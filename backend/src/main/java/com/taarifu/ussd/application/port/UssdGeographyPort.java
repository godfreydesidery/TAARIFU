package com.taarifu.ussd.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Consumer-owned port describing what the USSD flows need from <b>geography</b>: resolve a citizen-typed ward
 * (Kata) <b>code</b> to its ward public id, so a feature-phone user can pin a report to a ward by typing a short
 * friendly code instead of a UUID (PRD §9.0/§14, UC-D02, A7; ADR-0013, ADR-0019).
 *
 * <p>Responsibility: capture the geography contract this module depends on, decoupled from geography's
 * internals. The USSD "enter a ward code" step calls this to turn the keypress into the ward id the file flow
 * carries forward — the minimum pin granularity (PRD §9.0).</p>
 *
 * <p>WHY this interface lives in the <b>consumer</b> module (not imported from geography): per the isolation
 * rule this module must not import geography's {@code application}/{@code domain}. The seam is defined here and
 * bound (by {@code GeographyUssdAdapter}) to geography's published {@code WardCodeQueryApi} query port — the
 * sanctioned synchronous {@code ussd → geography} read (ADR-0013 §1). The port speaks only public {@code UUID}s
 * /codes — never a geography entity (ARCHITECTURE §3.2). A miss is an empty (the machine re-prompts), never an
 * exception (EI-3).</p>
 */
public interface UssdGeographyPort {

    /**
     * Resolves a typed ward code to a ward public id, or empty if unrecognised.
     *
     * @param wardCode the ward code the citizen typed (case-insensitive; surrounding whitespace ignored).
     * @return the ward public id, or empty if no live ward has that code.
     */
    Optional<UUID> wardIdByCode(String wardCode);
}
