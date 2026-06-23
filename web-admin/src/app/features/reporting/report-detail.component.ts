import { Component, DestroyRef, Input, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/notifications/toast.service';
import { CaseEvent, PublicReport } from './reporting.models';
import { ReportingService } from './reporting.service';

/**
 * Report case detail — header + timeline + status actions (PRD Epic M3, §12.1; UC-D11/D21).
 *
 * <p>Responsibility: shows one report's public case view, its event timeline, and the responder lifecycle
 * actions a moderator/admin can take (assign a responder, start work, resolve with a note, escalate). The
 * state machine and every transition's legality are owned by the SERVER (reporting's §12.1 port); an
 * illegal transition surfaces as a CONFLICT toast from the interceptor. The reporter id and precise geo
 * are never shown here — this view reads the PII-free public projection (PDPA, PRD §25.3). Subscriptions
 * use {@link takeUntilDestroyed}.</p>
 *
 * <p>WHY actions live on a detail page (not the list): a status change is a deliberate, audited act that
 * needs context (the case + its timeline) and, for resolve/escalate, a typed note — surfacing them only
 * after the operator has opened the case reduces mis-clicks on a dense queue.</p>
 */
@Component({
  selector: 'app-report-detail',
  standalone: true,
  imports: [RouterLink, FormsModule, DatePipe, TranslateModule],
  templateUrl: './report-detail.component.html',
})
export class ReportDetailComponent implements OnInit {
  private readonly reporting = inject(ReportingService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);

  /** The report public id from the route (`/reports/:reportId`). */
  @Input() reportId = '';

  /** Detail + timeline UI state. */
  readonly report = signal<PublicReport | null>(null);
  readonly events = signal<CaseEvent[]>([]);
  readonly loading = signal(false);
  readonly errored = signal(false);
  readonly acting = signal(false);

  /** Action form fields. */
  readonly responderIdInput = signal('');
  readonly resolutionNote = signal('');
  readonly escalationReason = signal('');

  /** Loads the report + timeline on init. */
  ngOnInit(): void {
    this.load();
  }

  /** Loads the report header and its public timeline. */
  load(): void {
    this.loading.set(true);
    this.errored.set(false);
    this.reporting
      .getPublic(this.reportId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (report) => {
          this.report.set(report);
          this.loading.set(false);
          this.loadTimeline();
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });
  }

  /** Loads the case timeline (best-effort; failure is non-fatal to the header). */
  private loadTimeline(): void {
    this.reporting
      .getTimeline(this.reportId, { page: 0, size: 50, sort: 'createdAt,asc' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => this.events.set(result.content),
        error: () => this.events.set([]),
      });
  }

  /** Assigns the entered responder to the case (→ ASSIGNED). No-ops without a responder id. */
  assign(): void {
    const responderId = this.responderIdInput().trim();
    if (!responderId || this.acting()) {
      return;
    }
    this.runAction(this.reporting.assign(this.reportId, { responderId }), 'reports.assigned');
  }

  /** Starts work on the case (→ IN_PROGRESS). */
  start(): void {
    if (this.acting()) {
      return;
    }
    this.runAction(this.reporting.start(this.reportId), 'reports.started');
  }

  /** Resolves the case with the required note (→ RESOLVED). No-ops without a note. */
  resolve(): void {
    const note = this.resolutionNote().trim();
    if (!note || this.acting()) {
      return;
    }
    this.runAction(this.reporting.resolve(this.reportId, { resolutionNote: note }), 'reports.resolved');
  }

  /** Escalates the case to a supervisor (→ ESCALATED). */
  escalate(): void {
    if (this.acting()) {
      return;
    }
    const reason = this.escalationReason().trim() || undefined;
    this.runAction(this.reporting.escalate(this.reportId, { reason }), 'reports.escalated');
  }

  /** Returns to the queue. */
  back(): void {
    void this.router.navigate(['/reports']);
  }

  /**
   * Runs a lifecycle action, then reloads the case + timeline and toasts success. Errors (incl. an
   * illegal transition CONFLICT) are toasted centrally by the interceptor.
   * @param action$ the lifecycle call.
   * @param successKey the i18n key for the success toast.
   */
  private runAction(action$: ReturnType<ReportingService['start']>, successKey: string): void {
    this.acting.set(true);
    action$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.acting.set(false);
        this.toast.success(this.translate.instant(successKey));
        this.responderIdInput.set('');
        this.resolutionNote.set('');
        this.escalationReason.set('');
        this.load();
      },
      error: () => this.acting.set(false),
    });
  }
}
