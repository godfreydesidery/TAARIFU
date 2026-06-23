import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Me } from './users.models';

/**
 * Data access for the Users & Roles admin area (PRD §6.4, §7).
 *
 * <p>Responsibility: the feature's typed gateway over the identity reads the backend exposes today.
 * Currently that is only the caller's OWN snapshot (`GET /profiles/me`) — there is no generic user-list
 * or arbitrary role-grant/scope endpoint (a CENTRAL NEED; see {@link Me}). When the platform adds
 * `GET /admin/users` + `POST /admin/users/{id}/roles`, this service grows the list + grant methods and the
 * components below switch from the self-snapshot to the real directory. Envelope/error handling is
 * delegated to {@link ApiClient} (DRY, CLAUDE.md §8).</p>
 */
@Injectable({ providedIn: 'root' })
export class UsersService {
  private readonly api = inject(ApiClient);

  /**
   * Fetches the signed-in operator's own profile + role snapshot. `GET /profiles/me`.
   * @returns the {@link Me} snapshot.
   */
  getMe(): Observable<Me> {
    return this.api.get<Me>('/profiles/me');
  }
}
