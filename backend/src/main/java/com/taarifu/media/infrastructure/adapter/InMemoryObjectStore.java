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
     * In-memory bytes for keys written via {@link #putBytes}. Lets the EXIF/geo-strip worker round-trip
     * (read → scrub → re-store) entirely in dev/test with no S3. A pre-signed PUT in this stub does not
     * actually deposit bytes (the client uploads to a fake URL), so a test seeds bytes via
     * {@link #putBytesForTest} (or the worker's own {@code putBytes}).
     */
    private final Map<String, byte[]> contents = new ConcurrentHashMap<>();

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

    /** {@inheritDoc} Forgets the key and any stored bytes; idempotent (no error if absent). */
    @Override
    public void delete(String objectKey) {
        knownKeys.remove(objectKey);
        contents.remove(objectKey);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the bytes previously stored at the key via {@link #putBytes} (or {@link #putBytesForTest}).
     * The pre-signed PUT in this stub does not deposit bytes, so a key the EXIF-strip worker must read has
     * to have been seeded; an absent key throws {@link ObjectNotFoundException} so the worker fails safe
     * rather than scrub-nothing and mark the object servable.</p>
     */
    @Override
    public byte[] getBytes(String objectKey) {
        byte[] stored = contents.get(objectKey);
        if (stored == null) {
            throw new ObjectNotFoundException(objectKey);
        }
        return stored.clone();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Stores the bytes in memory, replacing any prior content at the key (the strip worker re-stores the
     * scrubbed image in place). Also marks the key known so {@link #exists} reflects it.</p>
     */
    @Override
    public void putBytes(String objectKey, String contentType, byte[] bytes) {
        knownKeys.add(objectKey);
        contents.put(objectKey, bytes.clone());
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

    /**
     * Test seam: deposits bytes at a key as if a client had completed the pre-signed PUT, so a test can
     * exercise the EXIF-strip worker's read→scrub→re-store round-trip against this stub.
     *
     * @param objectKey the key to seed.
     * @param bytes     the bytes to store.
     */
    public void putBytesForTest(String objectKey, byte[] bytes) {
        putBytes(objectKey, null, bytes);
    }

    /** Builds a deterministic, clearly-fake dev URL for the given key and operation. */
    private String devUrl(String objectKey, String op) {
        return DEV_BASE + objectKey + "?op=" + op + "&sig=dev-stub";
    }
}
