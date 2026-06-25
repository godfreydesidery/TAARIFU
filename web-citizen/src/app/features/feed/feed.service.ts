import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin } from 'rxjs';
import { map } from 'rxjs/operators';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import { PublicReport } from '../report/report.models';
import { Announcement, Petition, SearchResult } from './feed.models';

/** The assembled public feed: announcements, public reports, and petitions for the home screen. */
export interface FeedBundle {
  /** Latest public reports near the citizen (PII-free projection). */
  reports: PublicReport[];
  /** Latest petitions in the engagement feed. */
  petitions: Petition[];
}

/**
 * The home-feed / discovery service for the citizen PWA (PRD §11/§12, discovery).
 *
 * <p>Responsibility: aggregates the public, guest-readable civic graph for the home screen — public
 * reports ({@code /public/reports}) and petitions ({@code /petitions}) — and backs the discovery
 * {@link search}. All endpoints are `permitAll()` and SW-cached (freshness strategy) so the feed renders
 * from cache on a slow link and shows last-known content when offline (PRD §15). Announcements are fetched
 * by id on the detail view (the list endpoint is author-scoped); the public feed leads with reports +
 * petitions which both have open list endpoints.</p>
 */
@Injectable({ providedIn: 'root' })
export class FeedService {
  private readonly api = inject(ApiClient);

  /**
   * Loads the home feed in one shot (parallel reads). Each list is independently small + paged so the
   * payload stays low-data.
   *
   * @param wardId optional ward to bias the public reports toward the citizen's area.
   * @returns the assembled {@link FeedBundle}.
   */
  loadHomeFeed(wardId?: string): Observable<FeedBundle> {
    return forkJoin({
      reports: this.api
        .getPage<PublicReport>('/public/reports', { wardId, size: 10, sort: 'createdAt,desc' })
        .pipe(map((p) => p.content)),
      petitions: this.api
        .getPage<Petition>('/petitions', { size: 10, sort: 'createdAt,desc' })
        .pipe(map((p) => p.content)),
    });
  }

  /** Lists petitions (engagement feed), paged. */
  listPetitions(page = 0, size = 20): Observable<Page<Petition>> {
    return this.api.getPage<Petition>('/petitions', { page, size, sort: 'createdAt,desc' });
  }

  /** Loads one announcement by id (public read on the detail view). */
  getAnnouncement(announcementId: string): Observable<Announcement> {
    return this.api.get<Announcement>(`/announcements/${announcementId}`);
  }

  /**
   * Runs a cross-entity discovery search (reps, orgs, announcements, categories, public reports).
   * @param q the free-text query (Swahili or English); a blank query returns an empty page server-side.
   * @returns a page of ranked {@link SearchResult}.
   */
  search(q: string, page = 0, size = 20): Observable<Page<SearchResult>> {
    return this.api.getPage<SearchResult>('/search', { q, page, size });
  }
}
