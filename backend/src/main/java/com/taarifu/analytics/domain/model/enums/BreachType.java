package com.taarifu.analytics.domain.model.enums;

/**
 * Which SLA clock was breached on a {@code REPORT_ESCALATED} event — the dimension behind the
 * SLA-breach heatmap (PRD Appendix E.1 {@code report_escalated.breach_type}; Appendix C; §3.3
 * Resolution/Responsiveness).
 *
 * <p>Responsibility: distinguishes a first-response breach from a resolution breach so the dashboard
 * can show TTFR vs TTR breach counts separately, per area and category (the §3.3 "median TTFR &lt; 48h"
 * and "TTR &lt; 30 days" targets, surfaced as breach pressure). Carried as a nullable column —
 * non-escalation events leave it {@code null}.</p>
 */
public enum BreachType {

    /** Time-to-first-response SLA was breached (status stayed NEW past its TTFR target). */
    TTFR,

    /** Time-to-resolution SLA was breached (case not RESOLVED within its TTR target). */
    TTR
}
