package com.taarifu.geography;

import com.taarifu.geography.application.service.WardCodeQueryService;
import com.taarifu.geography.domain.model.enums.LocationType;
import com.taarifu.geography.domain.repository.LocationRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WardCodeQueryService} — the published {@code WardCodeQueryApi} impl (A7, ADR-0019).
 *
 * <p>Asserts the input normalisation + deny-by-default contract the USSD machine relies on: a known code
 * resolves to its ward id; the {@code WARD} type pin and case-fold are delegated to the repository; and a
 * {@code null}/blank input short-circuits to empty without touching the DB (so a fat-fingered feature-phone
 * entry re-prompts, never crashes — EI-3).</p>
 */
class WardCodeQueryServiceTest {

    private final LocationRepository repo = mock(LocationRepository.class);
    private final WardCodeQueryService service = new WardCodeQueryService(repo);

    /** A known ward code resolves to its ward public id, queried trimmed and WARD-pinned. */
    @Test
    void knownCode_resolvesToWardId() {
        UUID ward = UUID.randomUUID();
        when(repo.findPublicIdByCodeAndType(eq("KATA01"), eq(LocationType.WARD)))
                .thenReturn(Optional.of(ward));

        assertThat(service.wardIdByCode("  KATA01 ")).contains(ward);
    }

    /** An unknown code yields empty (the repository found no live WARD with that code). */
    @Test
    void unknownCode_isEmpty() {
        when(repo.findPublicIdByCodeAndType(eq("NOPE"), eq(LocationType.WARD)))
                .thenReturn(Optional.empty());

        assertThat(service.wardIdByCode("NOPE")).isEmpty();
    }

    /** A null input is a deny-by-default empty and never hits the DB. */
    @Test
    void nullInput_isEmpty_withoutDbCall() {
        assertThat(service.wardIdByCode(null)).isEmpty();
        verifyNoInteractions(repo);
    }

    /** A blank input is a deny-by-default empty and never hits the DB. */
    @Test
    void blankInput_isEmpty_withoutDbCall() {
        assertThat(service.wardIdByCode("   ")).isEmpty();
        verifyNoInteractions(repo);
    }
}
