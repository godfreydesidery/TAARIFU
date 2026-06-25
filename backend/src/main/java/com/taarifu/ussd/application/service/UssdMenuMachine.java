package com.taarifu.ussd.application.service;

import com.taarifu.ussd.api.dto.UssdGatewayRequest;
import com.taarifu.ussd.api.dto.UssdGatewayResponse;
import com.taarifu.ussd.application.port.UssdGeographyPort;
import com.taarifu.ussd.application.port.UssdIdentityPort;
import com.taarifu.ussd.application.port.UssdReportingPort;
import com.taarifu.ussd.application.port.UssdReportingPort.UssdCategoryOption;
import com.taarifu.ussd.application.port.UssdReportingPort.UssdReportStatus;
import com.taarifu.ussd.application.port.UssdSmsSender;
import com.taarifu.ussd.domain.model.UssdSession;
import com.taarifu.ussd.domain.model.enums.UssdLanguage;
import com.taarifu.ussd.domain.model.enums.UssdStep;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The USSD menu <b>state machine</b> — the brain of the feature-phone channel (PRD §14, UC-D02, US-3.9,
 * EI-4, journey J2).
 *
 * <p>Responsibility: given one inbound keypress, load (or start) the {@link UssdSession} for the
 * {@code (msisdn, sessionId)} key, link/create the MSISDN account at T1, dispatch on the session's current
 * {@link UssdStep} + the latest input, mutate the session, persist it, and return a {@code CON}/{@code END}
 * reply. It drives the four flows from PRD §14:
 * <ol>
 *   <li><b>File a report</b>: category → use-registered-area-or-pick → short description → confirm → ticket
 *       code (also sent by SMS);</li>
 *   <li><b>Track a report</b>: ticket code → status;</li>
 *   <li><b>My-area alerts</b>: subscribe the registered area to SMS alerts;</li>
 *   <li><b>Help</b>.</li>
 * </ol>
 * Cross-module work goes through the consumer-owned ports ({@link UssdIdentityPort},
 * {@link UssdReportingPort}, {@link UssdSmsSender}) by public {@code UUID} only — no other module's internals
 * are touched (ADR-0013, the isolation rule).</p>
 *
 * <p><b>Integrity:</b> the file-report flow is the civic-core action; it is gated on the MSISDN-linked T1
 * account (tier) and <b>never</b> reads a token balance — the civic-integrity fence holds by construction
 * (D18, §23.5): there is no {@code tokens} dependency anywhere on this path. WHY the machine holds its own
 * per-step state instead of re-parsing the aggregator's accumulated {@code text}: state on a persisted row
 * is robust to the aggregator's quirks (re-sends, partial histories, 182-char truncation) and lets a single
 * step read just "this turn's input" — the last {@code *}-segment (EI-4, R29 aggregator unreliability).</p>
 */
@Service
public class UssdMenuMachine {

    /** How many categories to offer on the menu (kept small so the page fits the USSD limit). */
    private static final int MAX_CATEGORIES = 5;

    /** Minimum sane free-text length for a report description (rejects an empty/space press). */
    private static final int MIN_DESCRIPTION = 3;

    private final UssdSessionStore sessions;
    private final UssdIdentityPort identity;
    private final UssdReportingPort reporting;
    private final UssdGeographyPort geography;
    private final UssdAlertService alerts;
    private final UssdSmsSender sms;

    /**
     * @param sessions  session store (DB-backed ephemeral state).
     * @param identity  identity port (link/create by MSISDN; registered area).
     * @param reporting reporting port (categories, file, track).
     * @param geography geography port (resolve a typed ward code → ward id; A7).
     * @param alerts    area-alert subscription service.
     * @param sms       outbound SMS port (ticket confirmation).
     */
    public UssdMenuMachine(UssdSessionStore sessions, UssdIdentityPort identity, UssdReportingPort reporting,
                           UssdGeographyPort geography, UssdAlertService alerts, UssdSmsSender sms) {
        this.sessions = sessions;
        this.identity = identity;
        this.reporting = reporting;
        this.geography = geography;
        this.alerts = alerts;
        this.sms = sms;
    }

