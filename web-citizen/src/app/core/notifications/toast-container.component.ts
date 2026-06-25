import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { ToastService } from './toast.service';

/**
 * Renders the active {@link ToastService} toasts as an accessible, mobile-anchored stack.
 *
 * <p>Responsibility: a thin, always-mounted presenter. Error toasts use `role="alert"` (assertive) and
 * others `role="status"` (polite) so screen readers announce them appropriately (WCAG 4.1.3). Anchored
 * above the bottom nav so it never covers the primary actions on a phone.</p>
 */
@Component({
  selector: 'app-toast-container',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toast-stack" aria-live="polite" aria-atomic="false">
      @for (toast of toasts(); track toast.id) {
        <div
          class="toast-item"
          [class.toast-item--success]="toast.level === 'success'"
          [class.toast-item--error]="toast.level === 'error'"
          [class.toast-item--info]="toast.level === 'info'"
          [attr.role]="toast.level === 'error' ? 'alert' : 'status'"
        >
          <span class="toast-item__msg">{{ toast.message }}</span>
          <button
            type="button"
            class="toast-item__close"
            (click)="toastService.dismiss(toast.id)"
            aria-label="Funga arifa"
          >
            &times;
          </button>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .toast-stack {
        position: fixed;
        left: 50%;
        transform: translateX(-50%);
        bottom: calc(var(--tz-bottom-nav-h) + 12px + env(safe-area-inset-bottom, 0px));
        z-index: 1080;
        width: min(94vw, 30rem);
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        pointer-events: none;
      }
      .toast-item {
        pointer-events: auto;
        display: flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.7rem 0.9rem;
        border-radius: 0.85rem;
        color: #fff;
        box-shadow: 0 6px 20px rgba(0, 0, 0, 0.18);
        font-size: 0.95rem;
      }
      .toast-item--success {
        background: var(--tz-green);
      }
      .toast-item--error {
        background: var(--tz-danger);
      }
      .toast-item--info {
        background: var(--tz-teal);
      }
      .toast-item__msg {
        flex: 1;
      }
      .toast-item__close {
        background: transparent;
        border: 0;
        color: inherit;
        font-size: 1.4rem;
        line-height: 1;
        min-width: 32px;
        min-height: 32px;
      }
    `,
  ],
})
export class ToastContainerComponent {
  /** The toast queue service (also used by the template to dismiss). */
  protected readonly toastService = inject(ToastService);

  /** Reactive list of toasts to render. */
  protected readonly toasts = this.toastService.toasts;
}
