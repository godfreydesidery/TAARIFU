import { Injectable, signal } from '@angular/core';

/** Visual severity of a toast — maps to a Bootstrap contextual colour. */
export type ToastKind = 'success' | 'danger' | 'warning' | 'info';

/** A transient on-screen notification. */
export interface Toast {
  /** Stable id for `@for` tracking and dismissal. */
  id: number;
  /** Severity → Bootstrap colour. */
  kind: ToastKind;
  /** Already-localised, user-safe text (typically the envelope `message`). */
  text: string;
}

/**
 * Lightweight, dependency-free toast queue rendered by the {@link ToastContainerComponent}.
 *
 * <p>Responsibility: a single, app-wide channel for surfacing the localised envelope `message`
 * (success and error) without pulling in a heavyweight UI library — keeping the bundle small for
 * low-data/low-end clients (PRD §15). The {@link ApiResponseInterceptor} pushes error toasts centrally;
 * feature components push success toasts after a save/delete.</p>
 *
 * <p>Accessibility: the container renders an `aria-live="polite"` region so screen readers announce
 * toasts (WCAG 2.1 AA, CLAUDE.md §5). Toasts auto-dismiss but remain dismissible by keyboard.</p>
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private nextId = 1;

  /** Reactive list of live toasts the container renders. */
  private readonly toastsSignal = signal<Toast[]>([]);

  /** Read-only view of the live toasts. */
  readonly toasts = this.toastsSignal.asReadonly();

  /**
   * Shows a success toast (e.g. after a create/update/delete).
   * @param text already-localised message.
   */
  success(text: string): void {
    this.push('success', text);
  }

  /**
   * Shows an error toast (used centrally by the error interceptor).
   * @param text already-localised, user-safe message (never a raw stack trace — PRD §18).
   */
  error(text: string): void {
    this.push('danger', text, 7000);
  }

  /**
   * Shows an informational toast.
   * @param text already-localised message.
   */
  info(text: string): void {
    this.push('info', text);
  }

  /**
   * Dismisses a toast early.
   * @param id the toast id to remove.
   */
  dismiss(id: number): void {
    this.toastsSignal.update((list) => list.filter((t) => t.id !== id));
  }

  /** Enqueues a toast and schedules its auto-dismissal. */
  private push(kind: ToastKind, text: string, ttlMs = 4000): void {
    const id = this.nextId++;
    this.toastsSignal.update((list) => [...list, { id, kind, text }]);
    setTimeout(() => this.dismiss(id), ttlMs);
  }
}
