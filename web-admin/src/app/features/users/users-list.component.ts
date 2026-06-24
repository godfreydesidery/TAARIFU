import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';
import { AuthService } from '../../core/auth/auth.service';
import { PaginationComponent } from '../../shared/components/pagination.component';
import {
  Me,
  ROLE_CATALOGUE,
  USER_STATUSES,
  USER_TIERS,
  UserAdminSummary,
} from './users.models';
import { UsersService } from './users.service';

/**
 * Users & Roles admin directory (M14, US-14.1, UC-H06; PRD §6.4, §7.1).
 *
 * <p>Responsibility: the server-paged account directory. It lists accounts from {@code GET /admin/users},
 * lets the operator filter by name, trailing phone digits, tier, role, and status (all server-side), and
 * links each row to the account detail for role/suspension management. A "my identity" card shows the
 * signed-in operator's own roles/tier (from {@code GET /profiles/me}) for orientation. Rows are
 * PII-minimised — the list shows only a masked phone, never a raw number or national ID (PRD §18, PDPA).
 * Loading/empty/error states are handled; subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-users-list',
  standalone: true,
  imports: [RouterLink, FormsModule, DatePipe, TranslateModule, PaginationComponent],
  templateUrl: './users-list.component.html',
})
export class UsersListComponent implements OnInit {
  private readonly users = inject(UsersService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  /** List UI state. */
  readonly rows = signal<UserAdminSummary[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  /** The signed-in operator's own snapshot (for the "my identity" card). */
  readonly me = signal<Me | null>(null);

  /** The session roles (JWT hint) as a fallback when the profile snapshot omits them. */
  readonly sessionRoles = this.auth.session;

  /** Server-side filter state. */
  readonly nameFilter = signal('');
  readonly phoneSuffixFilter = signal('');
  readonly tierFilter = signal('');
  readonly roleFilter = signal('');
  readonly statusFilter = signal('');

  /** Selectable tokens for the filter selects. */
  readonly tiers = USER_TIERS;
  readonly roleCatalogue = ROLE_CATALOGUE;
  readonly statuses = USER_STATUSES;

  private readonly pageSize = 20;

  /** Loads the operator snapshot + first page on init. */
  ngOnInit(): void {
    this.loadMe();
    this.loadPage(0);
  }

  /** Loads the signed-in operator's own profile + role snapshot (best-effort; non-fatal to the list). */
  private loadMe(): void {
    this.users
      .getMe()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (me) => this.me.set(me), error: () => this.me.set(null) });
  }

  /**
   * Loads a page of accounts for the active filters.
   * @param page zero-based page index.
   */
  loadPage(page: number): void {
    this.loading.set(true);
    this.errored.set(false);
    this.users
      .listUsers({
        name: this.nameFilter().trim() || undefined,
        phoneSuffix: this.phoneSuffixFilter().trim() || undefined,
        tier: this.tierFilter() || undefined,
        role: this.roleFilter() || undefined,
        status: this.statusFilter() || undefined,
        page,
        size: this.pageSize,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.rows.set(result.content);
          this.meta.set(result.meta);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });
  }

  /** Re-queries from page 0 when any filter changes (server-side filtering). */
  applyFilters(): void {
    this.loadPage(0);
  }

  /** The effective operator roles to display (profile snapshot, else JWT session hint). */
  effectiveRoles(): string[] {
    return this.me()?.roles ?? this.sessionRoles()?.roles ?? [];
  }
}
