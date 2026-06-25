import { Component, Input } from '@angular/core';

/** The semantic tone of a status badge — maps to a `.badge-soft.is-*` variant. */
export type StatusTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral' | 'primary';

/**
 * A soft, pill-shaped status chip with a leading dot — the console's default badge for lifecycle/status
 * values (report status, responder status, verification, etc.).
 *
 * <p>Responsibility: present an already-localised status {@link label} in a calm pastel chip whose colour
 * is chosen by {@link tone}, so status colours are consistent and accessible across the app (the pastel
 * `.badge-soft` variants meet AA contrast on their tints; defined once in the design system). Callers map
 * their domain status → a tone and pass the translated label, keeping this atom presentational.</p>
 */
@Component({
  selector: 'app-status-badge',
  standalone: true,
  template: `
    <span class="badge-soft" [class]="'badge-soft is-' + tone">
      <span class="dot" aria-hidden="true"></span>{{ label }}
    </span>
  `,
})
export class StatusBadgeComponent {
  /** Semantic tone → colour variant. */
  @Input() tone: StatusTone = 'neutral';

  /** Already-localised status text. */
  @Input({ required: true }) label = '';
}
