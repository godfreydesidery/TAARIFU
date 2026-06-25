import { Component, EventEmitter, Input, Output } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

/** The kind of state a {@link StatePanelComponent} represents. */
export type StatePanelKind = 'empty' | 'error';

/**
 * A friendly, centred panel for the EMPTY and ERROR states of any list/detail page.
 *
 * <p>Responsibility: one consistent, accessible presentation of "nothing here yet" and "couldn't load",
 * so every feature area stops hand-rolling its own alert. It shows an icon, a title, supporting text, and
 * — for the error kind — a Retry button that emits {@link retry} for the parent to re-query. This makes
 * the empty/offline/error states first-class across the console (a project acceptance criterion), not an
 * afterthought.</p>
 *
 * <p>Accessibility: the panel is a `role="status"` live region (empty) or `role="alert"` (error) so screen
 * readers announce the state change; the Retry control is a real keyboard-reachable `<button>`. All copy is
 * passed in already-localised (callers translate the i18n key) so this atom stays presentational.</p>
 */
@Component({
  selector: 'app-state-panel',
  standalone: true,
  imports: [TranslateModule],
  template: `
    <div class="state-panel" [attr.role]="kind === 'error' ? 'alert' : 'status'" aria-live="polite">
      <div class="state-panel__icon" aria-hidden="true">{{ glyph }}</div>
      <p class="state-panel__title">{{ title }}</p>
      @if (text) {
        <p class="state-panel__text">{{ text }}</p>
      }
      @if (kind === 'error') {
        <button type="button" class="btn btn-outline-primary btn-sm" (click)="retry.emit()">
          {{ 'common.retry' | translate }}
        </button>
      }
    </div>
  `,
})
export class StatePanelComponent {
  /** Whether this is an empty-result or an error state (drives the role + Retry button). */
  @Input() kind: StatePanelKind = 'empty';

  /** A glyph for the state (defaults differ by kind). */
  @Input() icon = '';

  /** Already-localised title (e.g. "No reports found"). */
  @Input({ required: true }) title = '';

  /** Optional already-localised supporting line. */
  @Input() text = '';

  /** Emitted when the user clicks Retry (error kind only). */
  @Output() readonly retry = new EventEmitter<void>();

  /** A sensible default glyph when the caller doesn't supply one. */
  get defaultIcon(): string {
    return this.kind === 'error' ? '⚠' : '—';
  }

  /** The icon to render (caller override, else the kind default). */
  get glyph(): string {
    return this.icon || this.defaultIcon;
  }
}
