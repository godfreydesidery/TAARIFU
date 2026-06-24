import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import {
  GrantRoleRequest,
  Me,
  RoleGranted,
  SuspendUserRequest,
  UserAdminDetail,
  UserAdminSummary,
  UserListFilter,
} from './users.models';

/**
 * Data access for the Users & Roles admin area (M14, US-14.1, UC-H06; PRD §6.4, §7.1).
 *
 * <p>Responsibility: the feature's typed gateway over the admin user-management surface
 * (`/admin/users/*`). It lists accounts (PII-minimised, filterable + paged), reads one account's detail
 * (roles + scopes), grants/revokes roles additively (D15), and suspends/reinstates accounts. The acting
 * admin is taken from the bearer token server-side and is never a request field; the no-self-action fence
 * (D16 — an admin can neither escalate their own roles nor suspend their own account) and the role-catalogue
 * validation are enforced SERVER-side and surface as CONFLICT/VALIDATION toasts via the interceptor.
 * Envelope/error handling is delegated to {@link ApiClient} (DRY, CLAUDE.md §8).</p>
 *
 * <p>The operator's own snapshot ({@link getMe}) backs the "my identity" card; it is the only first-party
 * identity read and is unaffected by the admin gating on the management endpoints.</p>
 */
@Injectable({ providedIn: 'root' })
export class UsersService {
  private readonly api = inject(ApiClient);

  /**
   * Lists accounts for management, filtered and paged. `GET /admin/users`.
   *
   * <p>Server-side filtering by name/phoneSuffix/tier/role/status; rows are PII-minimised (masked phone,
   * never an ID). Empty/blank filters are dropped from the query by {@link ApiClient}.</p>
   *
   * @param filter optional name/phoneSuffix/tier/role/status filters plus `page`/`size`.
   * @returns a {@link Page} of {@link UserAdminSummary}.
   */
  listUsers(filter: UserListFilter): Observable<Page<UserAdminSummary>> {
    return this.api.getPage<UserAdminSummary>('/admin/users', {
      name: filter.name,
      phoneSuffix: filter.phoneSuffix,
      tier: filter.tier,
      role: filter.role,
      status: filter.status,
      page: filter.page,
      size: filter.size,
    });
  }

  /**
   * Loads one account's admin detail (roles + scopes, tier, status, location count). `GET /admin/users/{id}`.
   * @param publicId the account's public id.
   * @returns the {@link UserAdminDetail}.
   */
  getUser(publicId: string): Observable<UserAdminDetail> {
    return this.api.get<UserAdminDetail>(`/admin/users/${publicId}`);
  }

  /**
   * Grants a role to an account additively, with optional scope + effective window (D15).
   * `POST /admin/users/{id}/roles`.
   * @param publicId the target account's public id.
   * @param body the role to grant + optional scope.
   * @returns the granted assignment id (so the console can address it for a later revoke).
   */
  grantRole(publicId: string, body: GrantRoleRequest): Observable<RoleGranted> {
    return this.api.post<RoleGranted, GrantRoleRequest>(`/admin/users/${publicId}/roles`, body);
  }

  /**
   * Revokes (end-dates) a specific role grant by its assignment id (sets it FORMER; never hard-deletes).
   * `DELETE /admin/users/{id}/roles/{assignmentId}`.
   * @param publicId the target account's public id.
   * @param assignmentId the role-assignment public id to revoke.
   * @returns `void` on success.
   */
  revokeRole(publicId: string, assignmentId: string): Observable<void> {
    return this.api.del(`/admin/users/${publicId}/roles/${assignmentId}`);
  }

  /**
   * Suspends an account so it can no longer authenticate/act (recoverable). `POST /admin/users/{id}/suspend`.
   * @param publicId the target account's public id.
   * @param body optional machine reason code.
   * @returns `void` on success.
   */
  suspend(publicId: string, body: SuspendUserRequest): Observable<void> {
    return this.api.post<void, SuspendUserRequest>(`/admin/users/${publicId}/suspend`, body);
  }

  /**
   * Reinstates a suspended account to ACTIVE. `POST /admin/users/{id}/reinstate`.
   * @param publicId the target account's public id.
   * @returns `void` on success.
   */
  reinstate(publicId: string): Observable<void> {
    return this.api.post<void, Record<string, never>>(`/admin/users/${publicId}/reinstate`, {});
  }

  /**
   * Fetches the signed-in operator's own profile + role snapshot. `GET /profiles/me`.
   * @returns the {@link Me} snapshot (backs the "my identity" card).
   */
  getMe(): Observable<Me> {
    return this.api.get<Me>('/profiles/me');
  }
}
