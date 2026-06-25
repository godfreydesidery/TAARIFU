package com.taarifu.privacy.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.privacy.api.SubjectExportContributor;
import com.taarifu.privacy.api.dto.SubjectDataExport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link SubjectDataExportService} — the contributor-registry export aggregator
 * (PDPA right of access; ADR-0016 §4).
 *
 * <p>Proves: (a) every contributor's slice is composed into the export keyed by {@code section()}; (b) a
 * contributor returning {@code null} (no data for the subject) is simply omitted; (c) one contributor
 * <b>throwing</b> never sinks the whole export — its section is dropped and the rest compose (resilience);
 * (d) the export act is audited. No Docker.</p>
 */
@ExtendWith(MockitoExtension.class)
class SubjectDataExportServiceTest {

    @Mock
    private AuditEventService audit;

    private final UUID subject = UUID.randomUUID();
    private final UUID actor = subject;
    private final Instant now = Instant.parse("2026-06-25T10:00:00Z");
    private final ClockPort clock = () -> now;

    /** A simple contributor stub returning a fixed slice (or throwing) for a named section. */
    private static SubjectExportContributor contributor(String section, Object slice, boolean fail) {
        return new SubjectExportContributor() {
            @Override
            public String section() {
                return section;
            }

            @Override
            public Object contribute(UUID subjectPublicId) {
                if (fail) {
                    throw new RuntimeException("boom");
                }
                return slice;
            }
        };
    }

    @Test
    void export_composesEveryContributorSlice_bySection_andAudits() {
        SubjectExportContributor identity = contributor("identity", "id-slice", false);
        SubjectExportContributor consents = contributor("consents", List.of("c1"), false);
        SubjectDataExportService service =
                new SubjectDataExportService(List.of(identity, consents), audit, clock);

        SubjectDataExport export = service.export(subject, actor);

        assertThat(export.subjectPublicId()).isEqualTo(subject);
        assertThat(export.generatedAt()).isEqualTo(now);
        assertThat(export.sections()).containsOnlyKeys("identity", "consents");
        assertThat(export.sections().get("identity")).isEqualTo("id-slice");

        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(ev.capture());
        assertThat(ev.getValue().getEventType()).isEqualTo(AuditEventType.PRIVACY_DSR_EXPORTED);
        assertThat(ev.getValue().getSubjectPublicId()).isEqualTo(subject);
    }

    @Test
    void export_omitsNullSlices_andSurvivesAFailingContributor() {
        SubjectExportContributor present = contributor("identity", "ok", false);
        SubjectExportContributor empty = contributor("reports", null, false);   // no data for this subject
        SubjectExportContributor broken = contributor("ratings", null, true);   // throws

        SubjectDataExportService service =
                new SubjectDataExportService(List.of(present, empty, broken), audit, clock);

        SubjectDataExport export = service.export(subject, actor);

        // Present section composed; null section omitted; failing section omitted (export still succeeds).
        assertThat(export.sections()).containsOnlyKeys("identity");
    }
}
