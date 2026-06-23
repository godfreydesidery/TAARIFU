package com.taarifu.media.infrastructure.adapter;

import com.taarifu.media.domain.port.ObjectStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dev/sandbox {@link ObjectStore} adapter that fabricates local pre-signed URLs and tracks keys
 * in memory (ARCHITECTURE.md §7 — every port ships a stub).
 *
 * <p>Responsibility: lets the entire media flow (request-upload → quarantine → scan → serve) run E2E
 * with <b>zero external calls and no S3 SDK on the classpath</b>. It returns deterministic fake URLs of
 * the form {@code https://dev-object-store.local/<key>?...} and records which keys "exist" so
 * {@link #delete(String)} is observable in tests. This is what keeps CI hermetic until the real S3
 * adapter (a deferred dependency — see CENTRAL INTEGRATION NEEDS) is wired in production.</p>
 *
 * <p>WHY it never reaches the network: a stub that called a real store would defeat the purpose and
 * couple tests to credentials/infra (ARCHITECTURE.md §7). The URLs are syntactically valid and carry a
 * mock expiry so the contract shape the client sees is identical to production.</p>
 *
 * <p>Activated by {@code taarifu.media.object-store=stub} (the default in dev/test); the production S3
 * adapter is selected by {@code =s3} once that dependency lands.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.media.object-store", havingValue = "stub", matchIfMissing = true)
public class InMemoryObjectStore implements ObjectStore {

    /** Base host for the fabricated dev URLs; clearly non-routable so it is never mistaken for real storage. */
    private static final String DEV_BASE = "https://dev-object-store.local/";

    /** Keys that have had an upload URL minted — lets {@link #delete} be observable in tests. */
    private final Set<String> knownKeys = ConcurrentHashMap.newKeySet();

    /**
     * {@inheritDoc}
     *
     * <p>Records the key as known and returns a fake PUT URL binding {@code Content-Type}, mirroring how
     * a real signed PUT requires the client to replay that header.</p>
     */
    @Override
    public PresignedUrl presignUpload(String objectKey, String contentType, Duration ttl) {
        knownKeys.add(objectKey);
        Map<String, String> headers = contentType == null
                ? Map.of()
                : Map.of("Content-Type", contentType);
        return new PresignedUrl(devUrl(objectKey, "upload"), "PUT", headers, ttl.toSeconds());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a fake GET URL. The stub does not re-check scan state — that gate is enforced by the
     * application service before this is ever called (EI-8).</p>
     */
    @Override
    public PresignedUrl presignDownload(String objectKey, Duration ttl) {
        return new PresignedUrl(devUrl(objectKey, "download"), "GET", Map.of(), ttl.toSeconds());
    }

    /** {@inheritDoc} Forgets the key; idempotent (no error if absent). */
    @Override
    public void delete(String objectKey) {
        knownKeys.remove(objectKey);
    }

    /**
     * Test/diagnostic helper: whether a key has had an upload URL minted and not been deleted.
     *
     * @param objectKey the key to check.
     * @return {@code true} if the key is currently known to this stub.
     */
    public boolean exists(String objectKey) {
        return knownKeys.contains(objectKey);
    }

    /** Builds a deterministic, clearly-fake dev URL for the given key and operation. */
    private String devUrl(String objectKey, String op) {
        return DEV_BASE + objectKey + "?op=" + op + "&sig=dev-stub";
    }
}
