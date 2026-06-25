import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { finalize } from 'rxjs/operators';

import { FeedBundle, FeedService } from './feed.service';
import { StatusPillComponent } from '../../shared/status-pill.component';

/**
 * The citizen home feed — public reports near the citizen and active petitions (PRD §11/§12, discovery).
 *
 * <p>Responsibility: render an elegant, low-data card feed of public civic activity that ANY visitor
 * (guest or signed-in) can browse, and that renders from the SW read-cache when offline. Shows skeleton
 * loaders while fetching, an empty state when there is nothing, and an error/offline state with a retry.
 * Cards are mobile-first and tappable; reports link to their public detail (future slice) and petitions
 * show signature progress. Leads the citizen toward the primary action (file a report) via the shell FAB.</p>
 */
@Component({
  selector: 'app-feed',
  standalone: true,
  imports: [RouterLink, TranslateModule, DatePipe, StatusPillComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './feed.component.html',
  styleUrl: './feed.component.scss',
})
export class FeedComponent {
  private readonly feed = inject(FeedService);

  /** The loaded feed bundle, or null before first load. */
  protected readonly bundle = signal<FeedBundle | null>(null);
  /** True while loading (drives skeletons). */
  protected readonly loading = signal(true);
  /** True if the load failed (drives the retry state). */
  protected readonly errored = signal(false);

  constructor() {
    this.load();
  }

  /** Loads (or reloads) the home feed. */
  protected load(): void {
    this.loading.set(true);
    this.errored.set(false);
    this.feed
      .loadHomeFeed()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (bundle) => this.bundle.set(bundle),
        error: () => this.errored.set(true),
      });
  }

  /** Percentage progress for a petition's signatures (clamped 0–100) for the progress bar. */
  protected progress(count: number, goal: number): number {
    if (goal <= 0) {
      return 0;
    }
    return Math.min(100, Math.round((count / goal) * 100));
  }

  /** Skeleton placeholder rows for the loading state. */
  protected readonly skeletons = [0, 1, 2];
}
