package com.taarifu.moderation.api.dto;

import com.taarifu.moderation.domain.model.Flag;
import com.taarifu.moderation.domain.model.enums.FlagReason;
import com.taarifu.moderation.domain.model.enums.FlagStatus;
import com.taarifu.moderation.api.FlagSubjectType;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for a {@link Flag} returned to its flagger (PRD §18, US-12.1 "feedback").
 *
 * <p>Exposes only the public id and non-sensitive fields; the internal numeric PK never leaves the
 * module (ARCHITECTURE.md §4.2). The flagger's own profile id is echoed back to them; this DTO is only
 * ever returned to the flagger or a moderator, never published.</p>
 *
 * @param id          the flag's public id.
 * @param subjectType the kind of content flagged.
 * @param subjectId   the flagged content's public id.
 * @param reason      why it was flagged.
 * @param status      the flag's lifecycle state.
 * @param createdAt   when the flag was submitted (UTC).
 */
public record FlagDto(
        UUID id,
        FlagSubjectType subjectType,
        UUID subjectId,
        FlagReason reason,
        FlagStatus status,
        Instant createdAt
) {

    /**
     * Maps an entity to its read model.
     *
     * @param flag the persisted flag.
     * @return the DTO.
     */
    public static FlagDto from(Flag flag) {
        return new FlagDto(flag.getPublicId(), flag.getSubjectType(), flag.getSubjectId(),
                flag.getReason(), flag.getStatus(), flag.getCreatedAt());
    }
}
