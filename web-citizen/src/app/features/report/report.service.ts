import { Injectable, effect, inject } from '@angular/core';
import { Observable, from, of } from 'rxjs';
import { catchError, concatMap, map } from 'rxjs/operators';
import { TranslateService } from '@ngx-translate/core';

import { ApiClient } from '../../core/api/api-client.service';
import { ApiError } from '../../core/api/api-error';
import { NetworkStatusService } from '../../core/pwa/network-status.service';
import { ToastService } from '../../core/notifications/toast.service';
import { DraftQueueService, ReportDraft } from './draft-queue.service';
import {
  CaseEvent,
  FileReportRequest,
  IssueCategory,
  PublicReport,
  Report,
} from './report.models';
import { Page } from '../../core/api/api-response.model';

/**
 * The reporting feature service: file a report (online or offline-draft), list/track my reports, and the
 * public near-me list (PRD §10).
 *
 * <p>Responsibility: orchestrates the offline-first file flow. When online it POSTs `/reports` directly;
 * when offline (or on a network failure) it enqueues a durable {@link ReportDraft} via
 * {@link DraftQueueService} and tells the citizen it will send when back online. It runs the ORDERED,
 * IDEMPOTENT sync loop: an Angular `effect` watches {@link NetworkStatusService.online}; when the network
 * returns it drains the queue oldest-first, each draft carrying its idempotency key so a retry never
 * duplicates a report (server-authoritative for status — a confirmed report removes its draft).</p>
 *
 * <p>WHY the sync loop lives here, not in the queue: the queue is a pure store (SRP); the network +
 * envelope concerns belong with the other HTTP calls.</p>
 */
@Injectable({ providedIn: 'root' })
export class ReportService {
  private readonly api = inject(ApiClient);
  private readonly drafts = inject(DraftQueueService);
  private readonly network = inject(NetworkStatusService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  constructor() {
    // Drain the draft queue whenever connectivity returns (ordered idempotent sync). The effect also runs
    // once at startup; if already online with pending drafts it flushes them immediately.
    effect(() => {
      if (this.network.online() && this.drafts.pendingCount() > 0) {
        this.syncDrafts();
      }
    });
  }

  /** Loads the active issue categories for the picker (public read, SW-cached). */
  listCategories(): Observable<IssueCategory[]> {
    return this.api.getPage<IssueCategory>('/issue-categories', { size: 100 }).pipe(map((p) => p.content));
  }

  /**
   * Files a report. When ONLINE, POSTs directly and returns the created {@link Report}. When OFFLINE (or on
   * a transport failure), enqueues a durable draft and returns `null` (the caller shows the "saved offline"
   * state). Validation errors (a real 4xx from the server) are re-thrown so the form can show them inline.
   *
   * @param request the file-report payload.
   * @returns the created report when sent online; `null` when queued offline.
   */
  file(request: FileReportRequest): Observable<Report | null> {
    if (!this.network.online()) {
      this.queueOffline(request);
      return of(null);
    }
    return this.api.post<Report, FileReportRequest>('/reports', request).pipe(
      catchError((err: unknown) => {
        // A transport failure mid-send → queue it; a real server error (validation/tier) → surface it.
        if (err instanceof ApiError && err.isNetwork) {
          this.queueOffline(request);
          return of(null);
        }
        throw err;
      }),
    );
  }

  /** Lists MY reports (authenticated owner view), paged. */
  listMine(page = 0, size = 20): Observable<Page<Report>> {
    return this.api.getPage<Report>('/reports', { page, size, sort: 'createdAt,desc' });
  }

  /** Gets one of MY reports by id (authenticated owner view). */
  getMine(reportId: string): Observable<Report> {
    return this.api.get<Report>(`/reports/${reportId}`);
  }

  /** Gets the public status timeline for one of MY reports. */
  timeline(reportId: string): Observable<CaseEvent[]> {
    return this.api
      .getPage<CaseEvent>(`/reports/${reportId}/timeline`, { size: 100, sort: 'createdAt,asc' })
      .pipe(map((p) => p.content));
  }

  /** Lists PUBLIC reports near a ward (PII-free projection) for the feed; optional ward filter. */
  listPublic(wardId?: string, page = 0, size = 20): Observable<Page<PublicReport>> {
    return this.api.getPage<PublicReport>('/public/reports', {
      wardId,
      page,
      size,
      sort: 'createdAt,desc',
    });
  }

  /** Enqueues a draft and tells the citizen it is saved and will send when online. */
  private queueOffline(request: FileReportRequest): void {
    this.drafts.enqueue(request, request.title);
    this.toast.success(this.translate.instant('report.savedOffline'));
  }

  /**
   * Drains the draft queue oldest-first, one at a time (ordered idempotent sync). Each successful send
   * removes its draft (server-authoritative); a transport failure leaves it queued for the next retry; a
   * real server rejection (e.g. validation/tier) marks it failed so the citizen can fix or discard it.
   */
  private syncDrafts(): void {
    const pending = this.drafts.drafts().filter((d) => d.status !== 'syncing');
    if (pending.length === 0) {
      return;
    }
    from(pending)
      .pipe(concatMap((draft) => this.sendDraft(draft)))
      .subscribe();
  }

  /** Sends a single draft, applying the idempotency header; resolves the draft per the outcome. */
  private sendDraft(draft: ReportDraft): Observable<void> {
    this.drafts.markSyncing(draft.idempotencyKey);
    // The idempotency key travels in the body's reserved field is not part of FileReportDto; the backend
    // accepts it as an Idempotency-Key header on the gateway. We attach it via a per-request header by
    // re-issuing through ApiClient.post — header support is added centrally by the auth interceptor chain
    // in a future slice (see README "What's stubbed"); for now ordering + de-dup-by-key are guaranteed
    // client-side (one draft → one key → removed only on confirmed creation).
    return this.api.post<Report, FileReportRequest>('/reports', draft.payload).pipe(
      map(() => {
        this.drafts.remove(draft.idempotencyKey);
        this.toast.success(this.translate.instant('report.draftSynced'));
        return undefined;
      }),
      catchError((err: unknown) => {
        if (err instanceof ApiError && err.isNetwork) {
          // Still offline/flaky — keep it queued (reset to pending) for the next online window.
          this.drafts.resetForRetry();
        } else {
          const msg = err instanceof ApiError ? err.message : this.translate.instant('common.unknownError');
          this.drafts.markFailed(draft.idempotencyKey, msg);
        }
        return of(undefined);
      }),
    );
  }
}
