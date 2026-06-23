import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { AuthService } from '../../core/auth/auth.service';
import { Me, ROLE_CATALOGUE } from './users.models';
import { UsersService } from './users.service';

/**
 * Users & Roles admin area (PRD §6.4, §7).
 *
 * <p>Responsibility: shows the signed-in operator's identity, additive roles, and trust tier, the
 * platform role catalogue (reference), and the available role-grant entry point (link a Representative,
 * US-0.6, §6.4 D12). It deliberately and visibly flags that a generic user directory + arbitrary
 * role-grant/scope admin API does not yet exist server-side (a CENTRAL NEED) rather than faking a list.
 * Loading/error states are handled; subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-users-list',
  standalone: true,
  imports: [RouterLink, TranslateModule],
  templateUrl: './users-list.component.html',
})
export class UsersListComponent implements OnInit {
  private readonly users = inject(UsersService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  /** UI state. */
  readonly me = signal<Me | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  /** The session roles (JWT hint) as a fallback when the profile snapshot omits them. */
  readonly sessionRoles = this.auth.session;

  /** The platform role catalogue for reference. */
  readonly roleCatalogue = ROLE_CATALOGUE;

  /** Loads the operator snapshot on init. */
  ngOnInit(): void {
    this.load();
  }

  /** Loads the signed-in operator's own profile + role snapshot. */
  load(): void {
    this.loading.set(true);
    this.errored.set(false);
    this.users
      .getMe()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (me) => {
          this.me.set(me);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });
  }

  /** The effective roles to display (profile snapshot, else JWT session hint). */
  effectiveRoles(): string[] {
    return this.me()?.roles ?? this.sessionRoles()?.roles ?? [];
  }
}
