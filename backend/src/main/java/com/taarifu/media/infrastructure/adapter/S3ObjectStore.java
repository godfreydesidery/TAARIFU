package com.taarifu.media.infrastructure.adapter;

import com.taarifu.media.domain.port.ObjectStore;
import com.taarifu.media.infrastructure.config.MediaStoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Production {@link ObjectStore} adapter over an <b>S3-compatible</b> store (AWS S3 or MinIO) using AWS
 * SDK v2 presigned PUT/GET (PRD §21 EI-8, §15, §18; ARCHITECTURE.md §3.4/§7).
 *
 * <p>Responsibility: mint short-lived presigned URLs so the client transfers bytes <b>directly</b> to/from
 * the store — large media never streams through the application, which is essential for a low-bandwidth,
 * national-scale civic service (PRD §15). Uploads are signed into the caller-supplied <i>quarantine</i>
 * key; downloads are signed only for keys the application service has already verified as
 * scan-{@code CLEAN} (the EI-8 serving gate lives in the service, not this port).</p>
 *
 * <p>WHY {@code @ConditionalOnProperty(...="s3")} (no {@code matchIfMissing}): the in-memory
 * {@link InMemoryObjectStore} is the dev/test default ({@code object-store=stub}, match-if-missing), so
 * the application <b>still boots with zero AWS configuration</b>. This adapter — and the S3 SDK clients
 * it builds — only come into existence when an operator explicitly sets
 * {@code taarifu.media.object-store=s3}. The two adapters are mutually exclusive on the same property, so
 * exactly one {@link ObjectStore} bean ever exists.</p>
 *
 * <p>WHY credentials are NOT held here: the adapter never sees a secret in source. It signs with whatever
 * the AWS {@link DefaultCredentialsProvider} chain resolves at runtime (environment variables, container
 * or instance role, shared profile) — CLAUDE.md §12, PRD §18. Only the non-secret bucket/region/endpoint
 * come from {@link MediaStoreProperties}.</p>
 *
 * <p>WHY presigner + client are built in the constructor (not Spring {@code @Bean}s): keeps every AWS
 * type confined to this single {@code infrastructure.adapter} class (ARCHITECTURE.md §3.4 — vendor SDKs
 * never leak past infrastructure) and makes the adapter unit-testable by constructing it directly with a
 * static credentials provider, so the key/URL-building logic is verified with no real AWS call. The
 * {@code S3Presigner}/{@code S3Client} are thread-safe and long-lived; they are closed on shutdown.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.media.object-store", havingValue = "s3")
public class S3ObjectStore implements ObjectStore, AutoCloseable {

    /** Non-secret connection settings (bucket/region/endpoint/path-style). */
    private final MediaStoreProperties properties;

    /** Long-lived, thread-safe presigner used to mint PUT/GET URLs without transferring bytes. */
    private final S3Presigner presigner;

    /** Long-lived, thread-safe client used for the synchronous {@code delete} (no presign for delete). */
    private final S3Client client;

    /**
     * Production constructor: resolves credentials via the AWS {@link DefaultCredentialsProvider} chain.
     *
     * <p>Spring injects only {@link MediaStoreProperties}; the credentials provider is the default chain so
     * no secret is ever passed through configuration or source.</p>
     *
     * @param properties the bound {@code taarifu.media.s3.*} settings; must carry a non-blank bucket.
     */
    @Autowired
    public S3ObjectStore(MediaStoreProperties properties) {
        this(properties, DefaultCredentialsProvider.create());
    }

