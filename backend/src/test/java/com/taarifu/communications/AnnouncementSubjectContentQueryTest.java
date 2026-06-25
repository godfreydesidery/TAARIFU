package com.taarifu.communications;

import com.taarifu.communications.application.service.AnnouncementSubjectContentQuery;
import com.taarifu.communications.domain.model.Announcement;
import com.taarifu.communications.domain.repository.AnnouncementRepository;
import com.taarifu.moderation.api.FlagSubjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnnouncementSubjectContentQuery} — communications' moderation content-lookup port for
 * {@link FlagSubjectType#ANNOUNCEMENT} subjects (US-12.3, ADR-0018; ADR-0013 §4c).
 *
 * <p>Responsibility: pin the auto-assist content contract — the port resolves a flagged announcement to its
 * <b>bilingual body</b> (SW + EN, body only, never the title), returns empty for a missing announcement (so
 * moderation skips the screen and the item still reaches a human — EI-18), and is registered against the
 * {@code ANNOUNCEMENT} subject type so moderation's resolver dispatches to it.</p>
 */
class AnnouncementSubjectContentQueryTest {

    private AnnouncementRepository announcementRepository;
    private AnnouncementSubjectContentQuery port;

    private final UUID author = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        announcementRepository = mock(AnnouncementRepository.class);
        port = new AnnouncementSubjectContentQuery(announcementRepository);
    }

    @Test
    void serves_announcement_subjectType() {
        // The registry key moderation dispatches on — communications owns ANNOUNCEMENT.
        assertThat(port.subjectType()).isEqualTo(FlagSubjectType.ANNOUNCEMENT);
    }

    @Test
    void contentTextOf_returnsBilingualBody_bodyOnly_notTitle() {
        Announcement a = Announcement.draft(author, "Kichwa cha Habari",
                "Maji yatakatika kesho.", "Water will be cut tomorrow.");
        when(announcementRepository.findByPublicId(any())).thenReturn(Optional.of(a));

        Optional<String> text = port.contentTextOf(UUID.randomUUID());

        assertThat(text).isPresent();
        // Both language bodies are screened so the Swahili-aware scorer sees SW and EN (and code-switching).
        assertThat(text.get()).contains("Maji yatakatika kesho.");
        assertThat(text.get()).contains("Water will be cut tomorrow.");
        // The title (a headline, not the moderatable body) is deliberately excluded.
        assertThat(text.get()).doesNotContain("Kichwa cha Habari");
    }

    @Test
    void contentTextOf_swahiliOnly_returnsSwahiliBody() {
        Announcement a = Announcement.draft(author, "Kichwa", "Mwili wa Kiswahili tu.", null);
        when(announcementRepository.findByPublicId(any())).thenReturn(Optional.of(a));

        assertThat(port.contentTextOf(UUID.randomUUID())).contains("Mwili wa Kiswahili tu.");
    }

    @Test
    void contentTextOf_missingAnnouncement_isEmpty_soScreenIsSkipped() {
        // Empty → moderation skips the auto-assist screen and the flagged item still reaches a human (EI-18).
        when(announcementRepository.findByPublicId(any())).thenReturn(Optional.empty());

        assertThat(port.contentTextOf(UUID.randomUUID())).isEmpty();
    }
}
