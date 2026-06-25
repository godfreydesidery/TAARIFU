import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import { AcknowledgeDsrRequest, CompleteDsrRequest, DsrRequest } from './privacy.models';

/**
 * Data access for the DSR (data-subject-request) admin console (UC-A17 / UC-S09; PRD §15, §25.1, §18).
 *
 * <p>Responsibility: the feature's typed gateway over the privacy DSR admin endpoints, delegating
 * envelope/HTTP concerns to {@link ApiClient} (DRY, CLAUDE.md §8). It lists requests with server-side
 * filtering by type/status and drives the operator lifecycle actions (acknowledge → complete, or place on
 * HOLD). Authorization is enforced SERVER-side — these endpoints are ADMIN/ROOT-gated (PRD App. R "Trigger
 * data-export/erasure job" = ✅ Admin/Root), regardless of client routing.</p>
 *
 * <p>WHY the paths are {@code /privacy/dsr/*} (and not {@code /privacy/admin/dsr/*}): the backend
 * {@code DataSubjectRequestController} is mapped at {@code /privacy/dsr}, with the ADMIN-only list +
 * lifecycle actions co-located under it (method-level {@code hasRole('ADMIN')}). There is intentionally NO
 * admin GET-by-id and NO "reject" action — the lifecycle is acknowledge → hold → complete (an E2E pass found
 * the old {@code /privacy/admin/dsr} + reject paths 404/absent; this is the reconciliation).</p>
 *
 * <p>The list method is left to throw on error so the list page can show its own retry/error state (an
 * admin who can't see DSRs needs to KNOW, not silently see an empty queue); the screen still degrades a
 * missing endpoint to a friendly empty/error state. PII discipline: payloads are masked + id-only (§18).</p>
 */
@Injectable({ providedIn: 'root' })
export class PrivacyService {
  private readonly api = inject(ApiClient);

  /**
   * Lists data-subject requests, filtered and paged. `GET /privacy/dsr` (ADMIN-only on the server).
   *
   * @param params optional `type`/`status` filters plus `page`/`size`. Filters apply SERVER-side.
   * @returns a {@link Page} of {@link DsrRequest}.
   */
  list(params: {
    type?: string;
    status?: string;
    page?: number;
    size?: number;
  }): Observable<Page<DsrRequest>> {
    return this.api.getPage<DsrRequest>('/privacy/dsr', params);
  }

  /**
   * Acknowledges a request (starts the §25.1 ≤72h ack / ≤30d completion SLA clock).
   * `POST /privacy/dsr/{id}/acknowledge`.
   *
   * <p>The backend action takes no request body (it acts on the path id + the current admin); an empty body
   * is sent only because {@link ApiClient.post} requires one.</p>
   *
   * @param id the request's public id.
   * @param body optional internal note (currently ignored server-side; kept for forward compatibility).
   * @returns the updated {@link DsrRequest}.
   */
  acknowledge(id: string, body: AcknowledgeDsrRequest = {}): Observable<DsrRequest> {
    return this.api.post<DsrRequest, AcknowledgeDsrRequest>(`/privacy/dsr/${id}/acknowledge`, body);
  }

  /**
   * Places a request under legal HOLD — suspends erasure until released (§25.1).
   * `POST /privacy/dsr/{id}/hold?reasonCode=…`.
   *
   * <p>WHY query param, not body: the backend {@code hold} endpoint takes {@code reasonCode} as a
   * {@code @RequestParam} (query string), so the code is appended to the path (URL-encoded) rather than sent
   * as a JSON body. The reason is a machine code (e.g. {@code UNDER_INVESTIGATION}); never PII.</p>
   *
   * @param id the request's public id.
   * @param reasonCode the machine hold reason; never PII.
   * @returns the updated {@link DsrRequest}.
   */
  hold(id: string, reasonCode: string): Observable<DsrRequest> {
    return this.api.post<DsrRequest, null>(
      `/privacy/dsr/${id}/hold?reasonCode=${encodeURIComponent(reasonCode)}`,
      null,
    );
  }

  /**
   * Marks a request complete — export delivered or anonymisation done (the job itself runs server-side,
   * UC-S09). `POST /privacy/dsr/{id}/complete`.
   *
   * <p>The backend action takes no request body; an empty body is sent only because {@link ApiClient.post}
   * requires one.</p>
   *
   * @param id the request's public id.
   * @param body optional internal note (currently ignored server-side; kept for forward compatibility).
   * @returns the updated {@link DsrRequest}.
   */
  complete(id: string, body: CompleteDsrRequest = {}): Observable<DsrRequest> {
    return this.api.post<DsrRequest, CompleteDsrRequest>(`/privacy/dsr/${id}/complete`, body);
  }
}
