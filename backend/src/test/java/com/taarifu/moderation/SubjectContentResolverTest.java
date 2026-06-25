package com.taarifu.moderation;

import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.api.SubjectContentQueryApi;
import com.taarifu.moderation.application.service.SubjectContentResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SubjectContentResolver} — the moderation dispatch to owner
 * {@link SubjectContentQueryApi} content ports for the auto-assist screen (US-12.3; ADR-0018; ADR-0013 §4c).
 *
 * <p>Responsibility: proves (a) a flagged subject is dispatched to the owner registered for its
 * {@link FlagSubjectType} and the owner's scorable text is returned; (b) an <b>unregistered</b> subject type
 * resolves to empty — so the auto-assist screen is skipped and the item still goes to a human (the EI-18
 * floor); (c) two owners claiming the same subject type are rejected at construction (ambiguous dispatch is a
 * wiring bug, not a silent override).</p>
 */
class SubjectContentResolverTest {

    /** A tiny in-test owner content port for a given subject type and (optional) text. */
    private static SubjectContentQueryApi port(FlagSubjectType type, String text) {
        return new SubjectContentQueryApi() {
            @Override
            public FlagSubjectType subjectType() {
                return type;
            }

            @Override
            public Optional<String> contentTextOf(UUID subjectId) {
                return Optional.ofNullable(text);
            }
        };
    }

    @Test
    void dispatchesToTheOwnerRegisteredForTheSubjectType() {
        SubjectContentResolver resolver = new SubjectContentResolver(List.of(
                port(FlagSubjectType.REPORT, "report body"),
                port(FlagSubjectType.PETITION, "petition body")));

        assertThat(resolver.contentTextOf(FlagSubjectType.REPORT, UUID.randomUUID()))
                .contains("report body");
        assertThat(resolver.contentTextOf(FlagSubjectType.PETITION, UUID.randomUUID()))
                .contains("petition body");
    }

    @Test
    void unregisteredSubjectType_resolvesEmpty_screenSkipped() {
        SubjectContentResolver resolver = new SubjectContentResolver(List.of(
                port(FlagSubjectType.REPORT, "report body")));

        // No owner registered for COMMENT → empty: the auto-assist screen is skipped (EI-18 — the flagged
        // item still goes to a human; absence of a content port never blocks flagging or silences content).
        assertThat(resolver.contentTextOf(FlagSubjectType.COMMENT, UUID.randomUUID())).isEmpty();
    }

    @Test
    void emptyRegistry_resolvesEmpty_launchReality() {
        // The launch reality: no owner has wired a content port yet. Every lookup is empty → no screen ever
        // runs, every flag still raises a human-reviewed item.
        SubjectContentResolver resolver = new SubjectContentResolver(List.of());

        assertThat(resolver.contentTextOf(FlagSubjectType.REPORT, UUID.randomUUID())).isEmpty();
    }

    @Test
    void duplicateSubjectTypeRegistration_isRejected() {
        // Two owners both claiming REPORT is an ambiguous-dispatch wiring bug → fail fast at construction.
        assertThatThrownBy(() -> new SubjectContentResolver(List.of(
                port(FlagSubjectType.REPORT, "a"),
                port(FlagSubjectType.REPORT, "b"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
