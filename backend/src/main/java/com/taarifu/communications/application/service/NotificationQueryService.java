package com.taarifu.communications.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.communications.domain.model.Notification;
import com.taarifu.communications.domain.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Reads a citizen's notifications and marks them read (PRD §13, UC-G09, M5).
 *
 * <p>Responsibility: the use-case orchestration for the in-app notification list and the read receipt.
 * It owns the transaction boundary and enforces that a citizen reads/marks only <b>their own</b>
 * notifications — a notification carries PII-adjacent context and must never be exposed cross-user
 * (PRD §18). It returns entities to the controller's mapper (kept in-module).</p>
 */
@Service
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;
    private final ClockPort clock;

    /**
     * @param notificationRepository notification persistence.
     * @param clock                  injectable "now" for the read timestamp.
     */
    public NotificationQueryService(NotificationRepository notificationRepository, ClockPort clock) {
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    /**
     * Lists the caller's notifications, newest first, paged.
     *
     * @param callerProfileId the authenticated caller's profile public id.
     * @param pageable        paging/sorting.
     * @return a page of the caller's notifications.
     */
    @Transactional(readOnly = true)
    public Page<Notification> listMine(UUID callerProfileId, Pageable pageable) {
        return notificationRepository.findByRecipientProfileId(callerProfileId, pageable);
    }

    /**
     * Marks one of the caller's notifications read.
     *
     * @param callerProfileId      the authenticated caller's profile public id.
     * @param notificationPublicId the notification's public id.
     * @return the updated notification.
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if no such notification,
     *                      {@link ErrorCode#FORBIDDEN} if it is not the caller's own.
     */
    @Transactional
    public Notification markRead(UUID callerProfileId, UUID notificationPublicId) {
        Notification n = notificationRepository.findByPublicId(notificationPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        // Ownership: a citizen may only read their own notifications (PRD §18 — no cross-user exposure).
        if (!n.getRecipientProfileId().equals(callerProfileId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        n.markRead(clock.now());
        return notificationRepository.save(n);
    }
}
