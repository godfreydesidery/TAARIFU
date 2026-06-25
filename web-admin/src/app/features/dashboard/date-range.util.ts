/**
 * Date-range helpers for the analytics dashboard's date-range picker + drill-down (PRD §3.3 KPIs, M15).
 *
 * <p>ONE place that turns a quick-range token into the inclusive `from` / exclusive `to` ISO-8601 instants
 * the analytics endpoints accept, and clamps a custom range. Centralising this keeps the dashboard component
 * thin and stops the window maths drifting (DRY, CLAUDE.md §3). All instants are UTC ISO-8601 to match the
 * backend analytics contract (`from`/`to` are UTC, see `analytics.models.ts`).</p>
 */

/** The selectable quick ranges plus a `CUSTOM` escape hatch for the two date inputs. */
export const QUICK_RANGES = ['7D', '30D', '90D', 'CUSTOM'] as const;

/** One quick-range token. */
export type QuickRange = (typeof QUICK_RANGES)[number];

/** A resolved analytics window — the `from`/`to` params threaded into every analytics call. */
export interface DateWindow {
  /** Inclusive window start (ISO-8601 UTC), or `undefined` to let the server default. */
  from?: string;
  /** Exclusive window end (ISO-8601 UTC), or `undefined` to let the server default (now). */
  to?: string;
}

/** The number of trailing days a non-custom quick range covers. */
const RANGE_DAYS: Record<Exclude<QuickRange, 'CUSTOM'>, number> = { '7D': 7, '30D': 30, '90D': 90 };

/**
 * Resolves a quick range (or a custom from/to pair) into a UTC {@link DateWindow}.
 *
 * @param range the selected quick range.
 * @param customFrom the custom-start `yyyy-MM-dd` (only used when `range === 'CUSTOM'`).
 * @param customTo the custom-end `yyyy-MM-dd` (only used when `range === 'CUSTOM'`); end-of-day inclusive.
 * @returns the resolved window; for `CUSTOM` with missing ends, those ends are left `undefined` (server default).
 */
export function resolveWindow(range: QuickRange, customFrom?: string, customTo?: string): DateWindow {
  if (range === 'CUSTOM') {
    return {
      from: customFrom ? new Date(`${customFrom}T00:00:00Z`).toISOString() : undefined,
      // Make the custom end inclusive of the chosen day by advancing to its end-of-day.
      to: customTo ? new Date(`${customTo}T23:59:59Z`).toISOString() : undefined,
    };
  }
  const days = RANGE_DAYS[range];
  const to = new Date();
  const from = new Date(to.getTime() - days * 24 * 60 * 60 * 1000);
  return { from: from.toISOString(), to: to.toISOString() };
}