    /**
     * Handles one inbound USSD keypress and returns the next screen.
     *
     * @param request the validated aggregator payload (session id, MSISDN, accumulated text).
     * @return the {@code CON}/{@code END} reply to render on the handset.
     */
    @Transactional
    public UssdGatewayResponse handle(UssdGatewayRequest request) {
        String msisdn = normalise(request.msisdn());
        String input = lastSegment(request.text());

        UssdSession session = sessions.findLive(msisdn, request.sessionId()).orElse(null);
        if (session == null) {
            // Fresh dialogue: start the session and link/create the MSISDN account (T1) up front so every
            // later flow has the reporter id without a second resolve (EI-4, US-0.1).
            session = sessions.start(msisdn, request.sessionId());
            UUID userId = identity.linkOrCreateByMsisdn(msisdn);
            session.linkUser(userId);
            sessions.save(session);
            return UssdGatewayResponse.con(UssdMenus.languagePrompt());
        }

        UssdGatewayResponse response = switch (session.getStep()) {
            case LANGUAGE -> onLanguage(session, input);
            case MAIN_MENU -> onMainMenu(session, input);
            case FILE_CATEGORY -> onFileCategory(session, input);
            case FILE_AREA_CHOICE -> onFileAreaChoice(session, input);
            case FILE_AREA_PICK -> onFileAreaPick(session, input);
            case FILE_DESCRIPTION -> onFileDescription(session, input);
            case FILE_CONFIRM -> onFileConfirm(session, input);
            case TRACK_CODE -> onTrackCode(session, input);
            case ALERTS_CONFIRM -> onAlertsConfirm(session, input);
            // A DONE row should not be re-hit (the aggregator closed the dialogue), but be defensive.
            case DONE -> UssdGatewayResponse.end(UssdMenus.expired(lang(session)));
        };

        if (response.terminal()) {
            session.moveTo(UssdStep.DONE);
        }
        sessions.save(session);
        return response;
    }

    // --- Step handlers ---------------------------------------------------------------------------------

    /** LANGUAGE: 1 → Swahili, 2 → English; then show the main menu. */
    private UssdGatewayResponse onLanguage(UssdSession s, String input) {
        UssdLanguage chosen = switch (input) {
            case "1" -> UssdLanguage.SW;
            case "2" -> UssdLanguage.EN;
            default -> null;
        };
        if (chosen == null) {
            return UssdGatewayResponse.con(UssdMenus.languagePrompt());
        }
        s.setLanguage(chosen);
        s.moveTo(UssdStep.MAIN_MENU);
        return UssdGatewayResponse.con(UssdMenus.mainMenu(chosen));
    }

    /** MAIN_MENU: route to file / track / alerts / help. */
    private UssdGatewayResponse onMainMenu(UssdSession s, String input) {
        UssdLanguage lang = lang(s);
        switch (input) {
            case "1" -> {
                s.moveTo(UssdStep.FILE_CATEGORY);
                return UssdGatewayResponse.con(UssdMenus.categoryMenu(lang, categories()));
            }
            case "2" -> {
                s.moveTo(UssdStep.TRACK_CODE);
                return UssdGatewayResponse.con(UssdMenus.trackPrompt(lang));
            }
            case "3" -> {
                s.moveTo(UssdStep.ALERTS_CONFIRM);
                return UssdGatewayResponse.con(UssdMenus.alertsConfirm(lang));
            }
            case "4" -> {
                return UssdGatewayResponse.end(UssdMenus.help(lang));
            }
            default -> {
                return UssdGatewayResponse.con(UssdMenus.mainMenu(lang));
            }
        }
    }

