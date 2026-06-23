package com.taarifu.engagement;

import com.taarifu.AbstractPostgisIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers tests for the engagement hard integrity constraints (PRD §23.5 fence, §12.2 M8/M9/M10).
 *
 * <p>Responsibility: proves the database actually enforces the civic-integrity invariants the binding
 * actions depend on — <b>one signature per (petition, signer)</b>, <b>one response per (survey,
 * responder)</b>, and <b>one answer per question</b>. These live in Postgres unique constraints, not in
 * Java, so they are integration-tested with native inserts (the most faithful way to assert the index
 * fires — ADR-0009; H2 cannot reproduce this).</p>
 *
 * <p>The {@code @SpringBootTest} context load is itself meaningful: it boots with {@code ddl-auto=validate}
 * against the Flyway-migrated schema (V36–V38), so a mismatch between the engagement entities and the
 * migrations fails this test fast.</p>
 *
 * <p>NOTE: Docker is unavailable in the local sandbox, so these run in CI only — the module's unit tests
 * (no DB) are the local green gate (build instructions). They are written now so the integrity rules are
 * proven against a real PostGIS instance in the pipeline.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class EngagementConstraintsIntegrationTest extends AbstractPostgisIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    @Test
    @Transactional
    void duplicateSignature_isRejectedByUniqueConstraint() {
        long petition = insertPetition("ACTIVE", 10);
        UUID signer = UUID.randomUUID();
        insertSignature(petition, signer);

        // A second signature by the same signer on the same petition must violate uq_petition_signature_once.
        assertThatThrownBy(() -> {
            insertSignature(petition, signer);
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @Transactional
    void distinctSigners_areAccepted() {
        long petition = insertPetition("ACTIVE", 10);
        insertSignature(petition, UUID.randomUUID());
        insertSignature(petition, UUID.randomUUID());
        em.flush();

        Number count = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM petition_signature WHERE petition_id = :p")
                .setParameter("p", petition).getSingleResult();
        assertThat(count.longValue()).isEqualTo(2L);
    }

    @Test
    @Transactional
    void duplicateSurveyResponse_isRejectedByUniqueConstraint() {
        long survey = insertSurvey("POLL", true, "OPEN");
        UUID responder = UUID.randomUUID();
        insertResponse(survey, responder);

        // A second response by the same responder must violate uq_survey_response_once (one-per-person).
        assertThatThrownBy(() -> {
            insertResponse(survey, responder);
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @Transactional
    void nonPollBindingSurvey_isRejectedByCheckConstraint() {
        // ck_survey_binding_only_poll: a SURVEY can never be binding (fence not reachable by mislabelling).
        assertThatThrownBy(() -> {
            insertSurvey("SURVEY", true, "DRAFT");
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @Transactional
    void duplicateAnswer_isRejectedByUniqueConstraint() {
        long question = insertQuestion();
        insertAnswer(question);

        // A second answer for the same question must violate uq_qa_answer_question (one answer per question).
        assertThatThrownBy(() -> {
            insertAnswer(question);
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    // ----------------------------------------------------------------------------- helpers (native inserts)

    private long insertPetition(String status, int goal) {
        UUID pid = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO petition (public_id, version, created_at, deleted, title, body, target_type,
                                      target_id, signature_goal, signature_count, status)
                VALUES (:pid, 0, :now, false, 'T', 'B', 'OFFICE', :target, :goal, 0, :status)
                """)
                .setParameter("pid", pid)
                .setParameter("now", Instant.now())
                .setParameter("target", UUID.randomUUID())
                .setParameter("goal", goal)
                .setParameter("status", status)
                .executeUpdate();
        return id("petition", pid);
    }

    private void insertSignature(long petitionId, UUID signer) {
        em.createNativeQuery("""
                INSERT INTO petition_signature (public_id, version, created_at, deleted, petition_id,
                                                signer_profile_id, is_public)
                VALUES (:pid, 0, :now, false, :petition, :signer, false)
                """)
                .setParameter("pid", UUID.randomUUID())
                .setParameter("now", Instant.now())
                .setParameter("petition", petitionId)
                .setParameter("signer", signer)
                .executeUpdate();
    }

    private long insertSurvey(String type, boolean binding, String status) {
        UUID pid = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO survey (public_id, version, created_at, deleted, title, type, binding,
                                    anonymous, status)
                VALUES (:pid, 0, :now, false, 'T', :type, :binding, false, :status)
                """)
                .setParameter("pid", pid)
                .setParameter("now", Instant.now())
                .setParameter("type", type)
                .setParameter("binding", binding)
                .setParameter("status", status)
                .executeUpdate();
        return id("survey", pid);
    }

    private void insertResponse(long surveyId, UUID responder) {
        em.createNativeQuery("""
                INSERT INTO survey_response (public_id, version, created_at, deleted, survey_id,
                                             responder_profile_id, answers)
                VALUES (:pid, 0, :now, false, :survey, :responder, '[]')
                """)
                .setParameter("pid", UUID.randomUUID())
                .setParameter("now", Instant.now())
                .setParameter("survey", surveyId)
                .setParameter("responder", responder)
                .executeUpdate();
    }

    private long insertQuestion() {
        UUID pid = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO qa_question (public_id, version, created_at, deleted, asker_profile_id,
                                         target_rep_id, body, upvotes, status)
                VALUES (:pid, 0, :now, false, :asker, :rep, 'Q?', 0, 'OPEN')
                """)
                .setParameter("pid", pid)
                .setParameter("now", Instant.now())
                .setParameter("asker", UUID.randomUUID())
                .setParameter("rep", UUID.randomUUID())
                .executeUpdate();
        return id("qa_question", pid);
    }

    private void insertAnswer(long questionId) {
        em.createNativeQuery("""
                INSERT INTO qa_answer (public_id, version, created_at, deleted, question_id,
                                       answered_by_rep_id, body)
                VALUES (:pid, 0, :now, false, :question, :rep, 'A.')
                """)
                .setParameter("pid", UUID.randomUUID())
                .setParameter("now", Instant.now())
                .setParameter("question", questionId)
                .setParameter("rep", UUID.randomUUID())
                .executeUpdate();
    }

    private long id(String table, UUID publicId) {
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM " + table + " WHERE public_id = :pid")
                .setParameter("pid", publicId).getSingleResult()).longValue();
    }
}
