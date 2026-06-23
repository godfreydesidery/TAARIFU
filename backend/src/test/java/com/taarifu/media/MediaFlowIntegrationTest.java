package com.taarifu.media;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.common.security.JwtService;
import com.taarifu.media.domain.model.MediaObject;
import com.taarifu.media.domain.model.enums.ScanStatus;
import com.taarifu.media.domain.repository.MediaObjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MediaFlowIntegrationTest extends AbstractPostgisIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private MediaObjectRepository repository;

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
        mockMvc.perform(post("/media/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullFlow_uploadPendingThenScanCleanThenDownload() throws Exception {
        UUID ownerId = UUID.randomUUID();

        // 1) Request an upload URL → a PENDING record is created and a pre-signed PUT returned.
        String uploadJson = mockMvc.perform(post("/media/uploads")
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

        // 2) A download before scanning is refused with 409 CONFLICT (EI-8 serving rule).
        mockMvc.perform(get("/media/" + mediaId + "/download-url")
                        .header("Authorization", bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("CONFLICT"));

        // 3) The scan callback returns CLEAN → object is promoted and EXIF-strip seam flagged.
        mockMvc.perform(post("/media/" + mediaId + "/scan-callback")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"CLEAN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanStatus").value("CLEAN"))
                .andExpect(jsonPath("$.data.servable").value(true))
                .andExpect(jsonPath("$.data.exifStripped").value(true));

        // 4) Now a download URL is issued.
        mockMvc.perform(get("/media/" + mediaId + "/download-url")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downloadUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.contentType").value("image/jpeg"));
    }

    @Test
    void infectedObject_isNeverServed() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String uploadJson = mockMvc.perform(post("/media/uploads")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody(ownerId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String mediaId = jsonField(uploadJson, "mediaId");

        // Scanner finds malware.
        mockMvc.perform(post("/media/" + mediaId + "/scan-callback")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"INFECTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanStatus").value("INFECTED"))
                .andExpect(jsonPath("$.data.servable").value(false));

        // An infected object must NEVER be served — download stays a 409 (EI-8).
        mockMvc.perform(get("/media/" + mediaId + "/download-url")
                        .header("Authorization", bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("CONFLICT"));
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
