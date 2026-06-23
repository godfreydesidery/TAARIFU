import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import {
  AssignCaseRequest,
  CaseEvent,
  EscalateCaseRequest,
  PublicReport,
  Report,
  ResolveCaseRequest,
} from './reporting.models';

/**
 * Data access for the official report queue + case management (PRD Epic M3, §12.1; UC-D05/D11-13, D21).
 *
 * <p>Responsibility: the feature's typed gateway over the reporting + responders report surfaces. The
 * console queue list reads the PII-free paged `GET /public/reports` (the only paged report-list endpoint
 * the backend exposes today — there is no admin "list all reports" endpoint; see CENTRAL NEEDS). Case
 * detail/timeline read the owner-grade `GET /reports/{id}` + `/timeline`. The status actions (assign,
 * start, resolve, escalate) hit the responder lifecycle endpoints under `/responders/admin/reports/...`,
 * which drive reporting's §12.1 state machine; authorization is enforced SERVER-side
 * (ADMIN/MODERATOR/RESPONDER_*). Envelope/error handling is delegated to {@link ApiClient} (DRY,
 * CLAUDE.md §8).</p>
 */
@Injectable({ providedIn: 'root' })
export class ReportingService {
  private readonly api = inject(ApiClient);

  /**
   * Lists reports for the console queue, paged. `GET /public/reports`.
   *
   * <p>Server-side filtering by `wardId` is supported by the backend; status/category/priority/SLA
   * filtering is applied client-side over the page until an admin queue endpoint with those filters
   * exists (CENTRAL NEEDS).</p>
   *
   * @param params optional `wardId`, plus `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link PublicReport}.
   */
  listQueue(params: {
    wardId?: string;
    page?: number;
    size?: number;
    sort?: string;
  }): Observable<Page<PublicReport>> {
    return this.api.getPage<PublicReport>('/public/reports', params);
  }

  /**
   * Fetches one report's public case view. `GET /public/reports/{id}`.
   * @param id the report's public id.
   * @returns the {@link PublicReport}.
   */
  getPublic(id: string): Observable<PublicReport> {
    return this.api.get<PublicReport>(`/public/reports/${id}`);
  }

  /**
   * Fetches a report's public timeline, paged. `GET /public/reports/{id}/timeline`.
   * @param id the report's public id.
   * @param params optional `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link CaseEvent}.
   */
  getTimeline(id: string, params: { page?: number; size?: number; sort?: string }): Observable<Page<CaseEvent>> {
    return this.api.getPage<CaseEvent>(`/public/reports/${id}/timeline`, params);
  }

  /**
   * Assigns a responder and moves the case to ASSIGNED. `POST /responders/admin/reports/{id}/assign`.
   * @param id the report's public id.
   * @param body the responder to assign.
   * @returns the updated {@link Report}.
   */
  assign(id: string, body: AssignCaseRequest): Observable<Report> {
    return this.api.post<Report, AssignCaseRequest>(`/responders/admin/reports/${id}/assign`, body);
  }

  /**
   * Starts work on an assigned case (→ IN_PROGRESS). `POST /responders/admin/reports/{id}/start`.
   * @param id the report's public id.
   * @returns the updated {@link Report}.
   */
  start(id: string): Observable<Report> {
    return this.api.post<Report, Record<string, never>>(`/responders/admin/reports/${id}/start`, {});
  }

  /**
   * Resolves a case with the required note (→ RESOLVED). `POST /responders/admin/reports/{id}/resolve`.
   * @param id the report's public id.
   * @param body the required resolution note.
   * @returns the updated {@link Report}.
   */
  resolve(id: string, body: ResolveCaseRequest): Observable<Report> {
    return this.api.post<Report, ResolveCaseRequest>(`/responders/admin/reports/${id}/resolve`, body);
  }

  /**
   * Escalates a case to a supervisor (→ ESCALATED; stays active). `POST .../reports/{id}/escalate`.
   * @param id the report's public id.
   * @param body the optional escalation reason.
   * @returns the updated {@link Report}.
   */
  escalate(id: string, body: EscalateCaseRequest): Observable<Report> {
    return this.api.post<Report, EscalateCaseRequest>(`/responders/admin/reports/${id}/escalate`, body);
  }
}
