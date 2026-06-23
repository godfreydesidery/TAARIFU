import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';
import { ToastService } from '../../core/notifications/toast.service';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { Parliament } from './institutions-admin.models';
import { InstitutionsAdminService } from './institutions-admin.service';

/**
 * Parliament terms admin list with create/edit/soft-delete (PRD §9.1; UC-B12).
 *
 * <p>Responsibility: lists parliament terms paged, links to the create/edit form, and soft-deletes a term
 * (confirm + success toast). Writes are ADMIN-gated SERVER-side. Loading/empty/error states are handled;
 * subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-parliaments-list',
  standalone: true,
  imports: [RouterLink, TranslateModule, PaginationComponent],
  templateUrl: './parliaments-list.component.html',
})
export class ParliamentsListComponent implements OnInit {
  private readonly institutions = inject(InstitutionsAdminService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** List UI state. */
  readonly rows = signal<Parliament[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  private readonly pageSize = 20;

  /** Loads the first page on init. */
  ngOnInit(): void {
    this.loadPage(0);
  }

  /**
   * Loads a page of parliament terms.
   * @param page zero-based page index.
   */
  loadPage(page: number): void {
    this.loading.set(true);
    this.errored.set(false);
    this.institutions
      .listParliaments({ page, size: this.pageSize, sort: 'termNumber,desc' })
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
   * Soft-deletes a parliament term after a confirm, then reloads.
   * @param parliament the term to delete.
   */
  remove(parliament: Parliament): void {
    if (!confirm(this.translate.instant('institutions.confirmDelete'))) {
      return;
    }
    this.institutions
      .deleteParliament(parliament.id)
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
