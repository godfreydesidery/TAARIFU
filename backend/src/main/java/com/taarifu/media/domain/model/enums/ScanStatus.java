package com.taarifu.media.domain.model.enums;

/**
 * The malware-scan lifecycle of a {@link com.taarifu.media.domain.model.MediaObject} (PRD §21 EI-8,
 * ARCHITECTURE.md §7).
 *
 * <p>Responsibility: encodes the quarantine-until-clean state machine that decides whether a stored
 * object may ever be served. An object is uploaded into a <b>quarantine</b> location as {@link #PENDING};
 * the {@code MalwareScanner} returns a verdict that promotes it to {@link #CLEAN} (servable) or
 * {@link #INFECTED} (never servable), or {@link #FAILED} when the scan itself could not complete.</p>
 *
 * <p>WHY only {@link #CLEAN} is servable (the load-bearing integrity rule): unscanned, infected, or
 * scan-failed media must never reach another citizen's device (EI-8). The download path checks this
 * status and refuses anything other than {@code CLEAN}, so a missing/failed scan <b>fails safe</b>
 * (delivery deferred, no data loss) rather than fails open.</p>
 */
public enum ScanStatus {

    /**
     * Uploaded and awaiting a scan verdict. The object physically exists in the quarantine location but
     * is <b>not servable</b> — {@code presignDownload} refuses it. This is the initial state of every
     * upload and the state during the scanner-unavailable degradation (delivery deferred — EI-8).
     */
    PENDING,

    /**
     * Scanned and found safe. The <b>only</b> status for which a download URL is ever issued. A CLEAN
     * verdict promotes the object from quarantine to the served location.
     */
    CLEAN,

    /**
     * Scanned and found to contain malware. Permanently non-servable; the object is held (and may be
     * purged by an out-of-band janitor). Never transitions back to {@link #CLEAN}.
     */
    INFECTED,

    /**
     * The scan could not be completed (scanner error/timeout, corrupt object). Non-servable; treated
     * exactly like {@link #PENDING} for serving purposes (fail-safe), but distinguished so operations
     * can re-queue or alert (EI-8 degradation).
     */
    FAILED
}
