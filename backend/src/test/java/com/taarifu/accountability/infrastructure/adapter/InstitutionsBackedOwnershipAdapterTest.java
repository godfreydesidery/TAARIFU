package com.taarifu.accountability.infrastructure.adapter;

import com.taarifu.institutions.api.RepresentativeQueryApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InstitutionsBackedOwnershipAdapter} — the real (wired-by-default)
 * {@code RepresentativeOwnershipPort} that replaces the deny-stub by delegating to institutions'
 * {@link RepresentativeQueryApi#ownsRepresentative} (PRD §10 US-6.2; D16). Mockito only.
 *
 * <p>Responsibility: proves the right-of-reply ownership fence is now genuinely backed by institutions — a rep
 * CAN reply to a rating about <b>themselves</b> (the port resolves them as the owner) and CANNOT reply to a
 * rating about a <b>rival</b> (the port resolves false). The adapter is a pure delegation, so the test pins that
 * it asks institutions the exact question with the caller's account id and the rated representative's id, and
 * returns institutions' answer verbatim (the fail-closed contract lives in institutions, asserted there).</p>
 */
class InstitutionsBackedOwnershipAdapterTest {

    private RepresentativeQueryApi representativeQueryApi;
    private InstitutionsBackedOwnershipAdapter adapter;

    private final UUID account = UUID.randomUUID();
    private final UUID representativeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        representativeQueryApi = mock(RepresentativeQueryApi.class);
        adapter = new InstitutionsBackedOwnershipAdapter(representativeQueryApi);
    }

    @Test
    void repCanReplyToOwnRating_whenInstitutionsConfirmsOwnership() {
        // Institutions confirms the caller's account IS the representative's linked account (§6.4).
        when(representativeQueryApi.ownsRepresentative(account, representativeId)).thenReturn(true);

        assertThat(adapter.isLinkedAccountOf(account, representativeId)).isTrue();
        // Pins the exact question: the caller's account + the RATED rep — never a body-supplied id.
        verify(representativeQueryApi).ownsRepresentative(account, representativeId);
    }

    @Test
    void repCannotReplyToAnothersRating_whenInstitutionsDeniesOwnership() {
        // The rated rep is a RIVAL — institutions denies ownership; the fence holds (conflict-of-interest).
        when(representativeQueryApi.ownsRepresentative(account, representativeId)).thenReturn(false);

        assertThat(adapter.isLinkedAccountOf(account, representativeId)).isFalse();
        verify(representativeQueryApi).ownsRepresentative(account, representativeId);
    }
}
