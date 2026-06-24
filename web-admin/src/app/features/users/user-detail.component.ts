import { Component, DestroyRef, Input, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/notifications/toast.service';
import { AreaPickerComponent } from '../../shared/components/area-picker.component';
import { CategoryPickerComponent } from '../../shared/components/category-picker.component';
import { GRANTABLE_ROLES, GrantRoleRequest, UserAdminDetail } from './users.models';
import { UsersService } from './users.service';

/**
 * Account detail — identity (masked), roles + scopes, and the back-office actions (M14, US-14.1, UC-H06).
 *
 * <p>Responsibility: shows one account's admin detail and lets an operator manage it — grant a role
 * additively with optional area/category/constituency scope (D15), revoke a specific grant by its
 * assignment id, and suspend/reinstate the account. Every mutation is server-authorised
 * ({@code hasAnyRole('ADMIN','ROOT')}) and fenced by the no-self-action guard (D16) — an admin acting on
 * their own account surfaces as a CONFLICT toast from the interceptor; the detail then refreshes so the UI
 * reflects the server's authoritative state. The view never shows a raw phone or national ID — only the
 * masked phone, a location <i>count</i>, and verification state (PRD §18, PDPA). Subscriptions use
 * {@link takeUntilDestroyed}.</p>
 *
 * <p>WHY revoke is by assignment id (not role name): an account may hold several grants of the same role
 * with different scopes, so the role name alone cannot identify which grant to end-date — the
 * {@code assignmentId} on each {@code UserRoleGrant} is the addressable target (mirrors the backend).</p>
 */
@Component({
  selector: 'app-user-detail',
  standalone: true,
  imports: [RouterLink, FormsModule, DatePipe, TranslateModule, AreaPickerComponent, CategoryPickerComponent],
  templateUrl: './user-detail.component.html',
})
export class UserDetailComponent implements OnInit {
  private readonly users = inject(UsersService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);

  /** The account public id from the route (`/users/:userId`). */
  @Input() userId = '';

  /** Detail UI state. */
  readonly user = signal<UserAdminDetail | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);
  readonly acting = signal(false);

  /** Grant-role form state. */
  readonly grantRole = signal<string>(GRANTABLE_ROLES[0]);
  readonly grantAreaIds = signal<string[]>([]);
  readonly grantCategoryIds = signal<string[]>([]);
  readonly grantConstituencyId = signal('');

  /** Suspend form state. */
  readonly suspendReason = signal('');

  /** Roles an admin may grant (excludes CITIZEN/ROOT). */
  readonly grantableRoles = GRANTABLE_ROLES;

  /** Loads the account detail on init. */
  ngOnInit(): void {
    this.load();
  }

  /** Loads (or reloads) the account's admin detail. */
  load(): void {
    this.loading.set(true);
    this.errored.set(false);
    this.users
      .getUser(this.userId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (user) => {
          this.user.set(user);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });
  }

  /** Whether the account is currently suspended (drives the suspend/reinstate toggle). */
  isSuspended(): boolean {
    return this.user()?.status === 'SUSPENDED';
  }

  /**
   * Grants the selected role with the chosen optional scope. No-ops while acting. The scope ids are sent
   * only when non-empty so an unrestricted grant carries no scope arrays.
   */
  grant(): void {
    if (this.acting()) {
      return;
    }
    const body: GrantRoleRequest = { roleName: this.grantRole() };
    if (this.grantAreaIds().length > 0) {
      body.areaIds = this.grantAreaIds();
    }
    if (this.grantCategoryIds().length > 0) {
      body.categoryIds = this.grantCategoryIds();
    }
    const constituency = this.grantConstituencyId().trim();
    if (constituency) {
      body.constituencyId = constituency;
    }
    this.acting.set(true);
    this.users
      .grantRole(this.userId, body)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.acting.set(false);
          this.toast.success(this.translate.instant('users.roleGranted'));
          this.resetGrantForm();
          this.load();
        },
        error: () => this.acting.set(false),
      });
  }

  /**
   * Revokes (end-dates) a specific role grant. Confirms first; no-ops while acting.
   * @param assignmentId the role-assignment public id to revoke.
   */
  revoke(assignmentId: string): void {
    if (this.acting() || !confirm(this.translate.instant('users.confirmRevoke'))) {
      return;
    }
    this.acting.set(true);
    this.users
      .revokeRole(this.userId, assignmentId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.acting.set(false);
          this.toast.success(this.translate.instant('users.roleRevoked'));
          this.load();
        },
        error: () => this.acting.set(false),
      });
  }

  /** Suspends the account with the optional reason. Confirms first; no-ops while acting. */
  suspend(): void {
    if (this.acting() || !confirm(this.translate.instant('users.confirmSuspend'))) {
      return;
    }
    this.acting.set(true);
    this.users
      .suspend(this.userId, { reasonCode: this.suspendReason().trim() || undefined })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.acting.set(false);
          this.toast.success(this.translate.instant('users.suspended'));
          this.suspendReason.set('');
          this.load();
        },
        error: () => this.acting.set(false),
      });
  }

  /** Reinstates the suspended account to ACTIVE. Confirms first; no-ops while acting. */
  reinstate(): void {
    if (this.acting() || !confirm(this.translate.instant('users.confirmReinstate'))) {
      return;
    }
    this.acting.set(true);
    this.users
      .reinstate(this.userId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.acting.set(false);
          this.toast.success(this.translate.instant('users.reinstated'));
          this.load();
        },
        error: () => this.acting.set(false),
      });
  }

  /** Returns to the directory. */
  back(): void {
    void this.router.navigate(['/users']);
  }

  /** Clears the grant-role form after a successful grant. */
  private resetGrantForm(): void {
    this.grantRole.set(GRANTABLE_ROLES[0]);
    this.grantAreaIds.set([]);
    this.grantCategoryIds.set([]);
    this.grantConstituencyId.set('');
  }
}
