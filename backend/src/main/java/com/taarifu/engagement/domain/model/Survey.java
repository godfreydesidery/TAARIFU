package com.taarifu.engagement.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.engagement.domain.model.enums.SurveyStatus;
import com.taarifu.engagement.domain.model.enums.SurveyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * A survey or poll targeted at an audience (PRD §9.1 Survey/Poll, §12.2 M8).
 *
 * <p>Responsibility: the survey/poll aggregate — its text, {@link #type} (SURVEY vs POLL), its
 * {@link #binding} flag, an {@link #audienceScope} descriptor (geo/role targeting), its open window
 * ({@link #startsAt}/{@link #endsAt}), an {@link #anonymous} setting, and its lifecycle {@link #status}.
 * Responses are a separate aggregate ({@link SurveyResponse}).</p>
 *
 * <p>WHY {@code questions}/{@code options}/{@code audienceScope} are stored as JSON text (not child
 * entity tables) in this Phase-2 scaffold: the question/option shapes are flexible (single/multi/scale/
 * text — US-8.1) and modelling them as normalised child tables is Phase-2 <i>product</i> work, not part
 * of this seam. KISS (CLAUDE.md §3): a {@code jsonb} blob locks the table shape now without
 * over-engineering the sub-structure, and a later migration can promote them to tables without changing
 * this module's public API. The blob is validated/parsed at the edge, never trusted raw.</p>
 *
 * <p><b>Integrity (D18, PRD §23.5):</b> a {@code POLL} flagged {@link #binding} is a democratic-weight
 * act — responding requires <b>T3 + one-per-person</b> (the response unique index), and its
 * authorisation path never reads a token balance. A non-binding {@code SURVEY} accepts T2 responses
 * (PRD §7.3). The gate is applied in the application service, not this entity.</p>
 *
 * <p>WHY {@link #creatorProfileId}/{@link #creatorOrgId} are UUIDs (not FKs into identity): module
 * boundary — the author (Authority/Representative/Org, US-8.1) is referenced by public id only.</p>
 */
@Entity
@Table(name = "survey", indexes = {
        @Index(name = "ix_survey_status", columnList = "status"),
        @Index(name = "ix_survey_type", columnList = "type"),
        @Index(name = "ix_survey_creator_profile", columnList = "creator_profile_id"),
        @Index(name = "ix_survey_creator_org", columnList = "creator_org_id")
})
@SQLRestriction("deleted = false")
public class Survey extends BaseEntity {

    /** Short title of the survey/poll. */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** Optional longer description. */
    @Column(name = "description", length = 4000)
    private String description;

    /** SURVEY (opinion) vs POLL (which may be binding). */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private SurveyType type;

    /**
     * Whether this is a <b>binding</b> poll (democratic weight). When {@code true}, responding requires
     * T3 + one-per-person and the authorisation path ignores token balance entirely (D18, PRD §23.5).
     * Always {@code false} for a non-binding survey.
     */
    @Column(name = "binding", nullable = false)
    private boolean binding = false;

    /**
     * Audience targeting descriptor as a JSON <i>string</i> — geo (area public ids) and/or role names
     * (US-8.1 "audience by geo/role"). Stored as a bounded {@code varchar} text blob in this scaffold
     * (see class Javadoc) — NOT a native {@code jsonb} column, to keep {@code ddl-auto=validate} simple
     * and dependency-free (no JSON type mapping); a later migration may promote it to {@code jsonb}.
     * {@code null} = open to all eligible tiers.
     */
    @Column(name = "audience_scope", length = 4000)
    private String audienceScope;

    /**
     * The questions definition as a JSON <i>string</i>: an array of {@code {prompt, kind, options[]}}
     * (US-8.1 single/multi/scale/text). Bounded {@code varchar} text blob in this Phase-2 scaffold (see
     * the {@link #audienceScope} note on the storage choice).
     */
    @Column(name = "questions", length = 16000)
    private String questions;

    /** When the survey opens (UTC); {@code null} until scheduled. */
    @Column(name = "starts_at")
    private Instant startsAt;

    /** When the survey closes (UTC); {@code null} for open-ended (closed manually). */
    @Column(name = "ends_at")
    private Instant endsAt;

    /**
     * Whether responses are anonymous (US-8.1 "anonymity setting"). When {@code true}, the stored
     * {@link SurveyResponse#getResponderProfileId()} is still kept for the one-per-person guarantee but is
     * never exposed in results (PDPA data-minimisation, PRD §18).
     */
    @Column(name = "anonymous", nullable = false)
    private boolean anonymous = false;

    /** Current lifecycle state (PRD §12.2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SurveyStatus status = SurveyStatus.DRAFT;

    /** Public id of the authoring person's identity {@code Profile}, or {@code null} for an org survey. */
    @Column(name = "creator_profile_id")
    private UUID creatorProfileId;

    /** Public id of the authoring organisation, or {@code null} for a person survey. */
    @Column(name = "creator_org_id")
    private UUID creatorOrgId;

    /** JPA requires a no-arg constructor; application code uses {@link #create}. */
    protected Survey() {
    }

    /**
     * Creates a new survey/poll in {@code DRAFT}.
     *
     * @param title            the title.
     * @param description      optional description, or {@code null}.
     * @param type             SURVEY vs POLL.
     * @param binding          whether this is a binding poll (T3 + one-per-person on response).
     * @param audienceScope    JSON audience descriptor, or {@code null} for open-to-all.
     * @param questions        JSON questions definition, or {@code null}.
     * @param startsAt         open instant, or {@code null}.
     * @param endsAt           close instant, or {@code null}.
     * @param anonymous        whether responses are anonymous.
     * @param creatorProfileId authoring person's profile public id, or {@code null} if org-authored.
     * @param creatorOrgId     authoring organisation public id, or {@code null} if person-authored.
     * @return the populated, transient survey (status {@code DRAFT}).
     */
    public static Survey create(String title, String description, SurveyType type, boolean binding,
                                String audienceScope, String questions,
                                Instant startsAt, Instant endsAt, boolean anonymous,
                                UUID creatorProfileId, UUID creatorOrgId) {
        Survey s = new Survey();
        s.title = title;
        s.description = description;
        s.type = type;
        // A non-poll can never be binding (defensive — also a DB CHECK).
        s.binding = type == SurveyType.POLL && binding;
        s.audienceScope = audienceScope;
        s.questions = questions;
        s.startsAt = startsAt;
        s.endsAt = endsAt;
        s.anonymous = anonymous;
        s.creatorProfileId = creatorProfileId;
        s.creatorOrgId = creatorOrgId;
        s.status = SurveyStatus.DRAFT;
        return s;
    }

    /** Opens the survey for responses (post-schedule). */
    public void open() {
        this.status = SurveyStatus.OPEN;
    }

    /** @return whether the survey is currently accepting responses. */
    public boolean isAcceptingResponses() {
        return this.status == SurveyStatus.OPEN;
    }

    /** @return whether the survey is publicly visible (anything past DRAFT). */
    public boolean isPubliclyVisible() {
        return this.status != SurveyStatus.DRAFT;
    }

    /** @return whether responding is a binding (T3 + one-per-person) act. */
    public boolean isBinding() {
        return binding;
    }

    /** @return the title. */
    public String getTitle() {
        return title;
    }

    /** @return the description, or {@code null}. */
    public String getDescription() {
        return description;
    }

    /** @return SURVEY vs POLL. */
    public SurveyType getType() {
        return type;
    }

    /** @return the JSON audience descriptor, or {@code null} for open-to-all. */
    public String getAudienceScope() {
        return audienceScope;
    }

    /** @return the JSON questions definition, or {@code null}. */
    public String getQuestions() {
        return questions;
    }

    /** @return the open instant, or {@code null}. */
    public Instant getStartsAt() {
        return startsAt;
    }

    /** @return the close instant, or {@code null}. */
    public Instant getEndsAt() {
        return endsAt;
    }

    /** @return whether responses are anonymous. */
    public boolean isAnonymous() {
        return anonymous;
    }

    /** @return the current lifecycle state. */
    public SurveyStatus getStatus() {
        return status;
    }

    /** @return the authoring person's profile public id, or {@code null}. */
    public UUID getCreatorProfileId() {
        return creatorProfileId;
    }

    /** @return the authoring organisation public id, or {@code null}. */
    public UUID getCreatorOrgId() {
        return creatorOrgId;
    }
}
