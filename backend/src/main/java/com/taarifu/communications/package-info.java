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
 */
package com.taarifu.communications;
