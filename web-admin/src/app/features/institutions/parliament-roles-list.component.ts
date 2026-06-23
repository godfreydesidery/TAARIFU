import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';
import { ToastService } from '../../core/notifications/toast.service';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { ParliamentRole } from './institutions-admin.models';
import { InstitutionsAdminService } from './institutions-admin.service';

/**
 * Parliament-roles admin list with create/edit/soft-delete (PRD §9.1; UC-B13).
 *
 * <p>Responsibility: lists parliament roles (Speaker, Minister, committee chair, …) paged, links to the
 * create/edit form, and soft-deletes a role (confirm + success toast). Writes are ADMIN-gated SERVER-side.
 * Loading/empty/error states are handled; subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-parliament-roles-list',
  standalone: true,
  imports: [RouterLink, TranslateModule, PaginationComponent],
  templateUrl: './parliament-roles-list.component.html',
})
export class ParliamentRolesListComponent implements OnInit {
  private readonly institutions = inject(InstitutionsAdminService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** List UI state. */
  readonly rows = signal<ParliamentRole[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  private readonly pageSize = 20;

  /** Loads the first page on init. */
  ngOnInit(): void {
    this.loadPage(0);
  }

  /**
   * Loads a page of parliament roles.
   * @param page zero-based page index.
   */
  loadPage(page: number): void {
    this.loading.set(true);
    this.errored.set(false);
    this.institutions
      .listParliamentRoles({ page, size: this.pageSize, sort: 'name,asc' })
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

  /**
   * Soft-deletes a parliament role after a confirm, then reloads.
   * @param role the role to delete.
   */
  remove(role: ParliamentRole): void {
    if (!confirm(this.translate.instant('institutions.confirmDelete'))) {
      return;
    }
    this.institutions
      .deleteParliamentRole(role.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('common.deleted'));
          this.loadPage(this.meta()?.page ?? 0);
        },
        error: () => undefined,
      });
  }
}
