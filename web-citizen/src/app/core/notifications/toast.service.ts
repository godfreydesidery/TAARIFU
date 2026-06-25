import { Injectable, signal } from '@angular/core';

/** A single transient notification shown to the citizen. */
export interface Toast {
  /** Stable id for trackBy + dismissal. */
  id: number;
  /** Severity, drives the colour + ARIA role. */
  level: 'success' | 'error' | 'info';
  /** Already-localised, user-safe text (from the server envelope or an i18n key the caller resolved). */
  message: string;
}

/**
 * Lightweight, dependency-free toast queue.
 *
 * <p>Responsibility: the single source of transient notifications, exposed as a signal the
 * {@link ToastContainerComponent} renders. Used by the {@link ApiResponseInterceptor} (errors) and feature
 * flows (e.g. "draft saved offline"). Toasts auto-dismiss so they never block a one-handed mobile user.</p>
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private static readonly AUTO_DISMISS_MS = 5000;
  private nextId = 1;

  /** Reactive list of active toasts. */
  private readonly toastsSignal = signal<Toast[]>([]);

  /** Read-only view of the active toasts. */
  readonly toasts = this.toastsSignal.asReadonly();

  /** Shows a success toast (e.g. report filed, draft queued). */
  success(message: string): void {
    this.push('success', message);
  }

  /** Shows an error toast (typically the server's localised envelope message). */
  error(message: string): void {
    this.push('error', message);
  }

  /** Shows an informational toast (e.g. "syncing your drafts…", "new version available"). */
  info(message: string): void {
    this.push('info', message);
  }

  /** Removes a toast by id (manual dismiss). */
  dismiss(id: number): void {
    this.toastsSignal.update((list) => list.filter((t) => t.id !== id));
  }

  /** Enqueues a toast and schedules its auto-dismissal. */
  private push(level: Toast['level'], message: string): void {
    const id = this.nextId++;
    this.toastsSignal.update((list) => [...list, { id, level, message }]);
    setTimeout(() => this.dismiss(id), ToastService.AUTO_DISMISS_MS);
  }
}
