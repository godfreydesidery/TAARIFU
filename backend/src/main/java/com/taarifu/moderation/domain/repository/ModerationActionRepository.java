package com.taarifu.moderation.domain.repository;

import com.taarifu.moderation.domain.model.ModerationAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for the append-only {@link ModerationAction} log (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: INSERT + SELECT only — actions are never updated/deleted (§18, §25.8; the V41 grant
 * enforces this at the database). Supports fetching an action by public id (to file/decide an appeal
 * against it) and listing a queue item's action history.</p>
 */
public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {

    /**
     * @param publicId the action's public id.
     * @return the action, or empty.
     */
    Optional<ModerationAction> findByPublicId(UUID publicId);

    /**
     * @param itemId the owning {@link ModerationAction#getItem() item}'s internal id.
     * @return that item's actions, newest decisions appended last.
     */
    List<ModerationAction> findByItemIdOrderByTakenAtAsc(Long itemId);
}
