import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { NetworkStatusService } from '../core/pwa/network-status.service';
import { DraftQueueService } from '../features/report/draft-queue.service';

/**
 * A slim banner that tells the citizen when they are offline and/or have drafts waiting to send.
 *
 * <p>Responsibility: surface the offline-first state honestly (PRD §15) so the citizen trusts that a report
 * filed on a dead 2G link is not lost. Shows an offline notice when disconnected and a "N drafts will send
 * when online" notice while any draft is queued. Uses `role="status"` (polite) so it is announced without
 * interrupting. Hidden entirely when online with no pending drafts (zero visual cost).</p>
 */
@Component({
  selector: 'app-offline-banner',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (network.offline()) {
      <div class="ob ob--offline" role="status">
        <span aria-hidden="true">●</span> {{ 'offline.youAreOffline' | translate }}
      </div>
    } @else if (drafts.pendingCount() > 0) {
      <div class="ob ob--pending" role="status">
        {{ 'offline.draftsPending' | translate: { count: drafts.pendingCount() } }}
      </div>
    }
  `,
  styles: [
    `
      .ob {
        text-align: center;
        font-size: 0.85rem;
        padding: 0.35rem 0.75rem;
        font-weight: 600;
      }
      .ob--offline {
        background: #4a2f2c;
        color: #ffd9d4;
      }
      .ob--pending {
        background: rgba(244, 168, 40, 0.18);
        color: #7a5300;
      }
    `,
  ],
})
export class OfflineBannerComponent {
  /** Network status (online/offline) — drives the offline notice. */
  protected readonly network = inject(NetworkStatusService);
  /** Draft queue — drives the "drafts pending" notice. */
  protected readonly drafts = inject(DraftQueueService);
}
