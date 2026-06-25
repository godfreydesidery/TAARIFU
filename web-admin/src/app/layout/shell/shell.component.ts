import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map, startWith } from 'rxjs/operators';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { AuthService } from '../../core/auth/auth.service';
import { LocaleService, SupportedLocale } from '../../core/i18n/locale.service';
import { ToastService } from '../../core/notifications/toast.service';
import { ThemeService } from '../../core/theme/theme.service';
import { SearchBoxComponent } from '../../features/search/search-box.component';

/** A single sidebar navigation item. */
interface NavItem {
  /** Router path to navigate to. */
  path: string;
  /** i18n key for the label. */
  labelKey: string;
  /** Decorative glyph (icon+label nav for low-literacy accessibility, CLAUDE.md §5). */
  icon: string;
  /** Visibility predicate against the user's roles (UI hint only — server gates). */
  show: () => boolean;
}

/** A labelled group of nav items in the sidebar. */
interface NavSection {
  /** i18n key for the section heading. */
  labelKey: string;
  /** The items in this section. */
  items: NavItem[];
}

/** One breadcrumb crumb derived from the active route. */
interface Crumb {
  /** Already-localised label. */
  label: string;
  /** Router link, or `null` for the current (non-link) crumb. */
  link: string | null;
}

/**
 * The authenticated admin SHELL: an elegant fixed sidebar + topbar (breadcrumbs, theme/locale/logout)
 * + routed content area (CLAUDE.md §5; PRD §14 admin console).
 *
 * <p>Responsibility: the persistent chrome around every feature page. It hosts the brand, a SECTIONED
 * icon+label navigation (grouped Overview / Operations / Civic data / Administration so the growing menu
 * stays scannable), breadcrumbs derived from the active route, the light/dark theme toggle, the SW/EN
 * language switch, and logout — rendering the active feature via {@link RouterOutlet}. Nav sections and
 * items are role-gated as UI hints (the server is the real authz gate). The sidebar collapses to an
 * off-canvas drawer on small screens so the console is usable on a low-end phone (PRD §15).</p>
 *
 * <p>Accessibility (WCAG 2.1 AA): the menu toggle is a real `<button>` with `aria-expanded`/`aria-controls`;
 * nav items expose `aria-current` via `routerLinkActive`; breadcrumbs are a labelled `<nav>`; the theme and
 * language controls are labelled; a skip-to-content link is provided; all labels come from i18n.</p>
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TranslateModule, SearchBoxComponent],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent {
  private readonly auth = inject(AuthService);
  private readonly localeService = inject(LocaleService);
  private readonly themeService = inject(ThemeService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  /** Whether the sidebar drawer is open (mobile). Static column on desktop regardless. */
  readonly sidenavOpen = signal(false);

  /** The active locale, surfaced for the topbar `<select>` binding. */
  readonly locale = this.localeService.locale;

  /** The active theme, surfaced for the topbar toggle's icon/label. */
  readonly theme = this.themeService.theme;

  /** The current session (greeting + role-gated nav), read from {@link AuthService}. */
  readonly session = this.auth.session;

  /** ROOT/ADMIN see the Administration section (UI hint — server gates). */
  readonly isAdmin = computed(() => {
    const roles = this.session()?.roles ?? [];
    return roles.includes('ROOT') || roles.includes('ADMIN');
  });

  /** ROOT/ADMIN/MODERATOR see the Operations section (UI hint — server gates). */
  readonly canModerate = computed(() => {
    const roles = this.session()?.roles ?? [];
    return roles.includes('ROOT') || roles.includes('ADMIN') || roles.includes('MODERATOR');
  });

  /**
   * The sectioned navigation model. Declared once (DRY) so the template stays a thin loop and adding a
   * nav item is a one-line data change. `show` predicates are UI hints mirroring the backend RoleHierarchy.
   */
  readonly sections: NavSection[] = [
    {
      labelKey: 'nav.sectionOverview',
      items: [{ path: '/dashboard', labelKey: 'nav.dashboard', icon: '◧', show: () => true }],
    },
    {
      labelKey: 'nav.sectionOperations',
      items: [
        { path: '/reports', labelKey: 'nav.reports', icon: '✉', show: () => this.canModerate() },
        { path: '/moderation', labelKey: 'nav.moderation', icon: '⚑', show: () => this.canModerate() },
        { path: '/responders', labelKey: 'nav.responders', icon: '⚙', show: () => this.canModerate() },
      ],
    },
    {
      labelKey: 'nav.sectionCivic',
      items: [
        { path: '/geography/regions', labelKey: 'nav.geography', icon: '🗺', show: () => true },
        { path: '/representatives', labelKey: 'nav.representatives', icon: '♟', show: () => true },
        { path: '/parties', labelKey: 'nav.parties', icon: '⚑', show: () => true },
        { path: '/announcements', labelKey: 'nav.announcements', icon: '📢', show: () => true },
      ],
    },
    {
      labelKey: 'nav.sectionAdmin',
      items: [
        { path: '/issue-categories', labelKey: 'nav.issueCategories', icon: '✎', show: () => this.isAdmin() },
        { path: '/institutions/parliaments', labelKey: 'nav.institutions', icon: '⚖', show: () => this.isAdmin() },
        { path: '/tokens', labelKey: 'nav.tokens', icon: '●', show: () => this.isAdmin() },
        { path: '/payments', labelKey: 'nav.payments', icon: '₮', show: () => this.isAdmin() },
        { path: '/users', labelKey: 'nav.users', icon: '☺', show: () => this.isAdmin() },
        { path: '/privacy', labelKey: 'nav.privacy', icon: '🛡', show: () => this.isAdmin() },
      ],
    },
  ];

  /** Sections with at least one visible item — keeps empty groups out of the rendered nav. */
  readonly visibleSections = computed(() =>
    this.sections
      .map((s) => ({ ...s, items: s.items.filter((i) => i.show()) }))
      .filter((s) => s.items.length > 0),
  );

  /** Breadcrumbs derived from the active route's deepest title, re-computed on every navigation. */
  readonly crumbs = signal<Crumb[]>([]);

  constructor() {
    // Recompute breadcrumbs on each completed navigation (and once at startup).
    this.router.events
      .pipe(
        filter((e): e is NavigationEnd => e instanceof NavigationEnd),
        startWith(null),
        map(() => this.buildCrumbs()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((crumbs) => this.crumbs.set(crumbs));
  }

  /** Toggles the mobile sidebar drawer. */
  toggleSidenav(): void {
    this.sidenavOpen.update((open) => !open);
  }

  /** Toggles light/dark theme from the topbar. */
  toggleTheme(): void {
    this.themeService.toggle();
  }

  /**
   * Switches the UI language from the topbar control.
   * @param value the chosen locale code.
   */
  onLocaleChange(value: string): void {
    this.localeService.use(value as SupportedLocale);
  }

  /** Logs out and returns to login. Always clears local state even if the network is down. */
  logout(): void {
    this.auth.logout().subscribe({
      next: () => this.afterLogout(),
      error: () => this.afterLogout(),
    });
  }

  /** Closes the drawer after a nav click on mobile (keeps content visible on a small screen). */
  onNavigate(): void {
    if (window.matchMedia('(max-width: 767.98px)').matches) {
      this.sidenavOpen.set(false);
    }
  }

  /**
   * Builds the breadcrumb trail from the active route. The shell ("Admin Console") is always the root
   * crumb; the current page label comes from the matched nav item (so it is already localised + consistent
   * with the sidebar), falling back to the route's first URL segment.
   */
  private buildCrumbs(): Crumb[] {
    const url = this.router.url.split('?')[0];
    const root: Crumb = { label: this.translate.instant('app.shortTitle'), link: '/dashboard' };
    if (url === '/' || url === '/dashboard') {
      return [{ ...root, link: null }];
    }
    const match = this.sections
      .flatMap((s) => s.items)
      .find((i) => url === i.path || url.startsWith(i.path + '/'));
    const current: Crumb = {
      label: match ? this.translate.instant(match.labelKey) : this.firstSegmentLabel(url),
      link: null,
    };
    return [root, current];
  }

  /** Humanises the first URL segment as a last-resort crumb label (e.g. `/foo/bar` → "Foo"). */
  private firstSegmentLabel(url: string): string {
    const seg = url.split('/').filter(Boolean)[0] ?? '';
    return seg ? seg.charAt(0).toUpperCase() + seg.slice(1).replace(/-/g, ' ') : '';
  }

  /** Shared post-logout routine: toast + redirect to login. */
  private afterLogout(): void {
    this.toast.info(this.translate.instant('auth.loggedOut'));
    void this.router.navigate(['/login']);
  }
}
