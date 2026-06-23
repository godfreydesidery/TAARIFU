import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import {
  CreateOrganisation,
  CreateResponder,
  Organisation,
  Responder,
  RoutingRule,
  UpdateOrganisation,
} from './responders.models';

/**
 * Data access for the responder directory admin surface (PRD §24, D20/D21).
 *
 * <p>Responsibility: the feature's typed gateway over `/responders/admin/*`. It supports organisation +
 * responder CRUD, the dedicated verification toggle (the §24.4 go-live gate), and the routing-rules view.
 * Authorization is enforced SERVER-side (`hasRole('ADMIN') or hasRole('MODERATOR')`); the client only
 * shows the UI to those roles as a convenience (ARCHITECTURE.md §6.2). Envelope/error handling is
 * delegated to {@link ApiClient} (DRY, CLAUDE.md §8).</p>
 */
@Injectable({ providedIn: 'root' })
export class RespondersService {
  private readonly api = inject(ApiClient);

  /**
   * Lists all organisations (any status), paged. `GET /responders/admin/organisations`.
   * @param params optional `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link Organisation}.
   */
  listOrganisations(params: { page?: number; size?: number; sort?: string }): Observable<Page<Organisation>> {
    return this.api.getPage<Organisation>('/responders/admin/organisations', params);
  }

  /**
   * Creates an organisation (PENDING + unverified). `POST /responders/admin/organisations`.
   * @param body the validated create request.
   * @returns the created {@link Organisation}.
   */
  createOrganisation(body: CreateOrganisation): Observable<Organisation> {
    return this.api.post<Organisation, CreateOrganisation>('/responders/admin/organisations', body);
  }

  /**
   * Updates an organisation's mutable fields. `PUT /responders/admin/organisations/{id}`.
   * @param id the organisation's public id.
   * @param body the validated update request.
   * @returns the updated {@link Organisation}.
   */
  updateOrganisation(id: string, body: UpdateOrganisation): Observable<Organisation> {
    return this.api.put<Organisation, UpdateOrganisation>(`/responders/admin/organisations/${id}`, body);
  }

  /**
   * Verifies or un-verifies an organisation (the §24.4 go-live gate; separately audited).
   * `POST /responders/admin/organisations/{id}/verification`.
   * @param id the organisation's public id.
   * @param verified the new verification state.
   * @returns the updated {@link Organisation}.
   */
  setVerified(id: string, verified: boolean): Observable<Organisation> {
    return this.api.post<Organisation, { verified: boolean }>(
      `/responders/admin/organisations/${id}/verification`,
      { verified },
    );
  }

  /**
   * Lists the responders of an organisation (any status), paged.
   * `GET /responders/admin/organisations/{id}/responders`.
   * @param organisationId the owning organisation's public id.
   * @param params optional `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link Responder}.
   */
  listResponders(
    organisationId: string,
    params: { page?: number; size?: number; sort?: string },
  ): Observable<Page<Responder>> {
    return this.api.getPage<Responder>(`/responders/admin/organisations/${organisationId}/responders`, params);
  }

  /**
   * Creates a responder capability under an organisation.
   * `POST /responders/admin/organisations/{id}/responders`.
   * @param organisationId the owning organisation's public id.
   * @param body the validated create request.
   * @returns the created {@link Responder}.
   */
  createResponder(organisationId: string, body: CreateResponder): Observable<Responder> {
    return this.api.post<Responder, CreateResponder>(
      `/responders/admin/organisations/${organisationId}/responders`,
      body,
    );
  }

  /**
   * Lists all routing rules, paged. `GET /responders/admin/routing-rules`.
   * @param params optional `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link RoutingRule}.
   */
  listRoutingRules(params: { page?: number; size?: number; sort?: string }): Observable<Page<RoutingRule>> {
    return this.api.getPage<RoutingRule>('/responders/admin/routing-rules', params);
  }
}
