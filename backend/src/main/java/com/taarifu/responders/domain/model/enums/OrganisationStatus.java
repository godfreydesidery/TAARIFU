package com.taarifu.responders.domain.model.enums;

/**
 * Lifecycle state of a responder {@link com.taarifu.responders.domain.model.Organisation} (PRD §24.4).
 *
 * <p>Responsibility: gates whether an organisation (and therefore its {@code Responder} capabilities)
 * may appear in the public directory and receive routed reports. An organisation must be
 * {@link #ACTIVE} <b>and</b> verified before it goes live (§24.4 — "providers are verified before
 * going live"; impersonation guarded).</p>
 *
 * <p>WHY status is distinct from {@code verified} and from soft-delete: an organisation can be a real,
 * verified body that is temporarily {@link #SUSPENDED} (e.g. SLA-breach governance, §24.4) without
 * losing its verification or being tombstoned. The three concerns are orthogonal (KISS but explicit).</p>
 */
public enum OrganisationStatus {

    /** Registered but not yet reviewed/activated; not publicly listed, receives no routed reports. */
    PENDING,

    /** Live: may be listed publicly (if verified) and receive routed reports within its coverage. */
    ACTIVE,

    /** Temporarily withheld (governance/SLA action, §24.4) — hidden from routing; data retained. */
    SUSPENDED,

    /** Permanently disabled (e.g. body dissolved) — retained for historical case integrity, never deleted. */
    DISABLED
}
