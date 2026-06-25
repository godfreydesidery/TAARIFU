import { ChangeDetectionStrategy, Component, Input, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { forkJoin } from 'rxjs';
import { finalize } from 'rxjs/operators';

import { CaseEvent, Report } from '../report/report.models';
import { ReportService } from '../report/report.service';
import { StatusPillComponent } from '../../shared/status-pill.component';

/**
 * Track a single report — its current status and the public case TIMELINE (PRD §10 US-3.2).
 *
 * <p>Responsibility: render one of the citizen's reports in detail and the chronological list of public
 * case events (filed → triaged → assigned → in progress → resolved …). The {@link reportId} route param is
 * bound via the router's component-input-binding. Report + timeline are fetched in parallel (low latency on
 * a slow link). The timeline is a vertical stepper that is keyboard- and screen-reader-friendly. Internal
 * events never reach the citizen (the backend filters them; we render only what we receive).</p>
 */
@Component({
  selector: 'app-track-detail',
  standalone: true,
  imports: [RouterLink, TranslateModule, DatePipe, StatusPillComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './track-detail.component.html',
  styleUrl: './track-detail.component.scss',
})
export class TrackDetailComponent {
  private readonly reports = inject(ReportService);

  /** The report's public id, bound from the route param `:reportId`. */
  @Input({ required: true })
  set reportId(value: string) {
    this.load(value);
  }

  /** The loaded report, or null before load. */
  protected readonly report = signal<Report | null>(null);
  /** The case timeline (public events), oldest-first. */
  protected readonly timeline = signal<CaseEvent[]>([]);
  /** True while loading. */
  protected readonly loading = signal(true);
  /** True if the load failed. */
  protected readonly errored = signal(false);

  /** Loads the report + its timeline in parallel. */
  private load(reportId: string): void {
    this.loading.set(true);
    this.errored.set(false);
    forkJoin({
      report: this.reports.getMine(reportId),
      timeline: this.reports.timeline(reportId),
    })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: ({ report, timeline }) => {
          this.report.set(report);
          this.timeline.set(timeline);
        },
        error: () => this.errored.set(true),
      });
  }
}
