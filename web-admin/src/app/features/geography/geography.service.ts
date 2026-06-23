import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import { District, Region, WardSummary } from './geography.models';

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

  /**
   * Searches wards (Kata) by name prefix for the manual ward picker. `GET /wards?q=&districtId=`.
   *
   * <p>Backs the typeahead used when GPS is unavailable (report/profile/responder forms, find-my-rep —
   * PRD §22.6). A blank/absent {@code q} returns an empty page server-side (a picker never pulls the whole
   * national ward table on an empty box, PRD §15), so the caller debounces and only queries on input.</p>
   *
   * @param q the ward-name prefix the user typed; blank yields an empty page.
   * @param params optional `districtId` to scope the search, plus `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link WardSummary} (each carrying council + district names to disambiguate).
   */
  searchWards(
    q: string,
    params?: { districtId?: string; page?: number; size?: number; sort?: string },
  ): Observable<Page<WardSummary>> {
    return this.api.getPage<WardSummary>('/wards', { q, ...params });
  }

  /**
   * Lists the wards (Kata) under a district (Wilaya), paged. `GET /districts/{districtId}/wards`.
   *
   * <p>The district-scoped variant of the picker — used when the user has already narrowed to a district
   * and wants to browse rather than type. Returns the same lean {@link WardSummary} projection.</p>
   *
   * @param districtId the parent district's public id.
   * @param params optional `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link WardSummary}.
   */
  listWardsInDistrict(
    districtId: string,
    params?: { page?: number; size?: number; sort?: string },
  ): Observable<Page<WardSummary>> {
    return this.api.getPage<WardSummary>(`/districts/${districtId}/wards`, params);
  }
}
