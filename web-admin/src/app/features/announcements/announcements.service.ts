import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Announcement } from './announcements.models';

/**
 * Data access for civic announcements (communications module; PRD §12, §22.6, M4).
 *
 * <p>Responsibility: the feature's typed gateway over the announcement endpoints, delegating all
 * HTTP/envelope concerns to {@link ApiClient} (DRY, CLAUDE.md §8). Today it backs the <b>public detail
 * read</b> ({@code GET /announcements/{id}}), which the server gates entirely service-side: only a
 * PUBLISHED, in-window announcement is returned, everything else 404s — so the client never needs to
 * second-guess visibility (PRD §18).</p>
 *
 * <p>NOTE (API gap): there is no admin "list ALL announcements" endpoint yet — only the public detail read
 * and the author-scoped {@code GET /announcements/mine}. The detail view is therefore reached by id; a
 * console-wide announcements list/queue is a backend follow-up (flagged in the UI gap note).</p>
 */
@Injectable({ providedIn: 'root' })
export class AnnouncementsService {
  private readonly api = inject(ApiClient);

  /**
   * Fetches a single published, citizen-visible announcement by its public id. `GET /announcements/{id}`.
   *
   * <p>Public read — visibility is enforced server-side. A non-published / expired / held / unknown id
   * returns {@code NOT_FOUND} (404), surfaced to the caller as an {@code ApiError} the detail view renders
   * as a "not available" empty state rather than leaking why.</p>
   *
   * @param id the announcement's public id.
   * @returns the {@link Announcement}.
   */
  get(id: string): Observable<Announcement> {
    return this.api.get<Announcement>(`/announcements/${id}`);
  }
}
