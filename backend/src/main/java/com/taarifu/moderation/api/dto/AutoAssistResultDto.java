package com.taarifu.moderation.api.dto;

import com.taarifu.moderation.domain.model.enums.ContentSignal;

/**
 * The outcome of an auto-assist triage pass (PRD §12 US-12.3, UC-H05; ADR-0018).
 *
 * <p>A decision record returned to the caller of
 * {@link com.taarifu.moderation.application.service.AutoAssistService#triage}: whether the content was
 * <b>held for human review</b> and, if so, the top content-safety signal and confidence that triggered it. It
 * deliberately carries <b>no content body, no author identity, and no free text</b> — only the label/confidence
 * decision (data minimisation, §18). It is never a takedown: a hold only surfaces the content to a human
 * moderator (assist only, D-Q8, R21).</p>
 *
 * @param held       whether the content was held for human review (a queue item was opened/escalated). When
 *                   {@code false}, nothing was held — the content flows on to the human pipeline + flagging.
 * @param topSignal  the top risk signal that caused the hold, or {@code null} when nothing was held.
 * @param confidence the scorer's confidence in {@link #topSignal} ({@code [0,1]}), or {@code null} when not held.
 */
public record AutoAssistResultDto(boolean held, ContentSignal topSignal, Double confidence) {
}
