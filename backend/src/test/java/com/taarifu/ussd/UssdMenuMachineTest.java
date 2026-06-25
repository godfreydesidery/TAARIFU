package com.taarifu.ussd;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.ussd.api.dto.UssdGatewayRequest;
import com.taarifu.ussd.api.dto.UssdGatewayResponse;
import com.taarifu.ussd.application.port.UssdGeographyPort;
import com.taarifu.ussd.application.port.UssdIdentityPort;
import com.taarifu.ussd.application.port.UssdReportingPort;
import com.taarifu.ussd.application.port.UssdSmsSender;
import com.taarifu.ussd.application.port.UssdSubscriptionPort;
import com.taarifu.ussd.application.service.UssdAlertService;
import com.taarifu.ussd.application.service.UssdMenuMachine;
import com.taarifu.ussd.application.service.UssdSessionStore;
import com.taarifu.ussd.domain.model.UssdAlertSubscription;
import com.taarifu.ussd.domain.model.UssdSession;
import com.taarifu.ussd.domain.repository.UssdAlertSubscriptionRepository;
import com.taarifu.ussd.domain.repository.UssdSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link UssdMenuMachine} state machine — the four PRD §14 flows, driven keypress by
 * keypress with in-memory fakes (no Spring, no Docker) so they run in the CI unit phase.
 *
 * <p>The fakes persist session state across keypresses exactly as the DB would, so each test exercises the
 * real multi-turn dialogue. The integrity-fence test ({@link #fileReport_neverConsultsTokens()}) asserts
 * structurally that the file path uses only the reporter + category + ward + description — no token type is
 * reachable from the machine's ports (D18, §23.5).</p>
 */
class UssdMenuMachineTest {

    private FakeSessionRepo sessionRepo;
    private FakeAlertRepo alertRepo;
    private FakeIdentity identity;
    private FakeReporting reporting;
    private FakeGeography geography;
    private RecordingSubscriptions subscriptions;
    private RecordingSms sms;
    private UssdMenuMachine machine;

    private static final String MSISDN = "+255712345678";
    private static final String SID = "sess-1";

    @BeforeEach
    void setUp() {
        ClockPort clock = () -> Instant.parse("2026-06-23T09:00:00Z");
        sessionRepo = new FakeSessionRepo();
        alertRepo = new FakeAlertRepo();
        UssdSessionStore store = new UssdSessionStore(sessionRepo, clock);
        identity = new FakeIdentity();
        reporting = new FakeReporting();
        geography = new FakeGeography();
        subscriptions = new RecordingSubscriptions();
        sms = new RecordingSms();
        // forwardEnabled = false (the default) — area-alert intent is captured locally; the forward to
        // communications stays gated on the account→profile grain CENTRAL NEED (ADR-0019 §1b).
        UssdAlertService alertService = new UssdAlertService(alertRepo, subscriptions, false);
        machine = new UssdMenuMachine(store, identity, reporting, geography, alertService, sms);
    }

    /** A fresh dialogue (no/empty text) shows the language prompt and links the MSISDN account. */
    @Test
    void firstHit_showsLanguage_andLinksAccount() {
        UssdGatewayResponse r = hit("");
        assertThat(r.terminal()).isFalse();
        assertThat(r.body()).contains("Kiswahili", "English");
        assertThat(identity.linked).containsKey(MSISDN);
    }

    /** Language -> main menu -> file -> category -> use-my-area -> description -> confirm -> ticket + SMS. */
    @Test
    void fileReport_happyPath_withRegisteredArea_endsWithTicketAndSms() {
        UUID ward = UUID.randomUUID();
        // Pre-register a home ward for this MSISDN's account so "use my area" is offered.
        identity.registeredWard.put(identity.idFor(MSISDN), ward);

        hit("");                 // -> LANGUAGE
        hit("1");                // SW -> MAIN_MENU
        UssdGatewayResponse cat = hit("1*1");  // file -> FILE_CATEGORY (category menu)
        assertThat(cat.body()).contains("Maji");
        UssdGatewayResponse area = hit("1*1*1"); // pick category 1 -> FILE_AREA_CHOICE
        assertThat(area.body()).contains("Tumia eneo langu");
        hit("1*1*1*1");          // use my area -> FILE_DESCRIPTION
        hit("1*1*1*1*Bomba limepasuka"); // description -> FILE_CONFIRM
        UssdGatewayResponse done = hit("1*1*1*1*Bomba limepasuka*1"); // confirm -> file

        assertThat(done.terminal()).isTrue();
        assertThat(done.body()).contains("Tikiti:").contains("TAR-");
        // Report was filed with the registered ward and the linked reporter.
        assertThat(reporting.filed).hasSize(1);
        FakeReporting.Filed f = reporting.filed.get(0);
        assertThat(f.wardId()).isEqualTo(ward);
        assertThat(f.reporterId()).isEqualTo(identity.idFor(MSISDN));
        assertThat(f.description()).isEqualTo("Bomba limepasuka");
        // The ticket code was sent by SMS to the caller (UC-D02).
        assertThat(sms.sent).hasSize(1);
        assertThat(sms.sent.get(0).body()).contains(f.ticket());
    }

    /** Picking "2. enter ward code" accepts a typed ward UUID and files against it (back-compat). */
    @Test
    void fileReport_withTypedWardCode_filesAgainstThatWard() {
        UUID typedWard = UUID.randomUUID();
        hit("");
        hit("1");
        hit("1*1");
        hit("1*1*1");                 // -> FILE_AREA_CHOICE (no registered area => only option 2)
        hit("1*1*1*2");               // enter ward code -> FILE_AREA_PICK
        hit("1*1*1*2*" + typedWard);  // typed ward -> FILE_DESCRIPTION
        hit("1*1*1*2*" + typedWard + "*Barabara mbovu"); // -> FILE_CONFIRM
        UssdGatewayResponse done = hit("1*1*1*2*" + typedWard + "*Barabara mbovu*1");

        assertThat(done.terminal()).isTrue();
        assertThat(reporting.filed).hasSize(1);
        assertThat(reporting.filed.get(0).wardId()).isEqualTo(typedWard);
    }

    /**
     * A7 (ADR-0019): a citizen types a friendly <b>ward code</b> (not a UUID); it resolves via geography's
     * published ward-by-code lookup and the report files against the resolved ward. This is the realistic
     * feature-phone input (a short Kata code, never a 36-char UUID).
     */
    @Test
    void fileReport_withFriendlyWardCode_resolvesViaGeography_andFiles() {
        UUID resolvedWard = UUID.randomUUID();
        geography.byCode.put("KATA01", resolvedWard); // case-insensitive in the real port; fake matches as typed

        hit("");
        hit("1");
        hit("1*1");
        hit("1*1*1");                       // -> FILE_AREA_CHOICE
        hit("1*1*1*2");                     // enter ward code -> FILE_AREA_PICK
        hit("1*1*1*2*KATA01");              // typed ward CODE -> FILE_DESCRIPTION
        hit("1*1*1*2*KATA01*Taa za barabarani"); // -> FILE_CONFIRM
        UssdGatewayResponse done = hit("1*1*1*2*KATA01*Taa za barabarani*1");

        assertThat(done.terminal()).isTrue();
        assertThat(reporting.filed).hasSize(1);
        assertThat(reporting.filed.get(0).wardId()).isEqualTo(resolvedWard);
        // The code (not a UUID) was resolved through the geography port.
        assertThat(geography.queried).contains("KATA01");
    }

    /** An unrecognised ward code (neither a UUID nor a known code) re-prompts, does not advance. */
    @Test
    void fileReport_withUnknownWardCode_reprompts() {
        hit("");
        hit("1");
        hit("1*1");
        hit("1*1*1");
        hit("1*1*1*2");                     // enter ward code -> FILE_AREA_PICK
        UssdGatewayResponse r = hit("1*1*1*2*NOPE"); // unknown code -> still FILE_AREA_PICK

        assertThat(r.terminal()).isFalse();
        assertThat(reporting.filed).isEmpty();
    }

    /** Confirm = 2 cancels without filing. */
    @Test
    void fileReport_cancel_doesNotFile() {
        UUID ward = UUID.randomUUID();
        identity.registeredWard.put(identity.idFor(MSISDN), ward);
        hit("");
        hit("1");
        hit("1*1");
        hit("1*1*1");
        hit("1*1*1*1");
        hit("1*1*1*1*Tatizo fulani");
        UssdGatewayResponse done = hit("1*1*1*1*Tatizo fulani*2");

        assertThat(done.terminal()).isTrue();
        assertThat(done.body()).contains("Imeghairiwa");
        assertThat(reporting.filed).isEmpty();
        assertThat(sms.sent).isEmpty();
    }

    /** Track flow: a known ticket returns its status; an unknown one ends with not-found. */
    @Test
    void track_knownTicket_returnsStatus_unknown_returnsNotFound() {
        reporting.statusByCode.put("TAR-2026-000007", "IN_PROGRESS");
        hit("");
        hit("1");
        hit("2");                       // track -> TRACK_CODE
        UssdGatewayResponse ok = hit("2*TAR-2026-000007");
        assertThat(ok.terminal()).isTrue();
        assertThat(ok.body()).contains("TAR-2026-000007").contains("IN_PROGRESS");

        setUp();                        // fresh dialogue
        hit("");
        hit("1");
        hit("2");
        UssdGatewayResponse miss = hit("2*TAR-2026-999999");
        assertThat(miss.body()).contains("haikupatikana");
    }

    /** Alerts flow: with a registered area, subscribing records one live subscription. */
    @Test
    void alerts_withRegisteredArea_subscribes() {
        UUID ward = UUID.randomUUID();
        identity.registeredWard.put(identity.idFor(MSISDN), ward);
        hit("");
        hit("1");
        hit("3");                       // alerts -> ALERTS_CONFIRM
        UssdGatewayResponse done = hit("3*1");
        assertThat(done.terminal()).isTrue();
        assertThat(done.body()).contains("Umejisajili");
        assertThat(alertRepo.findByUserPublicIdAndWardId(identity.idFor(MSISDN), ward)).isPresent();
    }

    /** Alerts flow: no registered area => a helpful END, no subscription. */
    @Test
    void alerts_withoutRegisteredArea_endsWithGuidance() {
        hit("");
        hit("1");
        hit("3");
        UssdGatewayResponse done = hit("3*1");
        assertThat(done.terminal()).isTrue();
        assertThat(done.body()).contains("Huna eneo");
        assertThat(alertRepo.saved).isEmpty();
    }

    /** Help is a terminal screen. */
    @Test
    void help_endsImmediately() {
        hit("");
        hit("2");                       // English to exercise the EN branch
        UssdGatewayResponse done = hit("2*4");
        assertThat(done.terminal()).isTrue();
        assertThat(done.body()).contains("Taarifu");
    }

    /** An invalid language press re-shows the language prompt (does not advance). */
    @Test
    void invalidLanguage_reShowsPrompt() {
        hit("");
        UssdGatewayResponse r = hit("9");
        assertThat(r.terminal()).isFalse();
        assertThat(r.body()).contains("Kiswahili");
    }

    /**
     * Integrity fence (D18, §23.5): the machine is constructed with exactly the identity/reporting/sms/alert
     * ports and the session store — there is <b>no</b> token-bearing collaborator, so the civic-core file
     * path cannot read a token balance. This test fails if a tokens dependency is ever injected here.
     */
    @Test
    void fileReport_neverConsultsTokens() {
        for (Field f : UssdMenuMachine.class.getDeclaredFields()) {
            assertThat(f.getType().getName().toLowerCase())
                    .as("USSD machine must not depend on tokens (civic-integrity fence D18)")
                    .doesNotContain("token");
        }
    }

    // --- helpers + fakes -------------------------------------------------------------------------------

    private UssdGatewayResponse hit(String text) {
        return machine.handle(new UssdGatewayRequest(SID, MSISDN, "*149#", text));
    }

    /** In-memory session repo that persists rows by id and supports the key lookup + TTL delete. */
    private static final class FakeSessionRepo implements UssdSessionRepository {
        private final Map<Long, UssdSession> byId = new HashMap<>();
        private final AtomicLong seq = new AtomicLong(1);

        @Override
        public Optional<UssdSession> findByMsisdnAndSessionId(String msisdn, String sessionId) {
            return byId.values().stream()
                    .filter(s -> s.getMsisdn().equals(msisdn) && s.getSessionId().equals(sessionId))
                    .findFirst();
        }

        @Override
        public int deleteExpired(Instant now) {
            return 0;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <S extends UssdSession> S save(S entity) {
            Long id = idOf(entity);
            if (id == null) {
                id = seq.getAndIncrement();
                setId(entity, id);
            }
            byId.put(id, entity);
            return entity;
        }

        private static Long idOf(UssdSession s) {
            try {
                Field f = com.taarifu.common.domain.model.BaseEntity.class.getDeclaredField("id");
                f.setAccessible(true);
                return (Long) f.get(s);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void setId(UssdSession s, Long id) {
            try {
                Field f = com.taarifu.common.domain.model.BaseEntity.class.getDeclaredField("id");
                f.setAccessible(true);
                f.set(s, id);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        // --- unused JpaRepository surface ---
        @Override public void flush() { }
        @Override public <S extends UssdSession> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends UssdSession> List<S> saveAllAndFlush(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<UssdSession> entities) { }
        @Override public void deleteAllByIdInBatch(Iterable<Long> longs) { }
        @Override public void deleteAllInBatch() { }
        @Override public UssdSession getOne(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public UssdSession getById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public UssdSession getReferenceById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public <S extends UssdSession> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public List<UssdSession> findAll() { return new ArrayList<>(byId.values()); }
        @Override public List<UssdSession> findAllById(Iterable<Long> longs) { throw new UnsupportedOperationException(); }
        @Override public List<UssdSession> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<UssdSession> findAll(org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public Optional<UssdSession> findById(Long aLong) { return Optional.ofNullable(byId.get(aLong)); }
        @Override public boolean existsById(Long aLong) { return byId.containsKey(aLong); }
        @Override public long count() { return byId.size(); }
        @Override public void deleteById(Long aLong) { byId.remove(aLong); }
        @Override public void delete(UssdSession entity) { }
        @Override public void deleteAllById(Iterable<? extends Long> longs) { }
        @Override public void deleteAll(Iterable<? extends UssdSession> entities) { }
        @Override public void deleteAll() { byId.clear(); }
        @Override public <S extends UssdSession> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends UssdSession> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends UssdSession> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends UssdSession> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends UssdSession> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends UssdSession> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends UssdSession, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
    }

    /** In-memory alert repo supporting only the lookup + save the service uses. */
    private static final class FakeAlertRepo implements UssdAlertSubscriptionRepository {
        private final List<UssdAlertSubscription> saved = new ArrayList<>();

        @Override
        public Optional<UssdAlertSubscription> findByUserPublicIdAndWardId(UUID userPublicId, UUID wardId) {
            return saved.stream()
                    .filter(a -> a.getUserPublicId().equals(userPublicId) && a.getWardId().equals(wardId))
                    .findFirst();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <S extends UssdAlertSubscription> S save(S entity) {
            saved.add(entity);
            return entity;
        }

        // --- unused JpaRepository surface ---
        @Override public void flush() { }
        @Override public <S extends UssdAlertSubscription> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends UssdAlertSubscription> List<S> saveAllAndFlush(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<UssdAlertSubscription> entities) { }
        @Override public void deleteAllByIdInBatch(Iterable<Long> longs) { }
        @Override public void deleteAllInBatch() { }
        @Override public UssdAlertSubscription getOne(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public UssdAlertSubscription getById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public UssdAlertSubscription getReferenceById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public <S extends UssdAlertSubscription> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public List<UssdAlertSubscription> findAll() { return new ArrayList<>(saved); }
        @Override public List<UssdAlertSubscription> findAllById(Iterable<Long> longs) { throw new UnsupportedOperationException(); }
        @Override public List<UssdAlertSubscription> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<UssdAlertSubscription> findAll(org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public Optional<UssdAlertSubscription> findById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public long count() { return saved.size(); }
        @Override public void deleteById(Long aLong) { }
        @Override public void delete(UssdAlertSubscription entity) { }
        @Override public void deleteAllById(Iterable<? extends Long> longs) { }
        @Override public void deleteAll(Iterable<? extends UssdAlertSubscription> entities) { }
        @Override public void deleteAll() { saved.clear(); }
        @Override public <S extends UssdAlertSubscription> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends UssdAlertSubscription> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends UssdAlertSubscription> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends UssdAlertSubscription> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends UssdAlertSubscription> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends UssdAlertSubscription> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends UssdAlertSubscription, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
    }

    /** Fake identity port: deterministic account id per MSISDN + an optional registered ward. */
    private static final class FakeIdentity implements UssdIdentityPort {
        private final Map<String, UUID> linked = new HashMap<>();
        private final Map<UUID, UUID> registeredWard = new HashMap<>();

        UUID idFor(String msisdn) {
            return linked.computeIfAbsent(msisdn, m -> UUID.nameUUIDFromBytes(m.getBytes()));
        }

        @Override
        public UUID linkOrCreateByMsisdn(String msisdn) {
            return idFor(msisdn);
        }

        @Override
        public Optional<UUID> registeredWardId(UUID userPublicId) {
            return Optional.ofNullable(registeredWard.get(userPublicId));
        }
    }

    /** Fake reporting port: records filings and serves a fixed category list + a status map. */
    private static final class FakeReporting implements UssdReportingPort {
        private final List<Filed> filed = new ArrayList<>();
        private final Map<String, String> statusByCode = new HashMap<>();
        private int seq = 1;

        @Override
        public List<UssdCategoryOption> topCategories(int max) {
            return List.of(
                    new UssdCategoryOption(UUID.randomUUID(), "Maji"),
                    new UssdCategoryOption(UUID.randomUUID(), "Barabara"));
        }

        @Override
        public String fileReport(UUID reporterPublicId, UUID categoryId, UUID wardId, String description) {
            String code = "TAR-2026-" + String.format("%06d", seq++);
            filed.add(new Filed(reporterPublicId, categoryId, wardId, description, code));
            return code;
        }

        @Override
        public Optional<UssdReportStatus> trackByCode(String ticketCode) {
            String status = statusByCode.get(ticketCode);
            return Optional.ofNullable(status).map(st -> new UssdReportStatus(ticketCode, st));
        }

        record Filed(UUID reporterId, UUID categoryId, UUID wardId, String description, String ticket) {
        }
    }

    /** Records outbound SMS for assertions. */
    private static final class RecordingSms implements UssdSmsSender {
        private final List<Sent> sent = new ArrayList<>();

        @Override
        public void send(String recipientE164, String body, String idempotencyKey) {
            sent.add(new Sent(recipientE164, body, idempotencyKey));
        }

        record Sent(String to, String body, String key) {
        }
    }

    /** Fake geography port: resolves a friendly ward code to a ward id from a fixed map (A7). */
    private static final class FakeGeography implements UssdGeographyPort {
        private final Map<String, UUID> byCode = new HashMap<>();
        private final List<String> queried = new ArrayList<>();

        @Override
        public Optional<UUID> wardIdByCode(String wardCode) {
            queried.add(wardCode);
            return Optional.ofNullable(byCode.get(wardCode));
        }
    }

    /** Records area-subscription forwards (default forwarding is OFF, so this stays empty in these tests). */
    private static final class RecordingSubscriptions implements UssdSubscriptionPort {
        private final List<UUID[]> forwarded = new ArrayList<>();

        @Override
        public UUID subscribeArea(UUID subscriberProfilePublicId, UUID wardPublicId) {
            forwarded.add(new UUID[]{subscriberProfilePublicId, wardPublicId});
            return UUID.randomUUID();
        }
    }
}
