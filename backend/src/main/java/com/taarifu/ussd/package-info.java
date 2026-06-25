/**
 * ussd module — the USSD/SMS feature-phone channel (ARCHITECTURE §3.1, PRD §14, §10 US-3.9,
 * UC-D02/D09, EI-3/EI-4, journey J2).
 *
 * <p>Responsibility: a <b>session state machine</b> that lets a feature-phone citizen, over a TCRA-licensed
 * shortcode, do the civic-core minimum entirely in Swahili — file a report, track a report, subscribe to
 * my-area alerts, and get help — without a smartphone, an app, or a data plan. The aggregator delivers each
 * keypress as an inbound webhook ({@code MSISDN + sessionId + text}); this module advances an
 * <b>ephemeral session</b> and replies with a {@code CON} (continue, show more menu) or {@code END}
 * (terminate, final line) string. Account is <b>auto-linked/created by MSISDN at trust tier T1</b>
 * (EI-4, US-0.1) — no OTP round-trip, because a feature-phone user owns the SIM that proves the number.</p>
 *
 * <p>Inclusion is the whole point (PRD §14, §15): menus are short and <b>GSM-7-safe</b> (no diacritics, ≤182
 * chars per USSD page) so they survive the narrowest handset and the cheapest aggregator route, and the
 * citizen is never priced or gated out of being heard — USSD civic actions are free (PRD §23 "USSD/SMS users
 * get equivalent quotas, no purchase required").</p>
 *
 * <p><b>Session state is DB-backed</b> ({@code ussd_session}), not Redis: the architecture permits
 * "Redis-or-DB-backed" (PRD §16, EI-4) and the build has no Redis dependency yet. Rows are keyed by
 * {@code (msisdn, session_id)}, carry the current {@code step} + accumulated answers, and expire
 * ({@code expires_at}) so an abandoned session self-cleans — exactly the ephemeral semantics the aggregator
 * needs. Swapping to Redis later is a port-impl change behind {@code UssdSessionStore}, no flow change.</p>
 *
 * <p><b>Boundary discipline (ADR-0013, the parallel-build isolation rule):</b> the flows drive the other
 * modules — sign up by MSISDN (identity), file/track a report (reporting), resolve a ward code (geography),
 * send outbound SMS + forward an area-alert intent (communications) — <b>only through consumer-owned ports</b>
 * in {@code application.port}, referencing everything by public {@code UUID}/code. This module never imports
 * another module's {@code domain}/{@code infrastructure}/{@code repository}. Each consumer-owned port is bound
 * by an {@code infrastructure.adapter} to the callee's published {@code com.taarifu.<callee>.api} port: identity
 * ({@code AccountProvisioningApi}), reporting ({@code UssdReportApi}), geography ({@code WardCodeQueryApi} — A7,
 * the friendly ward-code lookup), and communications ({@code SmsSendApi} for the ticket-confirmation SMS, and
 * {@code AreaSubscriptionApi} for area-alert forwarding — A3/ADR-0019). The area-alert <b>forward call</b>
 * stays config-gated (default off) pending identity exposing account→profile resolution so the follow is keyed
 * at the fan-out's profile grain — see {@code UssdAlertService} and CENTRAL INTEGRATION NEEDS.</p>
 */
package com.taarifu.ussd;
