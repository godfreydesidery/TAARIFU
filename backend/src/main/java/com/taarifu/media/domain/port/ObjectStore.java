package com.taarifu.media.domain.port;

import java.time.Duration;

/**
 * Outbound port to S3-compatible object storage for the media module (PRD §21 EI-8,
 * ARCHITECTURE.md §3.3/§7).
 *
 * <p>Responsibility: abstracts "give me a time-bounded pre-signed URL to upload/download a key" and
 * "delete a key" so the application/domain never touch a vendor SDK (boundary rule — ARCHITECTURE.md
 * §3.4). The contract is deliberately small: the byte transfer happens directly between the client and
 * the store via the pre-signed URL, so large media never streams through the application — essential
 * for a low-bandwidth, national-scale service (PRD §15).</p>
 *
 * <p>WHY pre-signed URLs (not proxying bytes): it keeps the API stateless and cheap, offloads transfer
 * to the store, and lets the quarantine-then-serve flow (EI-8) be expressed purely as which <i>key</i>
 * a URL is minted for — upload mints into quarantine, download mints only for a scanned-CLEAN object.</p>
 *
 * <p>The production adapter wraps the AWS S3 SDK v2 (a deferred dependency — see CENTRAL INTEGRATION
 * NEEDS); a dev/in-memory stub adapter ships now so the whole system boots and tests run with zero
 * external calls (ARCHITECTURE.md §7 — every port has a stub).</p>
 */
public interface ObjectStore {

    /**
     * Mints a pre-signed URL the client uses to PUT the object bytes directly into storage.
     *
     * <p>The key supplied here is the <b>quarantine</b> key (the object is unscanned). No bytes flow
     * through the application; the client uploads straight to the store.</p>
     *
     * @param objectKey   the server-generated storage key to upload to.
     * @param contentType the declared MIME type the client must send (binds the signed request).
     * @param ttl         how long the URL stays valid; kept short to limit replay (PRD §18).
     * @return a {@link PresignedUrl} describing the URL, HTTP method, and required headers.
     */
    PresignedUrl presignUpload(String objectKey, String contentType, Duration ttl);

    /**
     * Mints a pre-signed URL the client uses to GET (download) the object bytes directly from storage.
     *
     * <p>The caller (application service) is responsible for ensuring the object is scanned
     * {@link com.taarifu.media.domain.model.enums.ScanStatus#CLEAN} before invoking this — the port
     * itself does not know the scan state; it only signs a key (EI-8 serving rule is enforced in the
     * service).</p>
     *
     * @param objectKey the storage key to download from (the served-location key for a CLEAN object).
     * @param ttl       how long the URL stays valid; short-lived (PRD §18).
     * @return a {@link PresignedUrl} for the GET.
     */
    PresignedUrl presignDownload(String objectKey, Duration ttl);

    /**
     * Permanently removes an object from storage (e.g. an INFECTED object purge, or PDPA erasure of an
     * attachment's bytes while the de-identified record/tombstone is retained — PRD §25.1).
     *
     * @param objectKey the storage key to delete; a no-op if it does not exist (idempotent).
     */
    void delete(String objectKey);

    /**
     * A minted pre-signed URL and the exact request shape the client must use to honour the signature.
     *
     * <p>WHY headers are returned: a signed PUT typically binds {@code Content-Type}; the client must
     * replay the same headers or the store rejects the request. Surfacing them keeps the contract
     * explicit rather than relying on client guesswork.</p>
     *
     * @param url               the absolute, time-bounded pre-signed URL.
     * @param method            the HTTP method to use ({@code PUT} for upload, {@code GET} for download).
     * @param requiredHeaders   headers the client must send verbatim (e.g. {@code Content-Type}); may be empty.
     * @param expiresInSeconds  the URL's remaining validity in seconds (echoed to the client).
     */
    record PresignedUrl(String url, String method,
                        java.util.Map<String, String> requiredHeaders, long expiresInSeconds) {
    }
}
