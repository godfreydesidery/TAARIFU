package com.taarifu.responders.domain.model.enums;

/**
 * Lifecycle/availability state of a {@link com.taarifu.responders.domain.model.Responder} capability
 * (PRD §24.1).
 *
 * <p>Responsibility: gates whether a responder capability participates in routing and the public
 * directory, independently of its parent organisation's status. A capability must be {@link #ACTIVE}
 * (and its organisation active + verified) to receive routed reports (§24.4).</p>
 *
 * <p>WHY a per-capability status (not only org status): an organisation may keep its water-outage
 * responder live while suspending another capability; status at the capability level lets routing be
 * switched off surgically without disabling the whole body.</p>
 */
public enum ResponderStatus {

    /** Configured but not yet activated; not listed, receives no routed reports. */
    PENDING,

    /** Live: participates in routing and (if the org is verified) appears in the public directory. */
    ACTIVE,

    /** Temporarily paused (governance/capacity) — excluded from routing; configuration retained. */
    SUSPENDED,

    /** Retired capability — retained for historical assignment integrity, never physically deleted. */
    RETIRED
}
