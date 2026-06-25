import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

/**
 * A small, accessible status pill for a report's lifecycle status.
 *
 * <p>Responsibility: render a report {@link status} as a colour-coded, TRANSLATED pill. Colour alone never
 * conveys meaning (WCAG 1.4.1) — the localised label is always present. The status key maps to
 * `report.status.<STATUS>` in the i18n dictionaries (Swahili-first). Unknown statuses degrade to a neutral
 * pill showing the raw key so a new backend status never breaks the UI.</p>
 */
@Component({
  selector: 'app-status-pill',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="tz-pill" [style.background]="bg" [style.color]="fg">
      {{ 'report.status.' + status | translate }}
    </span>
  `,
})
export class StatusPillComponent {
  /** The report status name (e.g. `IN_PROGRESS`). */
  @Input({ required: true }) status!: string;

  /** Background colour derived from the status family. */
  protected get bg(): string {
    switch (this.status) {
      case 'RESOLVED':
      case 'CLOSED':
        return 'rgba(11,110,79,0.14)';
      case 'IN_PROGRESS':
      case 'ASSIGNED':
        return 'rgba(28,124,140,0.16)';
      case 'REJECTED':
      case 'DUPLICATE':
        return 'rgba(192,57,43,0.14)';
      case 'TRIAGED':
        return 'rgba(244,168,40,0.18)';
      default:
        return 'rgba(27,33,31,0.08)';
    }
  }

  /** Foreground colour with sufficient contrast against {@link bg}. */
  protected get fg(): string {
    switch (this.status) {
      case 'RESOLVED':
      case 'CLOSED':
        return '#064e38';
      case 'IN_PROGRESS':
      case 'ASSIGNED':
        return '#13525d';
      case 'REJECTED':
      case 'DUPLICATE':
        return '#8e2a20';
      case 'TRIAGED':
        return '#7a5300';
      default:
        return '#1b211f';
    }
  }
}
