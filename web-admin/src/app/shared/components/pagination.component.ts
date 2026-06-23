import { Component, EventEmitter, Input, Output } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';

/**
 * Reusable server-side pagination control driven by the backend {@link PageMeta}.
 *
 * <p>Responsibility: a presentational ("dumb") component shared by every list page (geography,
 * representatives, parties, categories) so pagination UX is consistent and defined once (DRY,
 * CLAUDE.md §3). It renders the current page window using the AUTHORITATIVE server counts in
 * {@link meta} (never client guesses) and emits {@link pageChange} when the user navigates; the parent
 * list re-queries the API with the new page index.</p>
 *
 * <p>Accessibility: rendered as a `<nav aria-label>` with real `<button>`s; the active/disabled states
 * are conveyed via `aria-current` and the `disabled` attribute (WCAG 2.1 AA). Labels are i18n keys.</p>
 */
@Component({
  selector: 'app-pagination',
  standalone: true,
  imports: [TranslateModule],
  template: `
    @if (meta && meta.totalPages > 1) {
      <nav class="d-flex align-items-center justify-content-between flex-wrap gap-2 mt-3" aria-label="Pagination">
        <small class="text-muted">
          {{ 'common.page' | translate }} {{ meta.page + 1 }} {{ 'common.of' | translate }}
          {{ meta.totalPages }} — {{ 'common.total' | translate }}: {{ meta.total }}
        </small>
        <ul class="pagination pagination-sm mb-0">
          <li class="page-item" [class.disabled]="meta.page === 0">
            <button
              type="button"
              class="page-link"
              [disabled]="meta.page === 0"
              (click)="go(meta.page - 1)"
              [attr.aria-label]="'common.back' | translate"
            >
              &laquo;
            </button>
          </li>
          <li class="page-item active" aria-current="page">
            <span class="page-link">{{ meta.page + 1 }}</span>
          </li>
          <li class="page-item" [class.disabled]="meta.page + 1 >= meta.totalPages">
            <button
              type="button"
              class="page-link"
              [disabled]="meta.page + 1 >= meta.totalPages"
              (click)="go(meta.page + 1)"
              aria-label="Next"
            >
              &raquo;
            </button>
          </li>
        </ul>
      </nav>
    }
  `,
})
export class PaginationComponent {
  /** The server page metadata for the current list. When `null`/single-page, the control hides. */
  @Input() meta: PageMeta | null = null;

  /** Emits the zero-based page index the user navigated to; the parent re-queries the API. */
  @Output() readonly pageChange = new EventEmitter<number>();

  /** Guards the bounds and emits a page change. */
  go(page: number): void {
    if (!this.meta || page < 0 || page >= this.meta.totalPages || page === this.meta.page) {
      return;
    }
    this.pageChange.emit(page);
  }
}
