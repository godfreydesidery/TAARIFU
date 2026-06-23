import { Component, inject } from '@angular/core';

import { ToastService } from './toast.service';

/**
 * Renders the live {@link ToastService} queue as Bootstrap toasts in a fixed corner stack.
 *
 * <p>Responsibility: the single visual sink for app-wide notifications. Mounted once at the app root so
 * toasts appear above any page. The stack is an `aria-live="polite"` region so screen readers announce
 * new toasts without stealing focus (WCAG 2.1 AA, CLAUDE.md §5). Each toast has a keyboard-reachable
 * close button. No external toast library is used — keeping the bundle lean for low-data clients
 * (PRD §15).</p>
 */
@Component({
  selector: 'app-toast-container',
  standalone: true,
  template: `
    <div
      class="toast-container position-fixed top-0 end-0 p-3"
      style="z-index: 1090;"
      aria-live="polite"
      aria-atomic="true"
    >
      @for (toast of toasts(); track toast.id) {
        <div class="toast show align-items-center text-bg-{{ toast.kind }} border-0 mb-2" role="alert">
          <div class="d-flex">
            <div class="toast-body">{{ toast.text }}</div>
            <button
              type="button"
              class="btn-close btn-close-white me-2 m-auto"
              aria-label="Close"
              (click)="dismiss(toast.id)"
            ></button>
          </div>
        </div>
      }
    </div>
  `,
})
export class ToastContainerComponent {
  private readonly toastService = inject(ToastService);

  /** The live toasts to render. */
  readonly toasts = this.toastService.toasts;

  /**
   * Dismisses a toast on user click.
   * @param id the toast id.
   */
  dismiss(id: number): void {
    this.toastService.dismiss(id);
  }
}
