package com.taarifu.ussd.domain.repository;

import com.taarifu.ussd.domain.model.UssdSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence port for {@link UssdSession} ephemeral dialogue state (ARCHITECTURE §3.3).
 *
 * <p>Responsibility: load the live session for an inbound keypress by its {@code (msisdn, sessionId)} key,
 * and sweep expired rows so the table stays small (EI-4 ephemeral state). Soft-deleted rows are excluded by
 * the entity's {@code @SQLRestriction}.</p>
 */
public interface UssdSessionRepository extends JpaRepository<UssdSession, Long> {

    /**
     * Loads the live session for a dialogue key.
     *
     * @param msisdn    the caller's E.164 phone.
     * @param sessionId the aggregator session id.
     * @return the matching live session, or empty if none (a fresh dialogue).
     */
    Optional<UssdSession> findByMsisdnAndSessionId(String msisdn, String sessionId);

    /**
     * Hard-deletes expired session rows (TTL sweep) — ephemeral state should not accumulate, and an
     * expired row carries a no-longer-needed MSISDN (PDPA data-minimisation).
     *
     * @param now the cutoff instant; rows with {@code expiresAt < now} are removed.
     * @return the number of rows deleted.
     */
    @Modifying
    @Query("DELETE FROM UssdSession s WHERE s.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
