package com.taarifu.media.infrastructure.adapter;

import com.taarifu.media.domain.port.ObjectStore;
import com.taarifu.media.infrastructure.config.MediaStoreProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link S3ObjectStore} — proves the key/presigned-URL building with <b>no real AWS</b>.
 *
 * <p>Responsibility: presigning in AWS SDK v2 is a pure local SigV4 computation (it never contacts the
 * store), so the adapter is constructed with a <b>static dummy credentials provider</b> and a MinIO-style
 * (path-style, endpoint-override) configuration. The tests assert the load-bearing contract shape: the
 * URL targets the configured bucket/key with path-style addressing, the correct HTTP method is returned,
 * a PUT surfaces the {@code Content-Type} required header (which the signature binds) while a GET surfaces
 * none, and a misconfigured (blank-bucket) adapter fails fast. No network, no Docker, no AWS account.</p>
 *
 * <p>WHY no Spring context: the adapter is plain (constructor-injected properties + credentials), so a
 * direct {@code new} is the cheapest, most honest test of the URL-building logic (KISS, CLAUDE.md §10).</p>
 */
class S3ObjectStoreTest {

    private static final String BUCKET = "taarifu-media";
    private static final String KEY = "quarantine/2026/06/abc123";

    /** MinIO-style config: explicit endpoint + path-style so the URL is deterministic and offline-signable. */
    private final MediaStoreProperties props = new MediaStoreProperties(
            BUCKET, "us-east-1", "https://minio.local:9000", true);

    /** Dummy SigV4 credentials — they only affect the signature, never which host is contacted. */
    private final S3ObjectStore store = new S3ObjectStore(
            props,
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret")));

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void presignUpload_buildsPathStylePutUrl_withContentTypeHeader() {
        ObjectStore.PresignedUrl presigned = store.presignUpload(KEY, "image/jpeg", Duration.ofMinutes(15));

        assertThat(presigned.method()).isEqualTo("PUT");
        // Path-style addressing places bucket then key in the path against the configured endpoint host.
        assertThat(presigned.url()).startsWith("https://minio.local:9000/" + BUCKET + "/" + KEY);
        // SigV4 query params are present → a genuinely signed URL, not a bare object URL.
        assertThat(presigned.url())
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                .contains("X-Amz-Signature=")
                .contains("X-Amz-Expires=900");
        // The declared Content-Type is surfaced so the client replays it verbatim (the signature binds it).
        assertThat(presigned.requiredHeaders()).containsEntry("Content-Type", "image/jpeg");
        assertThat(presigned.expiresInSeconds()).isEqualTo(900L);
    }

    @Test
    void presignUpload_withoutContentType_surfacesNoRequiredHeaders() {
        ObjectStore.PresignedUrl presigned = store.presignUpload(KEY, null, Duration.ofMinutes(10));

        assertThat(presigned.method()).isEqualTo("PUT");
        assertThat(presigned.requiredHeaders()).isEmpty();
        assertThat(presigned.url()).contains("X-Amz-Signature=");
    }

    @Test
    void presignDownload_buildsPathStyleGetUrl_withNoRequiredHeaders() {
        ObjectStore.PresignedUrl presigned = store.presignDownload(KEY, Duration.ofMinutes(5));

        assertThat(presigned.method()).isEqualTo("GET");
        assertThat(presigned.url()).startsWith("https://minio.local:9000/" + BUCKET + "/" + KEY);
        assertThat(presigned.url())
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                .contains("X-Amz-Signature=")
                .contains("X-Amz-Expires=300");
        // GET presigned URLs carry no required request headers.
        assertThat(presigned.requiredHeaders()).isEmpty();
        assertThat(presigned.expiresInSeconds()).isEqualTo(300L);
    }

    @Test
    void presignUpload_keyIsEncodedIntoUrlPath_notTheBucketSegment() {
        // The server-generated quarantine key must land as the object path, distinct from the bucket — a
        // regression here would mis-route uploads. Asserts the bucket and key appear as separate segments.
        ObjectStore.PresignedUrl presigned = store.presignUpload(KEY, "image/png", Duration.ofMinutes(15));

        assertThat(presigned.url()).contains("/" + BUCKET + "/quarantine/2026/06/abc123");
    }

    @Test
    void construction_withBlankBucket_failsFast() {
        // Booting an active S3 store with no bucket is a misconfiguration that must fail at startup, not on
        // the first upload. If this guard were removed, this test would fail (no exception thrown).
        MediaStoreProperties noBucket = new MediaStoreProperties("  ", "us-east-1", null, false);
        assertThatThrownBy(() -> new S3ObjectStore(
                noBucket,
                StaticCredentialsProvider.create(AwsBasicCredentials.create("a", "b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("taarifu.media.s3.bucket");
    }

    @Test
    void properties_blankEndpointAndRegion_defaultSafely() {
        // A blank endpoint placeholder behaves as "no override" (real AWS), and a blank region defaults so
        // SigV4 always has a region to sign with — guarding against empty env placeholders.
        MediaStoreProperties defaulted = new MediaStoreProperties(BUCKET, "  ", "  ", false);

        assertThat(defaulted.hasEndpointOverride()).isFalse();
        assertThat(defaulted.region()).isEqualTo("us-east-1");
    }
}
