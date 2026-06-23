package com.taarifu.institutions.domain.model.enums;

/**
 * Which legislature a {@link com.taarifu.institutions.domain.model.Representative} belongs to
 * (PRD §9.1, §22.6; D17).
 *
 * <p>Responsibility: separates the Union Parliament (Bunge la Muungano) from the Zanzibar House of
 * Representatives (Baraza la Wawakilishi). WHY model it now even though Zanzibar is Phase 2 (D17):
 * the generic model must already represent it so the mainland-first launch never has to re-shape the
 * schema when Zanzibar onboards — only seed data and endpoints are added (PRD §22.6, §25.10). A naive
 * single-parliament assumption would be a migration-breaking redesign later.</p>
 */
public enum Legislature {

    /** The Union Parliament of Tanzania (Bunge la Muungano) — mainland-first launch scope. */
    UNION_PARLIAMENT,

    /** The Zanzibar House of Representatives (Baraza la Wawakilishi) — Phase 2 (D17). */
    ZANZIBAR_HOR
}
