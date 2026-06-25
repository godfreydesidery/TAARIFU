import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import {
  AcknowledgeDsrRequest,
  CompleteDsrRequest,
  DsrRequest,
  RejectDsrRequest,
} from './privacy.models';

/**
 * Data access for the DSR (data-subject-request) admin console (UC-A17 / UC-S09; PRD §15, §25.1, §18).
 *
 * <p>Responsibility: the feature's typed gateway over the privacy/admin DSR endpoints, delegating
 * envelope/HTTP concerns to {@link ApiClient} (DRY, CLAUDE.md §8). It lists requests with server-side
 * filtering by type/status and drives the operator lifecycle actions (acknowledge → complete, or reject).
 * Authorization is enforced SERVER-side — these endpoints are ADMIN/ROOT-gated (PRD App. R "Trigger
 * data-export/erasure job" = ✅ Admin/Root), regardless of client routing.</p>
 *
 * <p>The list method is left to throw on error so the list page can show its own retry/error state (an
 * admin who can't see DSRs needs to KNOW, not silently see an empty queue); the screen still degrades a
 * missing endpoint to a friendly empty/error state. PII discipline: payloads are masked + id-only (§18).</p>
 */
@Injectable({ providedIn: 'root' })
export class PrivacyService {
  private readonly api = inject(ApiClient);

  /**
   * Lists data-subject requests, filtered and paged. `GET /privacy/admin/dsr`.
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
    return this.api.getPage<DsrRequest>('/privacy/admin/dsr', params);
  }

  /**
   * Fetches one DSR's detail. `GET /privacy/admin/dsr/{id}`.
   * @param id the request's public id.
   * @returns the {@link DsrRequest}.
   */
  get(id: string): Observable<DsrRequest> {
    return this.api.get<DsrRequest>(`/privacy/admin/dsr/${id}`);
  }

  /**
   * Acknowledges a request (starts the §25.1 ≤72h ack / ≤30d completion SLA clock).
   * `POST /privacy/admin/dsr/{id}/acknowledge`.
   * @param id the request's public id.
   * @param body optional internal note.
   * @returns the updated {@link DsrRequest}.
   */
  acknowledge(id: string, body: AcknowledgeDsrRequest = {}): Observable<DsrRequest> {
    return this.api.post<DsrRequest, AcknowledgeDsrRequest>(`/privacy/admin/dsr/${id}/acknowledge`, body);
  }

  /**
   * Marks a request complete — export delivered or anonymisation done (the job itself runs server-side,
   * UC-S09). `POST /privacy/admin/dsr/{id}/complete`.
   * @param id the request's public id.
   * @param body optional internal note.
   * @returns the updated {@link DsrRequest}.
   */
  complete(id: string, body: CompleteDsrRequest = {}): Observable<DsrRequest> {
    return this.api.post<DsrRequest, CompleteDsrRequest>(`/privacy/admin/dsr/${id}/complete`, body);
  }

  /**
   * Rejects a request with a required machine reason. `POST /privacy/admin/dsr/{id}/reject`.
   * @param id the request's public id.
   * @param body the required reason code (never PII).
   * @returns the updated {@link DsrRequest}.
   */
  reject(id: string, body: RejectDsrRequest): Observable<DsrRequest> {
    return this.api.post<DsrRequest, RejectDsrRequest>(`/privacy/admin/dsr/${id}/reject`, body);
  }
}
