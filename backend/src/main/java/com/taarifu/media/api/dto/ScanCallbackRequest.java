package com.taarifu.media.api.dto;

import com.taarifu.media.domain.model.enums.ScanStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for the asynchronous scan-verdict callback (PRD §21 EI-8, ARCHITECTURE.md §5).
 *
 * <p>Responsibility: the validated input by which the malware-scanning pipeline reports the outcome of
 * scanning a quarantined object back to the platform, driving the {@code PENDING → CLEAN/INFECTED/FAILED}
 * transition and (on CLEAN) promotion to servable. The object is addressed by its public id in the path;
 * this body carries only the verdict.</p>
 *
 * <p>WHY the verdict is constrained to non-PENDING at the service layer: {@link ScanStatus#PENDING} is a
 * pre-scan state, never a scan <i>result</i>; a callback claiming PENDING is rejected as a bad request.</p>
 *
 * @param verdict the scan outcome: {@link ScanStatus#CLEAN}, {@link ScanStatus#INFECTED}, or
 *                {@link ScanStatus#FAILED}.
 */
public record ScanCallbackRequest(
        @NotNull ScanStatus verdict
) {
}
