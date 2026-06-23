package com.taarifu.media.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for the S3-compatible {@link com.taarifu.media.domain.port.ObjectStore}
 * adapter, bound from {@code taarifu.media.s3.*} (PRD §21 EI-8, §18; ARCHITECTURE.md §7).
 *
 * <p>Responsibility: carries the <b>non-secret</b> connection settings the {@code S3ObjectStore} needs —
 * bucket, region, an optional endpoint override (so the same adapter targets AWS S3 <i>or</i> any
 * S3-compatible store such as MinIO), and a path-style-access flag (MinIO and other non-AWS stores
 * commonly require path-style addressing rather than virtual-hosted buckets).</p>
 *
 * <p>WHY no credentials here: access keys / secrets are <b>never</b> bound into source or config records
 * (CLAUDE.md §12, PRD §18). The adapter resolves credentials at runtime via the AWS
 * {@code DefaultCredentialsProvider} chain — environment variables, container/instance roles, or the
 * shared profile — so nothing sensitive is ever committed. This record only holds where to talk to and
 * which bucket, all of which are environment placeholders in {@code application.yml}.</p>
 *
 * <p>WHY a separate properties record (not {@code @Value} on the adapter): keeps the adapter testable and
 * the configuration surface explicit and documented in one place (KISS, CLAUDE.md §8), and lets the
 * unit test construct the adapter with hand-built properties and zero Spring context.</p>
 *
 * @param bucket        the target bucket all media keys live under; required when the S3 adapter is active.
 * @param region        the AWS region id (e.g. {@code af-south-1}); for MinIO any value the store accepts
 *                      (e.g. {@code us-east-1}) — SigV4 still requires a region to sign with.
 * @param endpoint      optional endpoint override URL; set for S3-compatible stores (MinIO), e.g.
 *                      {@code https://minio.internal:9000}. Left blank/null for real AWS S3 (the SDK then
 *                      derives the regional endpoint).
 * @param pathStyleAccess whether to use path-style addressing ({@code endpoint/bucket/key}) instead of
 *                      virtual-hosted ({@code bucket.endpoint/key}). Typically {@code true} for MinIO and
 *                      other non-AWS stores; {@code false}/default for AWS S3.
 */
@ConfigurationProperties(prefix = "taarifu.media.s3")
public record MediaStoreProperties(
        String bucket,
        String region,
        String endpoint,
        boolean pathStyleAccess
) {

    /** Default region used to sign requests when none is configured (kept signable for MinIO too). */
    private static final String DEFAULT_REGION = "us-east-1";

    /**
     * Applies a safe default region so SigV4 signing always has a region, and trims a blank endpoint to
     * {@code null} (so an empty placeholder behaves identically to "no override" → real AWS).
     *
     * <p>WHY default the region but NOT the bucket: signing needs <i>some</i> region or the SDK fails to
     * build; the bucket, by contrast, is environment-specific and must be supplied explicitly — a blank
     * bucket is rejected at adapter construction (fail-fast) rather than silently defaulted.</p>
     */
    public MediaStoreProperties {
        if (region == null || region.isBlank()) {
            region = DEFAULT_REGION;
        }
        if (endpoint != null && endpoint.isBlank()) {
            endpoint = null;
        }
    }

    /**
     * Whether a custom S3-compatible endpoint (e.g. MinIO) has been configured.
     *
     * @return {@code true} if an endpoint override is present (non-blank), {@code false} for default AWS S3.
     */
    public boolean hasEndpointOverride() {
        return endpoint != null;
    }
}
