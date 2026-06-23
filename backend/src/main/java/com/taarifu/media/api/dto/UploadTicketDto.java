package com.taarifu.media.api.dto;

import java.util.Map;
import java.util.UUID;

/**
 * Response returned when an upload URL is requested: the media object's id plus the pre-signed PUT
 * the client uses to send bytes directly to object storage (PRD §21 EI-8, ARCHITECTURE.md §5).
 *
 * <p>Responsibility: hands the client everything it needs to complete the upload <b>and</b> later refer
 * to the attachment — the persisted object's {@code mediaId} (public id), the time-bounded pre-signed
 * {@code uploadUrl}, the HTTP method, and the headers it must replay to honour the signature. The object
 * is created in {@code PENDING}/quarantine state; it is not servable until scanned CLEAN.</p>
 *
 * @param mediaId          the new media object's public id (used to fetch the download URL later).
 * @param uploadUrl        the absolute, short-lived pre-signed PUT URL.
 * @param method           the HTTP method to use ({@code PUT}).
 * @param requiredHeaders  headers the client must send verbatim (e.g. {@code Content-Type}); may be empty.
 * @param expiresInSeconds the upload URL's remaining validity, in seconds.
 */
public record UploadTicketDto(
        UUID mediaId,
        String uploadUrl,
        String method,
        Map<String, String> requiredHeaders,
        long expiresInSeconds
) {
}