    /**
     * Full constructor (also the unit-test seam): builds the SDK presigner/client from the given
     * properties and credentials provider.
     *
     * <p>WHY a credentials-provider parameter: a unit test passes a <b>static</b> provider (dummy keys) so
     * URL building is exercised with <b>no network and no real AWS account</b>, while production uses the
     * default chain. The provider only affects the SigV4 signature, never which endpoint is contacted —
     * presigning is a pure local computation.</p>
     *
     * @param properties          the connection settings; the bucket must be present (fail-fast otherwise).
     * @param credentialsProvider the SigV4 credentials source (default chain in prod; static in tests).
     * @throws IllegalStateException if the bucket is absent — booting an active S3 store with no bucket is
     *                               a misconfiguration that must fail fast rather than 500 on first upload.
     */
    public S3ObjectStore(MediaStoreProperties properties, AwsCredentialsProvider credentialsProvider) {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new IllegalStateException(
                    "taarifu.media.s3.bucket must be set when taarifu.media.object-store=s3. "
                    + "Provide the bucket via the environment/secret manager.");
        }
        this.properties = properties;
        Region region = Region.of(properties.region());

        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentialsProvider);
        S3ClientBuilder clientBuilder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider);

        // Endpoint override + path-style addressing for S3-compatible stores (MinIO). For real AWS S3 the
        // endpoint is left to the SDK (regional) and virtual-hosted addressing is used.
        if (properties.hasEndpointOverride()) {
            URI endpoint = URI.create(properties.endpoint());
            presignerBuilder.endpointOverride(endpoint);
            clientBuilder.endpointOverride(endpoint);
        }
        if (properties.pathStyleAccess()) {
            S3Configuration s3Config = S3Configuration.builder().pathStyleAccessEnabled(true).build();
            presignerBuilder.serviceConfiguration(s3Config);
            clientBuilder.serviceConfiguration(s3Config);
        }

        this.presigner = presignerBuilder.build();
        this.client = clientBuilder.build();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Signs a PUT for the quarantine key, binding {@code Content-Type} so the client must replay the
     * same header it declared — the presigned PUT's signature covers that header, so a mismatching upload
     * is rejected by the store. The returned URL is valid only for {@code ttl}.</p>
     */
    @Override
    public PresignedUrl presignUpload(String objectKey, String contentType, Duration ttl) {
        PutObjectRequest.Builder put = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey);
        Map<String, String> requiredHeaders;
        if (contentType == null || contentType.isBlank()) {
            requiredHeaders = Map.of();
        } else {
            put.contentType(contentType);
            // Surfaced so the client replays it verbatim; the signature binds it.
            requiredHeaders = Map.of("Content-Type", contentType);
        }
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(put.build())
                .build();
        var presigned = presigner.presignPutObject(presignRequest);
        return new PresignedUrl(presigned.url().toString(), "PUT", requiredHeaders, ttl.toSeconds());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Signs a GET for the supplied key. This adapter does NOT re-check scan state — the EI-8 serving
     * gate (only a {@code CLEAN} object is signed) is enforced by the application service before this is
     * called. A GET presigned URL carries no required request headers.</p>
     */
    @Override
    public PresignedUrl presignDownload(String objectKey, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(get)
                .build();
        var presigned = presigner.presignGetObject(presignRequest);
        return new PresignedUrl(presigned.url().toString(), "GET", Map.of(), ttl.toSeconds());
    }

    /**
     * {@inheritDoc}
     *
     * <p>S3 {@code DeleteObject} is idempotent — deleting a missing key returns success — which matches the
     * port contract (no error if absent). Used for INFECTED purges and PDPA byte-erasure (PRD §25.1).</p>
     */
    @Override
    public void delete(String objectKey) {
        client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .build());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads the full object via {@code GetObject} into memory. This is used only by the internal
     * EXIF/geo-strip worker (A6) on the application's own quarantine key for an object already capped by
     * upload policy (max ~10 MiB), so an in-memory read is bounded and safe — it is <b>not</b> a client
     * byte path (clients always go through pre-signed URLs). A missing key surfaces as
     * {@link ObjectNotFoundException} so the worker fails safe rather than mark a non-existent object
     * stripped/servable.</p>
     */
    @Override
    public byte[] getBytes(String objectKey) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .build();
        try {
            ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(get);
            return response.asByteArray();
        } catch (NoSuchKeyException e) {
            throw new ObjectNotFoundException(objectKey);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes (overwrites) the object via {@code PutObject} from an in-memory buffer — the scrubbed image
     * the strip worker re-stores in place at the same key. Records the content type on the stored object so
     * a later download serves the correct {@code Content-Type}.</p>
     */
    @Override
    public void putBytes(String objectKey, String contentType, byte[] bytes) {
        PutObjectRequest.Builder put = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey);
        if (contentType != null && !contentType.isBlank()) {
            put.contentType(contentType);
        }
        client.putObject(put.build(), RequestBody.fromBytes(bytes));
    }

    /**
     * Releases the underlying SDK HTTP/connection resources on application shutdown.
     *
     * <p>WHY {@link AutoCloseable}: Spring invokes a bean's {@code close()} on context shutdown, so the
     * presigner and client release their threads/connections cleanly rather than leaking on redeploy.</p>
     */
    @Override
    public void close() {
        presigner.close();
        client.close();
    }
}
