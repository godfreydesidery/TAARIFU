import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';
import { ToastService } from '../../core/notifications/toast.service';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { StatePanelComponent } from '../../shared/components/state-panel.component';
import { SkeletonTableComponent } from '../../shared/components/skeleton.component';
import { StatusBadgeComponent } from '../../shared/components/status-badge.component';
import { statusTone } from '../../shared/util/status-tone.util';
import { Organisation } from './responders.models';
import { RespondersService } from './responders.service';

/**
 * Responder organisations admin list with verify toggle + create/detail links (PRD §24, D20).
 *
 * <p>Responsibility: lists all responding organisations paged, links to create/detail, and toggles
 * verification (the §24.4 go-live gate) inline with a confirm + success toast. Verification is a
 * privileged, separately-audited act enforced SERVER-side; the toggle here is a convenience. Loading/
 * empty/error states are handled; subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-organisations-list',
  standalone: true,
  imports: [
    RouterLink,
    TranslateModule,
    PaginationComponent,
    StatePanelComponent,
    SkeletonTableComponent,
    StatusBadgeComponent,
  ],
  templateUrl: './organisations-list.component.html',
})
export class OrganisationsListComponent implements OnInit {
  private readonly responders = inject(RespondersService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** List UI state. */
  readonly rows = signal<Organisation[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  private readonly pageSize = 20;

  /** Loads the first page on init. */
  ngOnInit(): void {
    this.loadPage(0);
  }

  /**
   * Loads a page of organisations.
   * @param page zero-based page index.
   */
  loadPage(page: number): void {
    this.loading.set(true);
    this.errored.set(false);
    this.responders
      .listOrganisations({ page, size: this.pageSize, sort: 'name,asc' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.rows.set(result.content);
          this.meta.set(result.meta);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });
  }

  /**
   * Toggles an organisation's verified state after a confirm, then reloads the page.
   * @param org the organisation to (un)verify.
   */
  toggleVerified(org: Organisation): void {
    const next = !org.verified;
    const confirmKey = next ? 'responders.confirmVerify' : 'responders.confirmUnverify';
    if (!confirm(this.translate.instant(confirmKey))) {
      return;
    }
    this.responders
      .setVerified(org.id, next)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('common.saved'));
          this.loadPage(this.meta()?.page ?? 0);
        },
        error: () => undefined,
      });
  }

  /** Maps an organisation status token to a badge tone (shared design-system mapping). */
  tone = statusTone;
}
