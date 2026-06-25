import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

import { ApiClient } from '../../core/api/api-client.service';

/** A region (Mkoa) — top of the administrative hierarchy. */
export interface Region {
  /** Public id. */
  id: string;
  /** Stable code. */
  code: string;
  /** Display name. */
  name: string;
}

/** A district (Wilaya) within a region. */
export interface District {
  /** Public id. */
  id: string;
  /** Stable code. */
  code: string;
  /** Display name. */
  name: string;
  /** Parent region public id. */
  regionId: string;
}

/** A ward (Kata) summary — the minimum pin granularity for a report (PRD §9.0). */
export interface WardSummary {
  /** Public id. */
  id: string;
  /** Stable code. */
  code: string;
  /** Display name (Kata). */
  name: string;
  /** Council/LGA name (Halmashauri) for disambiguation. */
  councilName: string;
  /** District name (Wilaya) for disambiguation. */
  districtName: string;
}

/**
 * Read-only access to the Tanzanian administrative geography for the cascading area picker
 * (Mkoa → Wilaya → Kata) used when filing a report and when finding a representative.
 *
 * <p>Responsibility: thin typed reads over the public geography endpoints. All are `permitAll()` and
 * SW-cached so the picker works on a slow link and even offline once primed. The hierarchy stops at the
 * ward because the report pin and the "find my rep" lookup both key on the ward (PRD §9.0).</p>
 */
@Injectable({ providedIn: 'root' })
export class GeographyService {
  private readonly api = inject(ApiClient);

  /** Lists regions (Mikoa), sorted by name; large page since there are only ~31. */
  listRegions(): Observable<Region[]> {
    return this.api.getPage<Region>('/regions', { size: 100, sort: 'name,asc' }).pipe(map((p) => p.content));
  }

  /** Lists the districts (Wilaya) of a region. */
  listDistricts(regionId: string): Observable<District[]> {
    return this.api
      .getPage<District>(`/regions/${regionId}/districts`, { size: 100, sort: 'name,asc' })
      .pipe(map((p) => p.content));
  }

  /** Lists the wards (Kata) of a district. */
  listWards(districtId: string): Observable<WardSummary[]> {
    return this.api
      .getPage<WardSummary>(`/districts/${districtId}/wards`, { size: 100, sort: 'name,asc' })
      .pipe(map((p) => p.content));
  }

  /** Searches wards by free text (used to let a citizen jump straight to their Kata). */
  searchWards(q: string): Observable<WardSummary[]> {
    return this.api.getPage<WardSummary>('/wards', { q, size: 20 }).pipe(map((p) => p.content));
  }
}
