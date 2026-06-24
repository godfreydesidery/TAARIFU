import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import {
  Appeal,
  AppealSummary,
  DecideAppealRequest,
  ModerationAction,
  ModerationItem,
  TakeActionRequest,
} from './moderation.models';

/**
 * Data access for the moderation queue + actions + appeals (PRD §18, §25.8; UC-H01/H02/H03).
 *
 * <p>Responsibility: the feature's typed gateway over `/moderation/*`. The queue list reads
 * `GET /moderation/items?status=...` (prioritised server-side by severity + SLA); take-action and
 * appeal-decision POST to their endpoints. Authorization is `hasRole('MODERATOR')` SERVER-side, and the
 * conflict-of-interest / appeal-independence guards (D16, §25.8) are enforced in the backend service —
 * an illegal self-action surfaces as a CONFLICT toast. Envelope/error handling is delegated to
 * {@link ApiClient} (DRY, CLAUDE.md §8).</p>
 */
@Injectable({ providedIn: 'root' })
export class ModerationService {
  private readonly api = inject(ApiClient);

  /**
   * Lists the queue for a status, prioritised by severity + SLA. `GET /moderation/items`.
   * @param params `status` (default PENDING) plus `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link ModerationItem}.
   */
  listQueue(params: { status?: string; page?: number; size?: number; sort?: string }): Observable<Page<ModerationItem>> {
    return this.api.getPage<ModerationItem>('/moderation/items', params);
  }

  /**
   * Takes a moderation action on a queue item. `POST /moderation/items/{id}/actions`.
   * @param itemId the queue item's public id.
   * @param body the validated action request.
   * @returns the recorded {@link ModerationAction}.
   */
  takeAction(itemId: string, body: TakeActionRequest): Observable<ModerationAction> {
    return this.api.post<ModerationAction, TakeActionRequest>(`/moderation/items/${itemId}/actions`, body);
  }

  /**
   * Lists the moderator appeals queue, paged. `GET /moderation/appeals`.
   *
   * <p>Method-secured `hasRole('MODERATOR')` server-side; viewing carries no conflict-of-interest (the
   * appeal-independence fence applies only at decide time). Optionally filtered by appeal `status` (e.g.
   * `OPEN`); default sort is newest-filed first.</p>
   *
   * @param params optional `status` filter plus `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link AppealSummary}.
   */
  listAppeals(params: {
    status?: string;
    page?: number;
    size?: number;
    sort?: string;
  }): Observable<Page<AppealSummary>> {
    return this.api.getPage<AppealSummary>('/moderation/appeals', params);
  }

  /**
   * Decides an open appeal. `POST /moderation/appeals/{id}/decision`.
   * @param appealId the appeal's public id.
   * @param body the validated decision request.
   * @returns the decided {@link Appeal}.
   */
  decideAppeal(appealId: string, body: DecideAppealRequest): Observable<Appeal> {
    return this.api.post<Appeal, DecideAppealRequest>(`/moderation/appeals/${appealId}/decision`, body);
  }
}
