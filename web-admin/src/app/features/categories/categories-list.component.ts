import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';
import { ToastService } from '../../core/notifications/toast.service';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { IssueCategory } from './category.models';
import { CategoryService } from './category.service';

/**
 * Issue-category admin list with create/edit links and soft-delete (UC-B14; `/issue-categories/admin`).
 *
 * <p>Responsibility: the Admin CRUD list — loads all categories (active + retired) paged, links to the
 * create/edit form, and retires a category via {@link CategoryService.delete} (with a confirm and a
 * success toast). It is reachable only from the Admin-gated nav item and route; the SERVER still enforces
 * the role on every write (ARCHITECTURE.md §6.2). Loading/empty/error states are handled; subscriptions
 * use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-categories-list',
  standalone: true,
  imports: [RouterLink, TranslateModule, PaginationComponent],
  templateUrl: './categories-list.component.html',
})
export class CategoriesListComponent implements OnInit {
  private readonly categories = inject(CategoryService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** List UI state. */
  readonly rows = signal<IssueCategory[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  private readonly pageSize = 20;

  /** Loads the first page on init. */
  ngOnInit(): void {
    this.loadPage(0);
  }

  /**
   * Loads a page of categories.
   * @param page zero-based page index.
   */
  loadPage(page: number): void {
    this.loading.set(true);
    this.errored.set(false);
    this.categories
      .listAll({ page, size: this.pageSize, sort: 'name,asc' })
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
   * Retires (soft-deletes) a category after a confirm, then reloads the current page.
   * @param category the category to retire.
   */
  retire(category: IssueCategory): void {
    if (!confirm(this.translate.instant('categories.confirmDelete'))) {
      return;
    }
    this.categories
      .delete(category.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('common.deleted'));
          this.loadPage(this.meta()?.page ?? 0);
        },
        // Errors are toasted centrally by the interceptor.
        error: () => undefined,
      });
  }
}
