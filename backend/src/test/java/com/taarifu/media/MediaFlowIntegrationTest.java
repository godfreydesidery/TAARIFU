package com.taarifu.media;

import com.taarifu.AbstractHttpIntegrationTest;
import com.taarifu.common.security.JwtService;
import com.taarifu.media.domain.model.MediaObject;
import com.taarifu.media.domain.model.enums.ScanStatus;
import com.taarifu.media.domain.repository.MediaObjectRepository;
import com.taarifu.media.infrastructure.adapter.InMemoryObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testcontainers integration test for the media quarantine-then-serve flow over HTTP
 * (PRD §21 EI-8, §18, ADR-0009).
 *
 * <p>Responsibility: exercises the whole slice end-to-end against a real PostGIS database — entity →
 * repository → service → controller → {@link com.taarifu.common.api.dto.ApiResponse} envelope — proving
 * the migration-aligned schema persists, the envelope shape, method-security (unauthenticated is
 * rejected), and above all the EI-8 serving rule: a PENDING object is refused a download URL (409), a
 * CLEAN scan callback promotes it, and only then is a download URL issued.</p>
 *
 * <p>WHY the scanner stub is not relied on for the verdict: the realistic seam is the asynchronous
 * scan callback, so the test drives the verdict explicitly through the callback endpoint — the same path
 * a real engine uses (ARCHITECTURE.md §7).</p>
 *
 * <p>Runs under the {@code test} profile (create-drop on PostGIS) so it does not require Docker locally
 * to be wired into anyone else's build; CI runs it with Docker available (ADR-0009).</p>
 */
class MediaFlowIntegrationTest extends AbstractHttpIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private MediaObjectRepository repository;
    // The dev/test object store (stub) — lets the test seed the uploaded bytes so the CLEAN-verdict
    // EXIF-strip worker (A6) has bytes to scrub, mirroring a completed pre-signed PUT.
    @Autowired private InMemoryObjectStore objectStore;

    private String bearer;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        // A stateless access token authenticates the caller; the media endpoints only require
        // isAuthenticated(), so no persisted user/role is needed (the JWT filter does not hit the DB).
        String token = jwtService.issueAccessToken(UUID.randomUUID(), List.of("CITIZEN"), "T1");
        bearer = "Bearer " + token;
    }

    @Test
    void unauthenticatedUpload_isRejected() throws Exception {
        mockMvc.perform(post("/api/v1/media/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullFlow_uploadPendingThenScanCleanThenDownload() throws Exception {
        UUID ownerId = UUID.randomUUID();

        // 1) Request an upload URL → a PENDING record is created and a pre-signed PUT returned.
        String uploadJson = mockMvc.perform(post("/api/v1/media/uploads")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.uploadUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.method").value("PUT"))
                .andReturn().getResponse().getContentAsString();
        String mediaId = jsonField(uploadJson, "mediaId");

        // The persisted object is PENDING/quarantined and not yet servable.
        MediaObject persisted = repository.findByPublicId(UUID.fromString(mediaId)).orElseThrow();
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.PENDING);
        assertThat(persisted.getObjectKey()).startsWith("quarantine/");

        // Seed the uploaded bytes (a JPEG carrying an APP1/EXIF segment) as if the client had completed the
        // pre-signed PUT — so the CLEAN-verdict EXIF-strip worker (A6) has real bytes to scrub.
        objectStore.putBytesForTest(persisted.getObjectKey(), jpegWithExif());

        // 2) A download before scanning is refused with 409 CONFLICT (EI-8 serving rule).
        mockMvc.perform(get("/api/v1/media/" + mediaId + "/download-url")
                        .header("Authorization", bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("CONFLICT"));

        // 3) The scan callback returns CLEAN → the EXIF-strip worker scrubs the bytes in place and the
        // object is promoted (exifStripped=true). This is the A6 enforcement: the strip runs before serve.
        mockMvc.perform(post("/api/v1/media/" + mediaId + "/scan-callback")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"CLEAN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanStatus").value("CLEAN"))
                .andExpect(jsonPath("$.data.servable").value(true))
                .andExpect(jsonPath("$.data.exifStripped").value(true));

        // The bytes now stored at the key no longer carry the EXIF APP1 marker — the strip ran on real bytes.
        byte[] served = objectStore.getBytes(persisted.getObjectKey());
        assertThat(containsApp1Marker(served)).isFalse();

        // 4) Now a download URL is issued.
        mockMvc.perform(get("/api/v1/media/" + mediaId + "/download-url")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downloadUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.contentType").value("image/jpeg"));
    }

    @Test
    void infectedObject_isNeverServed() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String uploadJson = mockMvc.perform(post("/api/v1/media/uploads")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody(ownerId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String mediaId = jsonField(uploadJson, "mediaId");

        // Scanner finds malware.
        mockMvc.perform(post("/api/v1/media/" + mediaId + "/scan-callback")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"INFECTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanStatus").value("INFECTED"))
                .andExpect(jsonPath("$.data.servable").value(false));

        // An infected object must NEVER be served — download stays a 409 (EI-8).
        mockMvc.perform(get("/api/v1/media/" + mediaId + "/download-url")
                        .header("Authorization", bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("CONFLICT"));
    }

    /**
     * Builds a minimal, structurally-valid JPEG carrying an {@code APP1}/EXIF segment — the segment a
     * camera/phone uses to embed GPS. Used to prove the CLEAN-verdict worker actually scrubs real bytes.
     *
     * @return JPEG bytes containing an EXIF APP1 marker segment.
     */
    private static byte[] jpegWithExif() {
        byte[] exifPayload = {'E', 'x', 'i', 'f', 0x00, 0x00, 0x11, 0x22};
        int segLen = exifPayload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF); out.write(0xD8);                          // SOI
        out.write(0xFF); out.write(0xE1);                          // APP1 marker
        out.write((segLen >> 8) & 0xFF); out.write(segLen & 0xFF); // big-endian length
        out.write(exifPayload, 0, exifPayload.length);
        out.write(0xFF); out.write(0xDA);                          // SOS
        out.write(0x00); out.write(0x00);
        out.write(0x12);                                           // one byte of "scan data"
        out.write(0xFF); out.write(0xD9);                          // EOI
        return out.toByteArray();
    }

    /**
     * @param jpeg JPEG bytes.
     * @return {@code true} if the bytes still contain an {@code APP1} (0xFF 0xE1) marker — the EXIF segment.
     */
    private static boolean containsApp1Marker(byte[] jpeg) {
        for (int i = 0; i + 1 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xE1) {
                return true;
            }
        }
        return false;
    }

    /** Minimal valid upload request body for a given host id. */
    private static String uploadBody(UUID ownerId) {
        return """
                {"ownerType":"REPORT","ownerId":"%s","originalFilename":"evidence.jpg",
                 "contentType":"image/jpeg","sizeBytes":2048}
                """.formatted(ownerId);
    }

    /** Crude JSON field extractor for {@code $.data.<name>} (avoids pulling a JSON lib into the test). */
    private static String jsonField(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
