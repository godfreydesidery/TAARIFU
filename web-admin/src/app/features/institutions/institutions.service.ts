import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import { PoliticalParty, RepresentativeSummary } from './institutions.models';

/**
 * Read-only data access for the institutions directory (representatives + parties).
 *
 * <p>Responsibility: the feature's typed gateway over the public `/representatives` and `/parties`
 * endpoints. It supports the server-side search/filter params those endpoints accept (`q`, `type`,
 * `status`), passing them straight through; {@link ApiClient} drops any `undefined` so omitted filters
 * are absent from the query string (DRY, CLAUDE.md §8).</p>
 */
@Injectable({ providedIn: 'root' })
export class InstitutionsService {
  private readonly api = inject(ApiClient);

  /**
   * Lists/searches representatives, paged. `GET /representatives`.
   * @param params optional `q` (free text), `type`, `status`, plus `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link RepresentativeSummary}.
   */
  listRepresentatives(params: {
    q?: string;
    type?: string;
    status?: string;
    page?: number;
    size?: number;
    sort?: string;
  }): Observable<Page<RepresentativeSummary>> {
    return this.api.getPage<RepresentativeSummary>('/representatives', params);
  }

  /**
   * Lists/searches political parties, paged. `GET /parties`.
   * @param params optional `q` (free text), plus `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link PoliticalParty}.
   */
  listParties(params: { q?: string; page?: number; size?: number; sort?: string }): Observable<Page<PoliticalParty>> {
    return this.api.getPage<PoliticalParty>('/parties', params);
  }
}
