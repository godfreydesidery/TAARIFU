import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import { District, Region } from './geography.models';

/**
 * Read-only data access for civic geography (regions + districts).
 *
 * <p>Responsibility: the feature's typed gateway over the public geography endpoints, delegating all
 * HTTP/envelope concerns to {@link ApiClient}. It owns NO URL strings beyond the resource paths and no
 * envelope plumbing (DRY, CLAUDE.md §8). Geography reads are public on the server, so no special auth is
 * needed beyond the bearer the interceptor attaches.</p>
 */
@Injectable({ providedIn: 'root' })
export class GeographyService {
  private readonly api = inject(ApiClient);

  /**
   * Lists regions (Mikoa), paged + sortable. `GET /regions`.
   * @param params optional `page`, `size`, `sort`.
   * @returns a {@link Page} of {@link Region}.
   */
  listRegions(params: { page?: number; size?: number; sort?: string }): Observable<Page<Region>> {
    return this.api.getPage<Region>('/regions', params);
  }

  /**
   * Lists the districts (Wilaya) of a region, paged. `GET /regions/{regionId}/districts`.
   * @param regionId the parent region's public id.
   * @param params optional `page`, `size`, `sort`.
   * @returns a {@link Page} of {@link District}.
   */
  listDistricts(
    regionId: string,
    params: { page?: number; size?: number; sort?: string },
  ): Observable<Page<District>> {
    return this.api.getPage<District>(`/regions/${regionId}/districts`, params);
  }
}
