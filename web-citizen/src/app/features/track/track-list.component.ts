import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { finalize } from 'rxjs/operators';

import { DraftQueueService } from '../report/draft-queue.service';
import { Report } from '../report/report.models';
import { ReportService } from '../report/report.service';
import { StatusPillComponent } from '../../shared/status-pill.component';

/**
 * Track my reports — the list of the citizen's own reports plus any offline DRAFTS awaiting sync
 * (PRD §10 US-3.2).
 *
 * <p>Responsibility: show the citizen everything they have filed and everything still queued. Pending
 * drafts ({@link DraftQueueService}) render FIRST with a clear "will send when online / failed" state so
 * nothing the citizen wrote is ever invisible (offline-first trust). Submitted reports render as tappable
 * cards with a status pill, linking to the status timeline. Empty/error/offline states are all handled.</p>
 */
@Component({
  selector: 'app-track-list',
  standalone: true,
  imports: [RouterLink, TranslateModule, DatePipe, StatusPillComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './track-list.component.html',
  styleUrl: './track-list.component.scss',
})
export class TrackListComponent {
  private readonly reports = inject(ReportService);
  /** The offline draft queue (rendered at the top of the list). */
  protected readonly drafts = inject(DraftQueueService);

  /** The citizen's submitted reports. */
  protected readonly reportsList = signal<Report[]>([]);
  /** True while loading. */
  protected readonly loading = signal(true);
  /** True if the load failed. */
  protected readonly errored = signal(false);

  constructor() {
    this.load();
  }

  /** Loads (or reloads) the citizen's reports. */
  protected load(): void {
    this.loading.set(true);
    this.errored.set(false);
    this.reports
      .listMine()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (page) => this.reportsList.set(page.content),
        error: () => this.errored.set(true),
      });
  }
}
