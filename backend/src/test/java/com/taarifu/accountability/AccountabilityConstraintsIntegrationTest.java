package com.taarifu.accountability;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.accountability.domain.model.Attendance;
import com.taarifu.accountability.domain.model.Promise;
import com.taarifu.accountability.domain.model.PromiseStatusEntry;
import com.taarifu.accountability.domain.model.Rating;
import com.taarifu.accountability.domain.model.RatingReply;
import com.taarifu.accountability.domain.model.enums.PromiseStatus;
import com.taarifu.accountability.domain.model.enums.RatingSubjectType;
import com.taarifu.accountability.domain.repository.AttendanceRepository;
import com.taarifu.accountability.domain.repository.RatingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers tests for the accountability integrity constraints (PRD §10 Epic M6; §23 fence;
 * D16/D18).
 *
 * <p>Responsibility: proves the database actually enforces the civic-integrity invariants the
 * accountability data depends on — the <b>one-per-(rater, subject, period)</b> rating unique
 * (one person, one rating; D16), the <b>score 1..5</b> domain CHECK, and the <b>one row per
 * (representative, session)</b> attendance unique. These live in Postgres indexes/constraints, not in
 * Java, so they are integration tests against a real PostGIS Testcontainer (ADR-0009). The aggregate
 * queries are exercised too, confirming the score is derived from rows (never a stored, inflatable
 * field — the fence in arithmetic form).</p>
 *
 * <p>WHY this is the test that fails if the guard is removed: drop {@code ux_rating_one_per_...} and the
 * duplicate-rating assertion goes green when it must be red. It is the regression that protects "wealth
 * cannot buy democratic weight" at the data layer (§23).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountabilityConstraintsIntegrationTest extends AbstractPostgisIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private RatingRepository ratingRepository;
    @Autowired
    private AttendanceRepository attendanceRepository;

    @Test
    @Transactional
    void duplicateRatingForSamePeriod_isRejectedByUniqueIndex() {
        UUID subject = UUID.randomUUID();
        UUID rater = UUID.randomUUID();
        em.persist(Rating.create(RatingSubjectType.REPRESENTATIVE, subject, rater, 4, "first", "2026-Q2"));
        em.flush();

        // A second rating by the SAME rater for the SAME subject + period must violate the unique (D16).
        assertThatThrownBy(() -> {
            em.persist(Rating.create(RatingSubjectType.REPRESENTATIVE, subject, rater, 5, "second", "2026-Q2"));
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @Transactional
    void sameRaterDifferentPeriod_isAccepted() {
        UUID subject = UUID.randomUUID();
        UUID rater = UUID.randomUUID();
        em.persist(Rating.create(RatingSubjectType.REPRESENTATIVE, subject, rater, 4, "q1", "2026-Q1"));
        em.persist(Rating.create(RatingSubjectType.REPRESENTATIVE, subject, rater, 2, "q2", "2026-Q2"));
        em.flush();

        var aggregate = ratingRepository.aggregate(RatingSubjectType.REPRESENTATIVE, subject);
        // Aggregate is computed from the two rows: average (4+2)/2 = 3.0 (derive, never store — fence).
        assertThat(aggregate.getCount()).isEqualTo(2L);
        assertThat(aggregate.getAverage()).isEqualTo(3.0);
    }

    @Test
    @Transactional
    void scoreOutOfRange_isRejectedByCheckConstraint() {
        UUID subject = UUID.randomUUID();
        UUID rater = UUID.randomUUID();
        // score 6 is outside 1..5 → the ck_rating_score CHECK must reject it (bounds the aggregate).
        assertThatThrownBy(() -> {
            em.persist(Rating.create(RatingSubjectType.REPRESENTATIVE, subject, rater, 6, "too high", "2026-Q2"));
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @Transactional
    void duplicateAttendanceForSameSession_isRejected() {
        UUID rep = UUID.randomUUID();
        em.persist(Attendance.create(rep, "2026-06-18", true));
        em.flush();

        assertThatThrownBy(() -> {
            em.persist(Attendance.create(rep, "2026-06-18", false));
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @Transactional
    void attendanceAggregate_countsPresentAndTotal() {
        UUID rep = UUID.randomUUID();
        em.persist(Attendance.create(rep, "s1", true));
        em.persist(Attendance.create(rep, "s2", true));
        em.persist(Attendance.create(rep, "s3", false));
        em.flush();
        em.clear();

        var aggregate = attendanceRepository.aggregateByRepresentativeId(rep);
        assertThat(aggregate.getTotal()).isEqualTo(3L);
        assertThat(aggregate.getPresent()).isEqualTo(2L);
    }

    // ---------------------------------------------------------------------------------------------
    // Right-of-reply: the ONE-REPLY-PER-RATING fairness cap is enforced by the DB unique (US-6.2). Drop
    // ux_rating_reply_one_per_rating and the duplicate assertion goes green when it must be red — this is
    // the regression that protects "a reply, not a thread".
    // ---------------------------------------------------------------------------------------------

    @Test
    @Transactional
    void secondReplyForSameRating_isRejectedByUniqueIndex() {
        UUID representativeId = UUID.randomUUID();
        UUID rater = UUID.randomUUID();
        Rating rating = Rating.create(RatingSubjectType.REPRESENTATIVE, representativeId, rater, 3, "ok", "2026-Q2");
        em.persist(rating);
        em.persist(RatingReply.create(rating, representativeId, UUID.randomUUID(), false, "first reply"));
        em.flush();

        // A second reply to the SAME rating must violate the one-per-rating unique (the fairness cap).
        assertThatThrownBy(() -> {
            em.persist(RatingReply.create(rating, representativeId, UUID.randomUUID(), true, "second reply"));
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    // ---------------------------------------------------------------------------------------------
    // Promise status timeline: same-module FK to promise(id) is enforced, and entries accumulate per promise.
    // ---------------------------------------------------------------------------------------------

    @Test
    @Transactional
    void promiseTimelineEntries_persistAndLinkToPromise() {
        UUID representativeId = UUID.randomUUID();
        Promise promise = Promise.create(representativeId, "Build a ward dispensary",
                LocalDate.of(2026, 2, 1), PromiseStatus.MADE, null, null);
        em.persist(promise);
        em.persist(PromiseStatusEntry.record(promise, PromiseStatus.MADE, null, null));
        em.persist(PromiseStatusEntry.record(promise, PromiseStatus.IN_PROGRESS, "s3://e/p1.pdf", "started"));
        em.persist(PromiseStatusEntry.record(promise, PromiseStatus.KEPT, "s3://e/p2.pdf", "opened"));
        em.flush();
        em.clear();

        Long count = em.createQuery(
                        "select count(e) from PromiseStatusEntry e where e.promise.publicId = :pid", Long.class)
                .setParameter("pid", promise.getPublicId())
                .getSingleResult();
        // The append-only timeline accumulates one row per transition (MADE → IN_PROGRESS → KEPT).
        assertThat(count).isEqualTo(3L);
    }
}
