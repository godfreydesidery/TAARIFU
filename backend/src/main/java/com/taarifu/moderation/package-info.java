/**
 * Moderation &amp; Trust-and-Safety module — M12 (PRD §18, §25.8, Epic M12; ARCHITECTURE.md §3.1).
 *
 * <p>Responsibility: the platform's content-safety spine — citizens <b>flag</b> content; flags land in a
 * severity-prioritised <b>moderation queue</b> ({@code ModerationItem}); moderators take an append-only
 * <b>action</b> ({@code ModerationAction}: approve/hide/remove/warn/suspend/verify-request); and the
 * affected party may <b>appeal</b>, which is handled by a <i>different</i> moderator (appeal independence,
 * §25.8, Appendix F footnote ᵉ). Severity-based review SLAs are carried on the queue item (GBV/safety
 * ≤ hours; abuse/PII/spam ≤24h; general ≤72h — §25.8).</p>
 *
 * <p>Internal layering (ARCHITECTURE.md §3.3): {@code api → application → domain}. All moderated content
 * is referenced <b>by {@code (subjectType, subjectId UUID)} only</b> — this module never imports
 * reporting/engagement/communications/institutions (their entities live behind their own boundaries);
 * resolving a subject to a concrete record is a {@code // TODO(wiring)} for when those modules publish
 * their lookup APIs (ARCHITECTURE.md §3.2 rule 3). It depends only on {@code common} and {@code identity}
 * public surfaces.</p>
 *
 * <p>Integrity invariant (D16, §25.8): a moderator may not moderate their own content, nor handle an
 * appeal of their own action — enforced via {@code @taarifuAuthz.isNotSelf(...)} on the queue-action
 * endpoint and a distinct-moderator check on the appeal-decision endpoint, both audited.</p>
 */
package com.taarifu.moderation;