    /** FILE_CATEGORY: select a category by its 1-based number, then ask for the area. */
    private UssdGatewayResponse onFileCategory(UssdSession s, String input) {
        UssdLanguage lang = lang(s);
        List<UssdCategoryOption> categories = categories();
        Integer pick = parseChoice(input, categories.size());
        if (pick == null) {
            return UssdGatewayResponse.con(UssdMenus.categoryMenu(lang, categories));
        }
        s.setCategoryId(categories.get(pick - 1).categoryId());
        s.moveTo(UssdStep.FILE_AREA_CHOICE);
        boolean hasArea = identity.registeredWardId(s.getUserPublicId()).isPresent();
        return UssdGatewayResponse.con(UssdMenus.areaChoice(lang, hasArea));
    }

    /** FILE_AREA_CHOICE: 1 → use registered area, 2 → enter a ward code. */
    private UssdGatewayResponse onFileAreaChoice(UssdSession s, String input) {
        UssdLanguage lang = lang(s);
        Optional<UUID> registered = identity.registeredWardId(s.getUserPublicId());
        if ("1".equals(input) && registered.isPresent()) {
            s.setWardId(registered.get());
            s.moveTo(UssdStep.FILE_DESCRIPTION);
            return UssdGatewayResponse.con(UssdMenus.descriptionPrompt(lang));
        }
        if ("2".equals(input)) {
            s.moveTo(UssdStep.FILE_AREA_PICK);
            return UssdGatewayResponse.con(UssdMenus.areaPickPrompt(lang));
        }
        return UssdGatewayResponse.con(UssdMenus.areaChoice(lang, registered.isPresent()));
    }

    /** FILE_AREA_PICK: accept a typed ward code, resolve it, then ask for the description. */
    private UssdGatewayResponse onFileAreaPick(UssdSession s, String input) {
        UssdLanguage lang = lang(s);
        UUID wardId = resolveWardCode(input);
        if (wardId == null) {
            return UssdGatewayResponse.con(UssdMenus.invalid(lang) + "\n" + UssdMenus.areaPickPrompt(lang));
        }
        s.setWardId(wardId);
        s.moveTo(UssdStep.FILE_DESCRIPTION);
        return UssdGatewayResponse.con(UssdMenus.descriptionPrompt(lang));
    }

    /** FILE_DESCRIPTION: capture the free text, then confirm. */
    private UssdGatewayResponse onFileDescription(UssdSession s, String input) {
        UssdLanguage lang = lang(s);
        String text = input == null ? "" : input.trim();
        if (text.length() < MIN_DESCRIPTION) {
            return UssdGatewayResponse.con(UssdMenus.descriptionPrompt(lang));
        }
        s.setDescription(text);
        s.moveTo(UssdStep.FILE_CONFIRM);
        return UssdGatewayResponse.con(UssdMenus.confirmPrompt(lang));
    }

    /**
     * FILE_CONFIRM: 1 → file the report (then SMS the ticket), 2 → cancel.
     *
     * <p>Civic-integrity fence (D18): filing reads only the reporter (T1 account), category, ward, and
     * description — never a token balance.</p>
     */
    private UssdGatewayResponse onFileConfirm(UssdSession s, String input) {
        UssdLanguage lang = lang(s);
        if ("2".equals(input)) {
            return UssdGatewayResponse.end(UssdMenus.cancelled(lang));
        }
        if (!"1".equals(input)) {
            return UssdGatewayResponse.con(UssdMenus.confirmPrompt(lang));
        }
        String ticket = reporting.fileReport(
                s.getUserPublicId(), s.getCategoryId(), s.getWardId(), s.getDescription());
        // Confirmation SMS with the ticket code (UC-D02). Failures degrade silently — the END line already
        // carries the code, and the SMS port queues/retries (EI-3).
        sms.send(s.getMsisdn(), UssdMenus.filedOk(lang, ticket), s.getSessionId() + ":FILE");
        return UssdGatewayResponse.end(UssdMenus.filedOk(lang, ticket));
    }

