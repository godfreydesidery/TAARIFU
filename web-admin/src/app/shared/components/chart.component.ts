import {
  AfterViewInit,
  Component,
  ElementRef,
  Input,
  OnChanges,
  OnDestroy,
  ViewChild,
} from '@angular/core';
import {
  ArcElement,
  BarController,
  BarElement,
  CategoryScale,
  Chart,
  ChartConfiguration,
  ChartData,
  ChartOptions,
  ChartType,
  DoughnutController,
  Legend,
  LinearScale,
  LineController,
  LineElement,
  PointElement,
  Tooltip,
} from 'chart.js';

/**
 * Register ONLY the Chart.js controllers/elements/scales the console uses, once per app load.
 *
 * <p>WHY a manual registry instead of `Chart.register(...registerables)`: registering the full bundle
 * pulls in every chart type (radar, polar, scatter, bubble, financial…) the console never renders,
 * bloating the lazy chunk. Importing the exact pieces lets the bundler tree-shake the rest — a data-cost
 * win on a slow link (PRD §15). Add a controller here when a new chart type is genuinely needed.</p>
 */
Chart.register(
  BarController,
  BarElement,
  LineController,
  LineElement,
  PointElement,
  DoughnutController,
  ArcElement,
  CategoryScale,
  LinearScale,
  Tooltip,
  Legend,
);

/** The chart kinds the dashboard supports (a deliberately small, curated set). */
export type TfChartType = Extract<ChartType, 'bar' | 'line' | 'doughnut'>;

/**
 * A thin, theme-aware, standalone wrapper around a single Chart.js canvas.
 *
 * <p>Responsibility: turn declarative {@link type} + {@link data} (+ optional {@link options}) inputs into
 * a rendered, responsive, accessible chart, and keep it in sync when the inputs change. It owns the
 * Chart.js lifecycle (create on view-init, update on change, destroy on teardown — no leaks) so feature
 * components never touch Chart.js directly. Axis/grid/legend colours are read from the active theme's
 * `--tf-chart-*` CSS custom properties at render time, so charts recolour with the light/dark toggle.</p>
 *
 * <p>Accessibility (WCAG 2.1 AA): the `<canvas>` is decorative to a screen reader, so {@link ariaLabel} is
 * set as `role="img"` + `aria-label`, and callers SHOULD also render the underlying numbers in an adjacent
 * table/legend (the dashboard does) so the data is not chart-only. The canvas sits in a fixed-height
 * `.chart-frame` wrapper supplied by the parent so Chart.js can size responsively without layout jumps.</p>
 *
 * <p>WHY standalone + lazy: lives under `shared/` and is imported only by chart-bearing pages, so Chart.js
 * loads in those lazy chunks, never in the initial bundle (PRD §15 bundle budget).</p>
 */
@Component({
  selector: 'app-chart',
  standalone: true,
  template: `<canvas #canvas role="img" [attr.aria-label]="ariaLabel"></canvas>`,
})
export class ChartComponent implements AfterViewInit, OnChanges, OnDestroy {
  /** The canvas the chart draws into. */
  @ViewChild('canvas', { static: true }) private canvasRef!: ElementRef<HTMLCanvasElement>;

  /** Chart kind. */
  @Input({ required: true }) type!: TfChartType;

  /** Chart.js data (labels + datasets). Colours may be omitted — sensible brand defaults are applied. */
  @Input({ required: true }) data!: ChartData;

  /** Optional Chart.js option overrides merged over the theme-aware defaults. */
  @Input() options?: ChartOptions;

  /** Accessible label describing what the chart shows (e.g. "Reports by status"). */
  @Input() ariaLabel = '';

  /** The live Chart.js instance, or `null` before init / after destroy. */
  private chart: Chart | null = null;

  /** First render once the canvas exists. */
  ngAfterViewInit(): void {
    this.render();
  }

  /** Re-render on any input change (parent swaps data once the API resolves). */
  ngOnChanges(): void {
    if (this.chart) {
      this.render();
    }
  }

  /** Destroy the Chart.js instance to release the canvas + listeners (no memory leak). */
  ngOnDestroy(): void {
    this.chart?.destroy();
    this.chart = null;
  }

  /** Builds (or rebuilds) the chart from the current inputs against the active theme. */
  private render(): void {
    this.chart?.destroy();
    const ctx = this.canvasRef.nativeElement.getContext('2d');
    if (!ctx) {
      return;
    }
    this.chart = new Chart(ctx, {
      type: this.type,
      data: this.data,
      options: this.mergedOptions(),
    } as ChartConfiguration);
  }

  /** Reads a theme role from the stylesheet (falls back to a neutral grey if unset). */
  private cssVar(name: string, fallback: string): string {
    const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    return v || fallback;
  }

  /**
   * Theme-aware defaults merged under the caller's {@link options}. Tooltips on, legend off by default
   * (the dashboard renders its own accessible legend), grid/ticks coloured from the active theme.
   */
  private mergedOptions(): ChartOptions {
    const grid = this.cssVar('--tf-chart-grid', '#e9edf1');
    const tick = this.cssVar('--tf-chart-tick', '#6b7783');
    const base: ChartOptions = {
      responsive: true,
      maintainAspectRatio: false,
      animation: { duration: this.prefersReducedMotion() ? 0 : 400 },
      plugins: {
        legend: { display: false },
        tooltip: {
          backgroundColor: this.cssVar('--tf-text-strong', '#161b1f'),
          padding: 10,
          cornerRadius: 8,
          titleFont: { weight: 600 },
        },
      },
      scales:
        this.type === 'doughnut'
          ? {}
          : {
              x: {
                grid: { display: false },
                border: { color: grid },
                ticks: { color: tick, font: { size: 11 } },
              },
              y: {
                beginAtZero: true,
                grid: { color: grid },
                border: { display: false },
                ticks: { color: tick, font: { size: 11 }, precision: 0 } as Record<string, unknown>,
              },
            },
    };
    return this.deepMerge(base, this.options ?? {});
  }

  /** True when the user asked the OS to reduce motion (we then disable chart animation). */
  private prefersReducedMotion(): boolean {
    return (
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    );
  }

  /** Shallow-recursive merge of plain objects (enough for nested Chart.js option trees). */
  private deepMerge<T extends Record<string, unknown>>(base: T, override: Record<string, unknown>): T {
    const out: Record<string, unknown> = { ...base };
    for (const [key, value] of Object.entries(override)) {
      const existing = out[key];
      if (this.isPlainObject(existing) && this.isPlainObject(value)) {
        out[key] = this.deepMerge(existing as Record<string, unknown>, value as Record<string, unknown>);
      } else {
        out[key] = value;
      }
    }
    return out as T;
  }

  /** Narrow helper: a non-null, non-array object literal. */
  private isPlainObject(v: unknown): v is Record<string, unknown> {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
  }
}

/**
 * The Taarifu brand chart palette (qualitative) — used by feature components to colour datasets
 * consistently. Kept here so every chart shares one ordered palette (DRY) and stays on-brand.
 */
export const TF_CHART_PALETTE = [
  '#0d6e6e', // primary teal
  '#e8920c', // accent amber
  '#1f7bb8', // info blue
  '#1a8754', // success green
  '#c93545', // danger red
  '#7d5ba6', // violet
  '#4fa3a3', // teal-300
  '#c77b00', // warning
] as const;
