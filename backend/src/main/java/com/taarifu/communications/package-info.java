/**
 * communications module — announcements, personalised feed, and notifications (ARCHITECTURE §3.1, PRD §13, M4/M5).
 *
 * <p>Responsibility: the citizen-facing information loop. <b>Announcements</b> ({@code Announcement}) are
 * geo-targeted messages published by officials/representatives/orgs, held for moderation when authored by
 * a new/untrusted author (US-4.1). The <b>personalised feed</b> is assembled per citizen from their
 * <b>follows</b> ({@code Subscription} over areas/representatives/categories) plus area-matched
 * announcements (UC-G04). <b>Notifications</b> ({@code Notification}) are per-recipient, per-channel
 * dispatch records governed by each citizen's {@code NotificationPreference} (per-channel/type opt-in,
 * quiet hours, language — PRD §13).</p>
 *
 * <p>Outbound delivery sits behind ports in {@code domain.port}: the pre-existing {@code SmsGateway} (from
 * the auth increment — reused, not redefined) plus {@code PushSender} and {@code EmailSender}, each with a
 * dev/test stub in {@code infrastructure.adapter} so the system runs with <b>zero external calls</b>
 * (ARCHITECTURE §7). Publishing appends {@code AnnouncementPublished} (the public event) to the shared
 * transactional outbox in the publish transaction (ADR-0014); the relay later delivers it to
 * {@code AnnouncementPublishedHandler}, which dispatches per preference — FEED always retained, PUSH→SMS
 * fallback (EI-5) — <b>idempotently</b> (a redelivered event never double-notifies, keyed on the
 * announcement id).</p>
 *
 * <p>Boundary discipline: cross-module entities (author/recipient profiles, geography areas, institutions
 * representatives, reporting categories) are referenced by public {@code UUID} only — never FK-joined —
 * and resolved through the owning module's public API (ARCHITECTURE §3.2). The {@code // TODO(wiring)}
 * notes mark the deferred cross-module integration points.</p>
 *
 * <p><b>Discovery + moderation wiring (ADR-0017 / ADR-0018; outbound {@code api → api} — ADR-0013 §1):</b>
 * on the went-live funnel ({@code AnnouncementService.publish}/{@code approveAndPublish}) this module pushes a
 * <b>public, PII-free projection</b> of a {@code PUBLISHED} announcement (title + localised body snippet +
 * area/category facets) to the search module's {@code SearchIndexApi}, and <b>removes</b> the projection for any
 * non-published state (draft/held/scheduled/expired) so nothing in flight is ever discoverable (PRD §18). For
 * moderation auto-assist it publishes {@code AnnouncementSubjectContentQuery} — the implementation of
 * {@code moderation.api.SubjectContentQueryApi} for {@code FlagSubjectType.ANNOUNCEMENT} (the only moderatable
 * surface this module owns; it has no comment/reply entity) — surfacing a flagged announcement's bilingual body
 * <b>transiently</b> to the scorer. Both are sanctioned feature→foundation {@code api} dependencies (no cycle,
 * no reach-in); auto-assist is assist-only and degrades to the human pipeline (EI-18).</p>
 *
 * <p><b>Published command ports (ADR-0013 §1; A3/ADR-0019):</b> this module exposes two synchronous command
 * ports in {@code com.taarifu.communications.api} for sibling channels (above all {@code ussd}) that need SMS
 * delivery or an area follow but must not import this module's internals: {@code SmsSendApi} (façade over the
 * internal {@code SmsGateway} — masked, fail-soft, no tokens) and {@code AreaSubscriptionApi} (idempotent
 * {@code AREA} follow at the fan-out's <b>profile</b> grain). Both carry no token in/out (the civic-integrity
 * fence, D18) and no PII beyond the unavoidable raw recipient that crosses only into the masking gateway (S-4).</p>
 */
package com.taarifu.communications;
