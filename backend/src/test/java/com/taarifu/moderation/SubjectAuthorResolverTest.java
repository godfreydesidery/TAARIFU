package com.taarifu.moderation;

import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.api.SubjectAuthorQueryApi;
import com.taarifu.moderation.application.service.SubjectAuthorResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SubjectAuthorResolver} — the moderation dispatch to owner {@link SubjectAuthorQueryApi}
 * ports (ADR-0013 §4c).
 *
 * <p>Responsibility: proves (a) a flagged subject is dispatched to the owner registered for its
 * {@link FlagSubjectType} and the owner's author is returned; (b) an unregistered subject type resolves to
 * empty (no author to conflict with → D16 guard vacuously satisfied); (c) two owners claiming the same
 * subject type are rejected at construction (ambiguous dispatch is a wiring bug, not a silent override).</p>
 */
class SubjectAuthorResolverTest {

    /** A tiny in-test owner port for a given subject type and (optional) author. */
    private static SubjectAuthorQueryApi port(FlagSubjectType type, UUID author) {
        return new SubjectAuthorQueryApi() {
            @Override
            public FlagSubjectType subjectType() {
                return type;
            }

            @Override
            public Optional<UUID> authorOf(UUID subjectId) {
                return Optional.ofNullable(author);
            }
        };
    }

    @Test
    void dispatchesToTheOwnerRegisteredForTheSubjectType() {
        UUID reportAuthor = UUID.randomUUID();
        UUID petitionAuthor = UUID.randomUUID();
        SubjectAuthorResolver resolver = new SubjectAuthorResolver(List.of(
                port(FlagSubjectType.REPORT, reportAuthor),
                port(FlagSubjectType.PETITION, petitionAuthor)));

        assertThat(resolver.authorOf(FlagSubjectType.REPORT, UUID.randomUUID()))
                .contains(reportAuthor);
        assertThat(resolver.authorOf(FlagSubjectType.PETITION, UUID.randomUUID()))
                .contains(petitionAuthor);
    }

    @Test
    void unregisteredSubjectType_resolvesEmpty() {
        SubjectAuthorResolver resolver = new SubjectAuthorResolver(List.of(
                port(FlagSubjectType.REPORT, UUID.randomUUID())));

        // No owner registered for COMMENT → empty (deny-by-default: no author to self-conflict against).
        assertThat(resolver.authorOf(FlagSubjectType.COMMENT, UUID.randomUUID())).isEmpty();
    }

    @Test
    void duplicateSubjectTypeRegistration_isRejected() {
        // Two owners both claiming REPORT is an ambiguous-dispatch wiring bug → fail fast at construction.
        assertThatThrownBy(() -> new SubjectAuthorResolver(List.of(
                port(FlagSubjectType.REPORT, UUID.randomUUID()),
                port(FlagSubjectType.REPORT, UUID.randomUUID()))))
                .isInstanceOf(IllegalStateException.class);
    }
}
