import { Component, Input } from '@angular/core';

/**
 * A content-shaped loading placeholder for a data TABLE's body.
 *
 * <p>Responsibility: render N shimmering placeholder rows × M columns inside a `<tbody>`, so a list page
 * shows its eventual shape while data loads instead of a bare spinner. On a slow link this reads as
 * "content is coming" and reduces perceived latency (PRD §15). The shimmer is pure CSS (`.skeleton`) and
 * is disabled under `prefers-reduced-motion` (accessibility).</p>
 *
 * <p>Accessibility: marked `aria-hidden` because it conveys no real content — the surrounding container
 * carries the `role="status"`/"Loading…" announcement for screen readers, so the placeholder is purely
 * visual and must not be read out cell-by-cell.</p>
 *
 * <p>Usage: place INSIDE a `<table>` in lieu of the real `<tbody>` while loading, e.g.
 * `<tbody app-skeleton-table [rows]="6" [cols]="5"></tbody>` — the host renders the rows.</p>
 */
@Component({
  selector: '[app-skeleton-table]',
  standalone: true,
  template: `
    @for (row of rowsArray; track $index) {
      <tr class="skeleton-row" aria-hidden="true">
        @for (col of colsArray; track $index) {
          <td><span class="skeleton skeleton--line-sm" [style.width.%]="widthFor($index)"></span></td>
        }
      </tr>
    }
  `,
  host: { 'aria-hidden': 'true' },
})
export class SkeletonTableComponent {
  /** Number of placeholder rows to render. */
  @Input() rows = 6;

  /** Number of placeholder columns per row. */
  @Input() cols = 5;

  /** Stable arrays for `@for` (avoids re-creating on each CD pass via getters with memo-friendly length). */
  get rowsArray(): number[] {
    return Array.from({ length: Math.max(0, this.rows) });
  }
  get colsArray(): number[] {
    return Array.from({ length: Math.max(1, this.cols) });
  }

  /** Vary placeholder widths a little per column so the skeleton looks organic, not blocky. */
  widthFor(colIndex: number): number {
    const widths = [70, 90, 55, 80, 45, 60];
    return widths[colIndex % widths.length];
  }
}
