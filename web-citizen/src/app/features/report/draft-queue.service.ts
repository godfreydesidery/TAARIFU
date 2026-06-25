import { Injectable, computed, signal } from '@angular/core';

import { FileReportRequest } from './report.models';

/** Lifecycle of a queued offline draft. `pending` → (`syncing`) → removed on success, or `failed`. */
export type DraftStatus = 'pending' | 'syncing' | 'failed';

/** A report drafted offline (or queued while online) awaiting submission to the backend. */
export interface ReportDraft {
  /**
   * Client-generated idempotency key (also the local id). Sent so a retry after an ambiguous network
   * failure does not create a duplicate report server-side (offline-first ordered idempotent sync).
   */
  idempotencyKey: string;
  /** The report payload to POST. */
  payload: FileReportRequest;
  /** A human label for the draft list (the report title). */
  label: string;
  /** Current sync state. */
  status: DraftStatus;
  /** Number of submission attempts so far (drives capped retry/backoff messaging). */
  attempts: number;
  /** When the draft was first queued (epoch ms) — used for ordered FIFO sync + retention. */
  createdAt: number;
  /** Last error message (localised) if the most recent attempt failed; else null. */
  lastError: string | null;
}

/**
 * The offline DRAFT QUEUE for report filing — the heart of the citizen PWA's offline-first guarantee
 * (PRD §15; persona P1 Amina on a flaky 2G link).
 *
 * <p>Responsibility: durably persist reports the citizen submits while offline (or that fail to send),
 * and expose them reactively so the UI can show "saved — will send when online" and a draft badge. It is
 * a pure store + retention policy: the actual network send + ordered sync loop lives in
 * {@link ReportService} (which depends on this), keeping this class free of HTTP concerns (SRP).</p>
 *
 * <p>Design decisions (per the locked offline policy):
 * <ul>
 *   <li><b>Idempotency keys</b> — each draft carries a client-generated key so retrying an ambiguous send
 *       never creates a duplicate report.</li>
 *   <li><b>Ordered FIFO sync</b> — drafts are kept oldest-first so they submit in the order the citizen
 *       created them.</li>
 *   <li><b>Bounded retention</b> — the queue is capped ({@link MAX_DRAFTS}); the oldest is dropped if the
 *       cap is exceeded, so a long offline spell can never exhaust device storage.</li>
 *   <li><b>Client-preserved drafts</b> — drafts persist in `localStorage` across reloads/app re-opens and
 *       are only removed once the server confirms the report (server-authoritative for status).</li>
 * </ul></p>
 */
@Injectable({ providedIn: 'root' })
export class DraftQueueService {
  private static readonly STORAGE_KEY = 'taarifu.citizen.reportDrafts';
  /** Bounded retention: never hold more than this many drafts (oldest dropped first). */
  private static readonly MAX_DRAFTS = 25;

  /** Reactive queue, oldest-first. */
  private readonly draftsSignal = signal<ReportDraft[]>(this.load());

  /** Read-only view of the queued drafts (oldest-first). */
  readonly drafts = this.draftsSignal.asReadonly();

  /** Count of drafts still awaiting a successful send (drives the nav badge). */
  readonly pendingCount = computed(
    () => this.draftsSignal().filter((d) => d.status !== 'syncing').length,
  );

  /**
   * Enqueues a new draft (e.g. the citizen tapped "Send" while offline, or a send failed). Generates an
   * idempotency key and appends FIFO, enforcing the retention cap.
   *
   * @param payload the report request to send later.
   * @param label a short label (the report title) for the draft list.
   * @returns the created {@link ReportDraft}.
   */
  enqueue(payload: FileReportRequest, label: string): ReportDraft {
    const draft: ReportDraft = {
      idempotencyKey: this.newKey(),
      payload,
      label,
      status: 'pending',
      attempts: 0,
      createdAt: Date.now(),
      lastError: null,
    };
    this.draftsSignal.update((list) => this.applyCap([...list, draft]));
    this.persist();
    return draft;
  }

  /** Returns the next draft eligible for sending (oldest non-`syncing`), or null when none. */
  next(): ReportDraft | null {
    return this.draftsSignal().find((d) => d.status !== 'syncing') ?? null;
  }

  /** Marks a draft as currently sending (so the sync loop won't pick it twice). */
  markSyncing(key: string): void {
    this.patch(key, (d) => ({ ...d, status: 'syncing', attempts: d.attempts + 1 }));
  }

  /**
   * Removes a draft after the server CONFIRMS the report was created (server-authoritative for status).
   * @param key the draft's idempotency key.
   */
  remove(key: string): void {
    this.draftsSignal.update((list) => list.filter((d) => d.idempotencyKey !== key));
    this.persist();
  }

  /**
   * Marks a draft failed so it stays in the queue for the next online retry (client-preserved drafts).
   * @param key the draft's idempotency key.
   * @param error the localised error message to show on the draft.
   */
  markFailed(key: string, error: string): void {
    this.patch(key, (d) => ({ ...d, status: 'failed', lastError: error }));
  }

  /** Resets every `syncing`/`failed` draft back to `pending` (e.g. when the network returns). */
  resetForRetry(): void {
    this.draftsSignal.update((list) =>
      list.map((d) => (d.status === 'pending' ? d : { ...d, status: 'pending' })),
    );
    this.persist();
  }

  /** Applies a patch to one draft by key and persists. */
  private patch(key: string, fn: (d: ReportDraft) => ReportDraft): void {
    this.draftsSignal.update((list) => list.map((d) => (d.idempotencyKey === key ? fn(d) : d)));
    this.persist();
  }

  /** Enforces the retention cap by dropping the oldest drafts. */
  private applyCap(list: ReportDraft[]): ReportDraft[] {
    return list.length <= DraftQueueService.MAX_DRAFTS
      ? list
      : list.slice(list.length - DraftQueueService.MAX_DRAFTS);
  }

  /** Generates an idempotency key (UUID v4 where supported, else a timestamp+random fallback). */
  private newKey(): string {
    if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
      return crypto.randomUUID();
    }
    return `draft-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  }

  /** Loads the persisted queue, tolerating a corrupt/absent store by returning an empty list. */
  private load(): ReportDraft[] {
    try {
      const raw = localStorage.getItem(DraftQueueService.STORAGE_KEY);
      if (!raw) {
        return [];
      }
      const parsed = JSON.parse(raw) as ReportDraft[];
      // On reload, any draft left mid-`syncing` is reset to pending so it retries.
      return parsed.map((d) => (d.status === 'syncing' ? { ...d, status: 'pending' } : d));
    } catch {
      return [];
    }
  }

  /** Persists the current queue to `localStorage`. */
  private persist(): void {
    try {
      localStorage.setItem(DraftQueueService.STORAGE_KEY, JSON.stringify(this.draftsSignal()));
    } catch {
      // Storage full/blocked — drafts remain in memory for this session; nothing else we can do safely.
    }
  }
}
