package com.taarifu.media.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Upload-policy settings for the media attachment pipeline — the content-type allow-list and max object
 * size enforced at the {@code confirm} step (PRD §15, §18; ARCHITECTURE.md §7).
 *
 * <p>Responsibility: carries the two safety limits the {@code confirm} step checks before an uploaded
 * object becomes scan-eligible: which MIME content-types citizens may attach (evidence photos only by
 * default) and the maximum object size in bytes. Both ship with safe defaults so dev/test boot with zero
 * config (the compact-constructor defaults give the matchIfMissing behaviour).</p>
 *
 * <p>WHY enforce an allow-list (PRD §18): an attachment is citizen-supplied, attacker-controllable input.
 * Restricting to a small set of image types (and a size cap) limits the blast radius and keeps payloads
 * lean for low-bandwidth users (PRD §15). The malware scan (EI-8) is the deeper gate; this is the cheap
 * first filter at the boundary.</p>
 *
 * @param allowedContentTypes the permitted MIME content-types (case-insensitive); defaults to evidence-photo
 *                            image types.
 * @param maxSizeBytes        the maximum permitted object size in bytes; defaults to 10 MiB.
 */
@ConfigurationProperties(prefix = "taarifu.media.policy")
public record MediaPolicyProperties(
        List<String> allowedContentTypes,
        Long maxSizeBytes
) {

    /** Default evidence-photo content-types (common camera/gallery formats on feature and smartphones). */
    private static final List<String> DEFAULT_ALLOWED_CONTENT_TYPES =
            List.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif");

    /** Default max object size: 10 MiB — generous for a photo, bounded for low-bandwidth national scale. */
    private static final long DEFAULT_MAX_SIZE_BYTES = 10L * 1024 * 1024;

    /**
     * Applies safe defaults when properties are absent/blank so the module boots with no configuration.
     */
    public MediaPolicyProperties {
        if (allowedContentTypes == null || allowedContentTypes.isEmpty()) {
            allowedContentTypes = DEFAULT_ALLOWED_CONTENT_TYPES;
        }
        if (maxSizeBytes == null || maxSizeBytes <= 0) {
            maxSizeBytes = DEFAULT_MAX_SIZE_BYTES;
        }
    }

    /**
     * @param contentType the declared MIME type, or {@code null}.
     * @return {@code true} if present and on the allow-list (case-insensitive); {@code false} otherwise.
     */
    public boolean isContentTypeAllowed(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        return normalisedAllowed().contains(contentType.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * @param sizeBytes the declared size, or {@code null}.
     * @return {@code true} if present, positive, and {@code <= maxSizeBytes}.
     */
    public boolean isSizeAllowed(Long sizeBytes) {
        return sizeBytes != null && sizeBytes > 0 && sizeBytes <= maxSizeBytes;
    }

    /** @return the allow-list normalised to lower-case for case-insensitive matching. */
    private Set<String> normalisedAllowed() {
        return allowedContentTypes.stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
