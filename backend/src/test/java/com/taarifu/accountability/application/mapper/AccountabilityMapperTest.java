package com.taarifu.accountability.application.mapper;

import com.taarifu.accountability.api.dto.AttendanceSummaryDto;
import com.taarifu.accountability.api.dto.RatingSummaryDto;
import com.taarifu.accountability.domain.model.enums.RatingSubjectType;
import com.taarifu.accountability.domain.repository.AttendanceRepository;
import com.taarifu.accountability.domain.repository.RatingRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AccountabilityMapper} aggregate mapping (PRD §10 US-6.1/6.2).
 *
 * <p>Responsibility: proves the computed aggregates handle the empty/edge cases correctly — no
 * division-by-zero on an unrated/no-sessions subject, and a {@code null} average/rate rather than a
 * fabricated zero. The repositories aren't needed for the pure aggregate mappers, so the mapper is
 * constructed with {@code null} ports (KISS).</p>
 */
class AccountabilityMapperTest {

    private final AccountabilityMapper mapper = new AccountabilityMapper();
    private final UUID rep = UUID.randomUUID();

    @Test
    void attendanceSummary_noSessions_yieldsNullRate_notDivByZero() {
        AttendanceSummaryDto dto = mapper.toAttendanceSummaryDto(rep, aggregate(0, 0));
        assertThat(dto.present()).isZero();
        assertThat(dto.total()).isZero();
        assertThat(dto.rate()).isNull();
    }

    @Test
    void attendanceSummary_computesRate() {
        AttendanceSummaryDto dto = mapper.toAttendanceSummaryDto(rep, aggregate(3, 4));
        assertThat(dto.rate()).isEqualTo(0.75);
    }

    @Test
    void attendanceSummary_nullAggregate_isSafe() {
        AttendanceSummaryDto dto = mapper.toAttendanceSummaryDto(rep, null);
        assertThat(dto.total()).isZero();
        assertThat(dto.rate()).isNull();
    }

    @Test
    void ratingSummary_unrated_yieldsNullAverage() {
        RatingSummaryDto dto = mapper.toRatingSummaryDto(
                RatingSubjectType.REPRESENTATIVE, rep, ratingAggregate(0, null));
        assertThat(dto.count()).isZero();
        assertThat(dto.average()).isNull();
    }

    @Test
    void ratingSummary_carriesComputedAverage() {
        RatingSummaryDto dto = mapper.toRatingSummaryDto(
                RatingSubjectType.REPRESENTATIVE, rep, ratingAggregate(2, 4.5));
        assertThat(dto.count()).isEqualTo(2);
        assertThat(dto.average()).isEqualTo(4.5);
    }

    private static AttendanceRepository.AttendanceAggregate aggregate(long present, long total) {
        return new AttendanceRepository.AttendanceAggregate() {
            @Override
            public long getPresent() {
                return present;
            }

            @Override
            public long getTotal() {
                return total;
            }
        };
    }

    private static RatingRepository.RatingAggregate ratingAggregate(long count, Double average) {
        return new RatingRepository.RatingAggregate() {
            @Override
            public long getCount() {
                return count;
            }

            @Override
            public Double getAverage() {
                return average;
            }
        };
    }
}
