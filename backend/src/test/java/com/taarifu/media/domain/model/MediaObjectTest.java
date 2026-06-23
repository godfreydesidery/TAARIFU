package com.taarifu.media.domain.model;

import com.taarifu.media.domain.model.enums.ScanStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MediaObject} domain invariants (PRD §21 EI-8, §18).
 *
 * <p>Responsibility: pins the entity-level rules of the quarantine-then-serve state machine without any
 * Spring/DB — new objects start non-servable PENDING; only a CLEAN verdict makes them servable; a
 * terminal INFECTED never flips back to CLEAN even on a replayed verdict (at-least-once delivery
 * safety); and a {@code PENDING} "verdict" is rejected.</p>
 */
class MediaObjectTest {

    private MediaObject newPending() {
        return new MediaObject("REPORT", UUID.randomUUID(), "quarantine/2026/06/" + UUID.randomUUID(),
                "f.jpg", "image/jpeg", 10L, UUID.randomUUID());
    }

    @Test
    void newObject_isPending_andNotServable() {
        MediaObject media = newPending();
        assertThat(media.getScanStatus()).isEqualTo(ScanStatus.PENDING);
        assertThat(media.isServable()).isFalse();
        assertThat(media.isExifStripped()).isFalse();
    }

    @Test
    void cleanVerdict_makesServable() {
        MediaObject media = newPending();
        media.applyScanVerdict(ScanStatus.CLEAN);
        assertThat(media.isServable()).isTrue();
    }

    @Test
    void infectedVerdict_isTerminal_andNeverFlipsBackToClean() {
        MediaObject media = newPending();
        media.applyScanVerdict(ScanStatus.INFECTED);
        // A replayed/late CLEAN must NOT resurrect an infected object (safety; at-least-once delivery).
        media.applyScanVerdict(ScanStatus.CLEAN);
        assertThat(media.getScanStatus()).isEqualTo(ScanStatus.INFECTED);
        assertThat(media.isServable()).isFalse();
    }

    @Test
    void failedVerdict_isNotServable_butMayBeRescannedToClean() {
        MediaObject media = newPending();
        media.applyScanVerdict(ScanStatus.FAILED);
        assertThat(media.isServable()).isFalse();
        // A failed scan can be retried and later succeed (EI-8 deferred delivery).
        media.applyScanVerdict(ScanStatus.CLEAN);
        assertThat(media.isServable()).isTrue();
    }

    @Test
    void pendingVerdict_isRejected() {
        MediaObject media = newPending();
        assertThatThrownBy(() -> media.applyScanVerdict(ScanStatus.PENDING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markExifStripped_setsFlag() {
        MediaObject media = newPending();
        media.markExifStripped();
        assertThat(media.isExifStripped()).isTrue();
    }
}
