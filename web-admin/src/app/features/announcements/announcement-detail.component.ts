import { DatePipe } from '@angular/common';
import { Component, DestroyRef, Input, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { ApiError } from '../../core/api/api-error';
import { LocaleService } from '../../core/i18n/locale.service';
import { Announcement } from './announcements.models';
import { AnnouncementsService } from './announcements.service';

/**
 * Announcement detail — the full read of one published civic announcement (PRD §12, §22.6, M4; `GET
 * /announcements/{id}`).
 *
 * <p>Responsibility: render a single announcement's headline, locale-appropriate body (SW-first, EN
 * fallback — ADR-0010), status, author/category references, targeted areas, delivery channels, and the
 * publish/expiry window. The id comes from the route ({@code /announcements/:announcementId}, bound via
 * component-input-binding). Visibility is enforced SERVER-side: the endpoint returns only PUBLISHED,
 * in-window announcements; a draft/scheduled/expired/held/unknown id 404s and is shown here as a neutral
 * "not available" empty state — never leaking why (PRD §18). Subscriptions use {@link takeUntilDestroyed}.</p>
 *
 * <p>WHY locale-aware body selection in the view: the API returns both {@code bodySw} and {@code bodyEn};
 * the recipient's active locale decides which to show, falling back to Swahili (the always-present body)
 * so an English-locale reader never sees a blank where {@code bodyEn} was omitted.</p>
 */
@Component({
  selector: 'app-announcement-detail',
  standalone: true,
  imports: [RouterLink, DatePipe, TranslateModule],
  templateUrl: './announcement-detail.component.html',
})
export class AnnouncementDetailComponent implements OnInit {
  private readonly announcements = inject(AnnouncementsService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly locale = inject(LocaleService);

  /** The announcement public id from the route (`/announcements/:announcementId`). */
  @Input() announcementId = '';

  /** Detail UI state. */
  readonly announcement = signal<Announcement | null>(null);
  readonly loading = signal(false);
  /** True for a generic load failure (network/server) — distinct from the 404 "not available" state. */
  readonly errored = signal(false);
  /** True when the server returned 404 — the announcement is not publicly available (or never existed). */
  readonly notAvailable = signal(false);

  /** The active locale tag for date formatting (sw-TZ / en-TZ). */
  readonly localeTag = computed(() => (this.locale.locale() === 'sw' ? 'sw-TZ' : 'en-TZ'));

  /**
   * The body to display for the active locale: English body when the locale is English AND an English body
   * exists; otherwise the Swahili body (the always-present, SW-first default). Implements the SW→EN→ (here
   * SW-as-floor) language fallback for the content itself (ADR-0010).
   */
  readonly localisedBody = computed<string>(() => {
    const a = this.announcement();
    if (!a) {
      return '';
    }
    if (this.locale.locale() === 'en' && a.bodyEn) {
      return a.bodyEn;
    }
    return a.bodySw;
  });

  /** Loads the announcement on init. */
  ngOnInit(): void {
    this.load();
  }

  /** Fetches the announcement, branching the 404 (not available) state from a transport/server error. */
  load(): void {
    this.loading.set(true);
    this.errored.set(false);
    this.notAvailable.set(false);
    this.announcements
      .get(this.announcementId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (announcement) => {
          this.announcement.set(announcement);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          // A 404 means "not a publicly visible announcement" (drafts/expired/held/unknown all 404 alike,
          // PRD §18) — show a neutral empty state, NOT a scary error. Anything else is a real load failure.
          if (error instanceof ApiError && error.statusCode === 404) {
            this.notAvailable.set(true);
          } else {
            this.errored.set(true);
          }
        },
      });
  }
}
