package com.taarifu.communications.domain.repository;

import com.taarifu.communications.domain.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Notification} dispatch records (ARCHITECTURE §3.3).
 *
 * <p>Responsibility: the persistence port for notifications. The recipient's notification list/unread
 * count is the hot read; the idempotency lookup guarantees no double-send under at-least-once delivery
 * (DI4). Soft-deleted rows are excluded automatically by the entity's {@code @SQLRestriction}.</p>
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * @param publicId the notification's public id.
     * @return the matching notification, or empty if none/soft-deleted.
     */
    Optional<Notification> findByPublicId(UUID publicId);

    /**
     * Finds an existing dispatch row by its idempotency key — the de-dup gate before queuing a send.
     *
     * @param idempotencyKey the stable de-dup key.
     * @return the existing notification, or empty.
     */
    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    /**
     * Whether a dispatch row already exists for the key (cheap idempotency probe).
     *
     * @param idempotencyKey the stable de-dup key.
     * @return {@code true} if a row exists.
     */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * Lists a recipient's notifications, newest first, paged — the in-app notification list.
     *
     * @param recipientProfileId the recipient profile's public id.
     * @param pageable           paging/sorting (the service orders by creation desc).
     * @return a page of the recipient's notifications.
     */
    Page<Notification> findByRecipientProfileId(UUID recipientProfileId, Pageable pageable);
}
