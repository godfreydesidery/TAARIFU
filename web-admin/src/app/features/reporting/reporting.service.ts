import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import {
  AdminReportDetail,
  AdminReportSummary,
  AdminStats,
  AssignCaseRequest,
  EscalateCaseRequest,
  Report,
  ResolveCaseRequest,
} from './reporting.models';

/**
 * Data access for the official report queue + case management (M14, Epic M3, §12.1; UC-D05/D11-13, D21).
 *
 * <p>Responsibility: the feature's typed gateway over the admin reporting surfaces. The console queue reads
 * the owner-grade paged `GET /admin/reports` with SERVER-side filtering by status/category/area/SLA — no
 * client-side filtering over a partial page. Case detail reads `GET /admin/reports/{id}`, which carries the
 * full internal+public timeline (US-3.4) inline, so the detail screen needs no separate timeline call. The
 * dashboard reads aggregate counts from `GET /admin/stats`. The status actions (assign/start/resolve/
 * escalate) hit the responder lifecycle endpoints under `/responders/admin/reports/...`, which drive
 * reporting's §12.1 state machine; authorization is enforced SERVER-side (ADMIN/MODERATOR/RESPONDER_*).
 * Both admin reads are PII-minimised — no reporter identity, no precise geo-point (PRD §18, PDPA, D-Q1).
 * Envelope/error handling is delegated to {@link ApiClient} (DRY, CLAUDE.md §8).</p>
 */
@Injectable({ providedIn: 'root' })
export class ReportingService {
  private readonly api = inject(ApiClient);

  /**
   * Lists the owner-grade report queue, filtered and paged. `GET /admin/reports`.
   *
   * <p>All filters are applied SERVER-side; an unknown `status` yields an empty page (never an error). The
   * sort is fixed server-side (newest-filed first).</p>
   *
   * @param params optional `status`/`categoryId`/`areaId`/`slaBreached` filters plus `page`/`size`.
   * @returns a {@link Page} of {@link AdminReportSummary}.
   */
  listQueue(params: {
    status?: string;
    categoryId?: string;
    areaId?: string;
    slaBreached?: boolean;
    page?: number;
    size?: number;
  }): Observable<Page<AdminReportSummary>> {
    return this.api.getPage<AdminReportSummary>('/admin/reports', params);
  }

  /**
   * Fetches one case's staff detail incl. the full internal+public timeline. `GET /admin/reports/{id}`.
   * @param id the report's public id.
   * @returns the {@link AdminReportDetail}.
   */
  getAdminDetail(id: string): Observable<AdminReportDetail> {
    return this.api.get<AdminReportDetail>(`/admin/reports/${id}`);
  }

  /**
   * Fetches the dashboard overview aggregate counts. `GET /admin/stats`.
   * @returns the {@link AdminStats} snapshot.
   */
  getStats(): Observable<AdminStats> {
    return this.api.get<AdminStats>('/admin/stats');
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
