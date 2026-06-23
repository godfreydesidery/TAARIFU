package com.taarifu.engagement.domain.model.enums;

/**
 * Whether a {@link com.taarifu.engagement.domain.model.Survey} is a non-binding survey or a binding
 * poll (PRD §9.1 Survey/Poll, §12.2 M8).
 *
 * <p>Responsibility: the single field that, together with {@code Survey.binding}, decides the trust
 * gate for responding. A non-binding {@code SURVEY} accepts T2 responses; a {@code POLL} flagged
 * {@code binding} is a democratic-weight act and requires <b>T3 + one-per-person</b> (PRD §7.3 T3,
 * §23.5 integrity fence).</p>
 */
public enum SurveyType {

    /** Opinion gathering; non-binding; responding is a T2 action (PRD §7.3 T2 "respond to surveys"). */
    SURVEY,

    /** A poll; when {@code binding=true} it carries democratic weight (T3 + one-per-person — PRD §7.3). */
    POLL
}
