package com.taarifu.ussd.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.ussd.domain.model.UssdSession;
import com.taarifu.ussd.domain.repository.UssdSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Loads, renews, and persists {@link UssdSession} ephemeral state, and sweeps expired rows (PRD §14, EI-4).
 *
 * <p>Responsibility: the single seam between the menu machine and the DB-backed session store. It hides the
 * TTL policy (a short window matching the aggregator's session timeout) so the machine deals only in "load
 * the session for this key" / "save the advanced session". Keeping this behind a service also means a later
 * swap to Redis (PRD §16) is a change here alone — the machine is unaffected.</p>
 *
 * <p>WHY load excludes expired rows even though they are also swept: the aggregator can re-post a key whose
 * row has aged past its TTL but not yet been swept; treating an expired row as "no live session" gives
 * correct last-write-wins semantics without depending on sweep timing. The clock comes from the shared
 * {@link ClockPort} so tests are deterministic (CLAUDE.md §10).</p>
 */
@Service
public class UssdSessionStore {

    /** How long an idle USSD dialogue stays live; matches typical aggregator session windows (EI-4). */
    private static final Duration SESSION_TTL = Duration.ofMinutes(5);

    private final UssdSessionRepository sessions;
    private final ClockPort clock;

    /**
     * @param sessions session persistence.
     * @param clock    the shared clock (deterministic in tests).
     */
    public UssdSessionStore(UssdSessionRepository sessions, ClockPort clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * Loads the live session for a dialogue key, treating an expired row as absent.
     *
     * @param msisdn    the caller's phone.
     * @param sessionId the aggregator session id.
     * @return the live session, or empty for a fresh/expired dialogue.
     */
    @Transactional(readOnly = true)
    public Optional<UssdSession> findLive(String msisdn, String sessionId) {
        Instant now = clock.now();
        return sessions.findByMsisdnAndSessionId(msisdn, sessionId)
                .filter(s -> s.getExpiresAt().isAfter(now));
    }

    /**
     * Starts a fresh session for a key, with a TTL from now.
     *
     * @param msisdn    the caller's phone.
     * @param sessionId the aggregator session id.
     * @return the persisted new session at the language step.
     */
    @Transactional
    public UssdSession start(String msisdn, String sessionId) {
        UssdSession s = UssdSession.start(msisdn, sessionId, clock.now().plus(SESSION_TTL));
        return sessions.save(s);
    }

    /**
     * Renews the TTL and persists an advanced session.
     *
     * @param session the mutated session to save.
     * @return the saved session.
     */
    @Transactional
    public UssdSession save(UssdSession session) {
        session.renew(clock.now().plus(SESSION_TTL));
        return sessions.save(session);
    }

    /**
     * Removes expired session rows (TTL sweep). Intended to be invoked by a scheduled task or opportunistically.
     *
     * @return the number of rows removed.
     */
    @Transactional
    public int sweepExpired() {
        return sessions.deleteExpired(clock.now());
    }
}