    /** TRACK_CODE: accept a ticket code and show its status. */
    private UssdGatewayResponse onTrackCode(UssdSession s, String input) {
        UssdLanguage lang = lang(s);
        if (input == null || input.isBlank()) {
            return UssdGatewayResponse.con(UssdMenus.trackPrompt(lang));
        }
        Optional<UssdReportStatus> status = reporting.trackByCode(input.trim());
        return status
                .map(st -> UssdGatewayResponse.end(UssdMenus.trackResult(lang, st.ticketCode(), st.status())))
                .orElseGet(() -> UssdGatewayResponse.end(UssdMenus.trackNotFound(lang)));
    }

    /** ALERTS_CONFIRM: 1 → subscribe the registered area, 2 → decline. */
    private UssdGatewayResponse onAlertsConfirm(UssdSession s, String input) {
        UssdLanguage lang = lang(s);
        if ("2".equals(input)) {
            return UssdGatewayResponse.end(UssdMenus.cancelled(lang));
        }
        if (!"1".equals(input)) {
            return UssdGatewayResponse.con(UssdMenus.alertsConfirm(lang));
        }
        Optional<UUID> registered = identity.registeredWardId(s.getUserPublicId());
        if (registered.isEmpty()) {
            return UssdGatewayResponse.end(UssdMenus.alertsNoArea(lang));
        }
        alerts.subscribeArea(s.getUserPublicId(), registered.get());
        return UssdGatewayResponse.end(UssdMenus.alertsOk(lang));
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    /** The offered category list (kept short for the USSD page). */
    private List<UssdCategoryOption> categories() {
        return reporting.topCategories(MAX_CATEGORIES);
    }

    /**
     * Resolves a citizen-typed ward identifier to a ward public id (A7, ADR-0019).
     *
     * <p>Tries the two forms a feature-phone user might enter, in order: (1) a raw <b>UUID</b> typed directly
     * (back-compat — e.g. a deep-linked id), and (2) a friendly official <b>ward code</b> resolved via
     * geography's published {@code WardCodeQueryApi} through the consumer-owned {@link UssdGeographyPort}. A
     * code is the realistic feature-phone input — a citizen can type a short Kata code but never a 36-char UUID.
     * An unrecognised entry yields {@code null} so the machine re-prompts (EI-3, deny-by-default), never crashes
     * the dialogue.</p>
     *
     * @param code the typed ward identifier (a UUID or an official ward code; case-insensitive for codes).
     * @return the ward id, or {@code null} if neither form resolves.
     */
    private UUID resolveWardCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim();
        // (1) Back-compat: a directly typed UUID is the ward id as-is.
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException notAUuid) {
            // (2) The realistic case: resolve a friendly ward code via geography's published lookup.
            return geography.wardIdByCode(trimmed).orElse(null);
        }
    }

    /** @return the session language, defaulting to Swahili (Swahili-first). */
    private static UssdLanguage lang(UssdSession s) {
        return s.getLanguage() == null ? UssdLanguage.SW : s.getLanguage();
    }

    /**
     * Parses a 1-based menu choice within {@code [1, count]}.
     *
     * @return the chosen number, or {@code null} if not a valid in-range integer.
     */
    private static Integer parseChoice(String input, int count) {
        if (input == null) {
            return null;
        }
        try {
            int n = Integer.parseInt(input.trim());
            return (n >= 1 && n <= count) ? n : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Returns this turn's input — the last {@code *}-delimited segment of the aggregator's accumulated text.
     *
     * <p>WHY the last segment: the aggregator appends each keypress to a {@code *}-joined string; the
     * machine tracks its own step state, so it needs only the most recent entry. An empty/blank string
     * (first hit) yields an empty string.</p>
     */
    private static String lastSegment(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int idx = text.lastIndexOf('*');
        return idx < 0 ? text : text.substring(idx + 1);
    }

    /**
     * Normalises an MSISDN to a stable key form (trim + strip spaces). Kept conservative — full E.164
     * normalisation belongs in the aggregator adapter (DI7); here we only need a consistent session key.
     */
    private static String normalise(String msisdn) {
        return msisdn == null ? "" : msisdn.trim().replace(" ", "");
    }
}
