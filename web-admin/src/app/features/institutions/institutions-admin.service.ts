import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import { RepresentativeSummary } from './institutions.models';
import {
  Parliament,
  ParliamentRole,
  ParliamentRoleWrite,
  ParliamentWrite,
  RepresentativeWrite,
} from './institutions-admin.models';

/**
 * Admin data access for institutions reference data — parliaments, parliament roles, and the
 * representative create/link flow (PRD §9.1; UC-B12/B13, UC-C04/C08).
 *
 * <p>Responsibility: the feature's typed gateway over the public read endpoints (`/parliaments`,
 * `/parliaments/roles`, `/representatives`) and the ADMIN write endpoints under `/admin/institutions/*`.
 * Reads are public; every write is `hasRole('ADMIN')` SERVER-side (ARCHITECTURE.md §6.2). The
 * representative write enforces the additive-role + mandate⇄geography invariants in the backend service
 * (§6.4 D12, US-0.6). Envelope/error handling is delegated to {@link ApiClient} (DRY, CLAUDE.md §8).</p>
 */
@Injectable({ providedIn: 'root' })
export class InstitutionsAdminService {
  private readonly api = inject(ApiClient);

  // ---- Parliaments (UC-B12) ----

  /**
   * Lists parliament terms, paged. `GET /parliaments`.
   * @param params optional `legislature`, plus `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link Parliament}.
   */
  listParliaments(params: { legislature?: string; page?: number; size?: number; sort?: string }): Observable<Page<Parliament>> {
    return this.api.getPage<Parliament>('/parliaments', params);
  }

  /**
   * Fetches one parliament term. `GET /parliaments/{id}`.
   * @param id the term's public id.
   * @returns the {@link Parliament}.
   */
  getParliament(id: string): Observable<Parliament> {
    return this.api.get<Parliament>(`/parliaments/${id}`);
  }

  /**
   * Creates a parliament term. `POST /admin/institutions/parliaments` (Admin).
   * @param body the validated write body.
   * @returns the created {@link Parliament}.
   */
  createParliament(body: ParliamentWrite): Observable<Parliament> {
    return this.api.post<Parliament, ParliamentWrite>('/admin/institutions/parliaments', body);
  }

  /**
   * Updates a parliament term. `PUT /admin/institutions/parliaments/{id}` (Admin).
   * @param id the term's public id.
   * @param body the validated write body.
   * @returns the updated {@link Parliament}.
   */
  updateParliament(id: string, body: ParliamentWrite): Observable<Parliament> {
    return this.api.put<Parliament, ParliamentWrite>(`/admin/institutions/parliaments/${id}`, body);
  }

  /**
   * Soft-deletes a parliament term. `DELETE /admin/institutions/parliaments/{id}` (Admin).
   * @param id the term's public id.
   * @returns `void` on success.
   */
  deleteParliament(id: string): Observable<void> {
    return this.api.del(`/admin/institutions/parliaments/${id}`);
  }

  // ---- Parliament roles (UC-B13) ----

  /**
   * Lists parliament roles, paged. `GET /parliaments/roles`.
   * @param params optional `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link ParliamentRole}.
   */
  listParliamentRoles(params: { page?: number; size?: number; sort?: string }): Observable<Page<ParliamentRole>> {
    return this.api.getPage<ParliamentRole>('/parliaments/roles', params);
  }

  /**
   * Creates a parliament role. `POST /admin/institutions/parliament-roles` (Admin).
   * @param body the validated write body.
   * @returns the created {@link ParliamentRole}.
   */
  createParliamentRole(body: ParliamentRoleWrite): Observable<ParliamentRole> {
    return this.api.post<ParliamentRole, ParliamentRoleWrite>('/admin/institutions/parliament-roles', body);
  }

  /**
   * Updates a parliament role (code immutable). `PUT /admin/institutions/parliament-roles/{id}` (Admin).
   * @param id the role's public id.
   * @param body the validated write body.
   * @returns the updated {@link ParliamentRole}.
   */
  updateParliamentRole(id: string, body: ParliamentRoleWrite): Observable<ParliamentRole> {
    return this.api.put<ParliamentRole, ParliamentRoleWrite>(`/admin/institutions/parliament-roles/${id}`, body);
  }

  /**
   * Soft-deletes a parliament role. `DELETE /admin/institutions/parliament-roles/{id}` (Admin).
   * @param id the role's public id.
   * @returns `void` on success.
   */
  deleteParliamentRole(id: string): Observable<void> {
    return this.api.del(`/admin/institutions/parliament-roles/${id}`);
  }

  // ---- Representatives (UC-C04, UC-C08) ----

  /**
   * Lists/searches representatives, paged. `GET /representatives` (reused for the admin picker).
   * @param params optional `type`/`status`/`q`, plus `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link RepresentativeSummary}.
   */
  listRepresentatives(params: {
    type?: string;
    status?: string;
    q?: string;
    page?: number;
    size?: number;
    sort?: string;
  }): Observable<Page<RepresentativeSummary>> {
    return this.api.getPage<RepresentativeSummary>('/representatives', params);
  }

  /**
   * Creates/links a representative to an EXISTING profile (additive role, §6.4 D12).
   * `POST /admin/institutions/representatives` (Admin).
   * @param body the validated write body.
   * @returns the created representative (typed loosely; the form only needs the success signal).
   */
  createRepresentative(body: RepresentativeWrite): Observable<unknown> {
    return this.api.post<unknown, RepresentativeWrite>('/admin/institutions/representatives', body);
  }
}
