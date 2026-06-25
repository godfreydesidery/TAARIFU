import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import { MyRepresentatives, RepresentativeSummary } from './representatives.models';

/**
 * Public representative directory access for "find my representative" (PRD §8).
 *
 * <p>Responsibility: thin typed reads over the public `/representatives` endpoints (all `permitAll()`,
 * SW-cached). The headline flow is {@link findByWard}: a citizen picks (or detects) their ward and gets
 * their MP (Mbunge, for the ward's constituency/Jimbo) and Councillor (Diwani, for the ward/Kata). A
 * free-text {@link search} backs the directory list.</p>
 */
@Injectable({ providedIn: 'root' })
export class RepresentativesService {
  private readonly api = inject(ApiClient);

  /** Resolves the MP + Councillor for a ward (the find-my-rep result). */
  findByWard(wardId: string): Observable<MyRepresentatives> {
    return this.api.get<MyRepresentatives>(`/representatives/by-ward/${wardId}`);
  }

  /** Lists/searches representatives for the directory (optional free-text + type filter), paged. */
  list(q?: string, type?: string, page = 0, size = 20): Observable<Page<RepresentativeSummary>> {
    return this.api.getPage<RepresentativeSummary>('/representatives', { q, type, page, size });
  }

  /** Loads one representative's summary by id (used for the directory detail tap). */
  get(representativeId: string): Observable<RepresentativeSummary> {
    return this.api
      .get<RepresentativeSummary>(`/representatives/${representativeId}`)
      .pipe(map((r) => r));
  }
}
