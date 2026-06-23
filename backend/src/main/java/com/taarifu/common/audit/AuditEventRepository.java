package com.taarifu.common.audit;

import com.taarifu.common.audit.domain.model.AuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for the append-only {@link AuditEvent} store (AUTH-DESIGN §11, L-1).
 *
 * <p>Responsibility: <b>insert and read only</b>. There is intentionally no update/delete surface — the
 * table is append-only and the DB grant (added by the database engineer) revokes {@code UPDATE}/
 * {@code DELETE} so even a code mistake cannot mutate history. Reads support security review / the
 * tamper-evidence chain head lookup.</p>
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * @param subjectPublicId the subject account/entity.
     * @return that subject's events, newest first (security review).
     */
    List<AuditEvent> findBySubjectPublicIdOrderByOccurredAtDesc(UUID subjectPublicId);

    /**
     * @param eventType the type to filter by.
     * @param pageable  paging (typically size 1 to read the chain head).
     * @return matching events, newest first.
     */
    List<AuditEvent> findByEventTypeOrderByOccurredAtDesc(AuditEventType eventType, Pageable pageable);
}
