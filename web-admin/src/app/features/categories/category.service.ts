import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import { CreateIssueCategory, IssueCategory, UpdateIssueCategory } from './category.models';

/**
 * Data access for the issue-category taxonomy — the admin CRUD surface (PRD §9.1, Appendix D; UC-B14).
 *
 * <p>Responsibility: the feature's typed gateway over `/issue-categories`. The admin list reads the
 * `/admin` variant (active + retired); writes (create/update/delete) hit the Admin-gated endpoints.
 * Authorization is enforced SERVER-side (`@PreAuthorize("hasRole('ADMIN')")`); the client only shows the
 * UI to admins as a convenience (ARCHITECTURE.md §6.2). Envelope/error handling is delegated to
 * {@link ApiClient} + the interceptors (DRY, CLAUDE.md §8).</p>
 */
@Injectable({ providedIn: 'root' })
export class CategoryService {
  private readonly api = inject(ApiClient);

  /**
   * Lists ALL categories (active + retired) for the Admin console. `GET /issue-categories/admin`.
   * @param params optional `page`, `size`, `sort`.
   * @returns a {@link Page} of {@link IssueCategory}.
   */
  listAll(params: { page?: number; size?: number; sort?: string }): Observable<Page<IssueCategory>> {
    return this.api.getPage<IssueCategory>('/issue-categories/admin', params);
  }

  /**
   * Lists ACTIVE categories only (the citizen/admin picker list). `GET /issue-categories`.
   *
   * <p>Backs the category typeahead picker used in admin forms (responder capability, routing rules). The
   * public active list is the right source for a "choose a category" control — retired categories must not
   * be selectable for new routing. The taxonomy is small, so callers fetch a generous page once and filter
   * client-side rather than re-querying per keystroke (PRD §15 — fewer round-trips on a slow link).</p>
   *
   * @param params optional `page`/`size`/`sort` (default a large size to fetch the whole small taxonomy).
   * @returns a {@link Page} of active {@link IssueCategory}.
   */
  listActive(params: { page?: number; size?: number; sort?: string }): Observable<Page<IssueCategory>> {
    return this.api.getPage<IssueCategory>('/issue-categories', params);
  }

  /**
   * Fetches one category. `GET /issue-categories/{id}`.
   * @param id the category's public id.
   * @returns the {@link IssueCategory}.
   */
  get(id: string): Observable<IssueCategory> {
    return this.api.get<IssueCategory>(`/issue-categories/${id}`);
  }

  /**
   * Creates a category. `POST /issue-categories` (Admin).
   * @param body the validated create request.
   * @returns the created {@link IssueCategory}.
   */
  create(body: CreateIssueCategory): Observable<IssueCategory> {
    return this.api.post<IssueCategory, CreateIssueCategory>('/issue-categories', body);
  }

  /**
   * Updates a category. `PUT /issue-categories/{id}` (Admin).
   * @param id the category's public id.
   * @param body the validated update request.
   * @returns the updated {@link IssueCategory}.
   */
  update(id: string, body: UpdateIssueCategory): Observable<IssueCategory> {
    return this.api.put<IssueCategory, UpdateIssueCategory>(`/issue-categories/${id}`, body);
  }

  /**
   * Soft-deletes (retires) a category. `DELETE /issue-categories/{id}` (Admin).
   * @param id the category's public id.
   * @returns `void` on success.
   */
  delete(id: string): Observable<void> {
    return this.api.del(`/issue-categories/${id}`);
  }
}
