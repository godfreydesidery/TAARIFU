import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { AuthService } from '../../core/auth/auth.service';
import { LocaleService, SupportedLocale } from '../../core/i18n/locale.service';
import { ToastService } from '../../core/notifications/toast.service';

/**
 * The authenticated admin SHELL: a responsive header + collapsible sidenav + routed content area
 * (CLAUDE.md §5 layout requirement).
 *
 * <p>Responsibility: the persistent chrome around every feature page. It hosts the brand/title, the
 * language switch (SW/EN), the logout action, and the navigation sidenav, and renders the active feature
 * via {@link RouterOutlet}. The sidenav collapses on small screens (Bootstrap offcanvas-style toggle held
 * in a signal) so the console is usable on a low-end phone (PRD §15). Navigation uses icon+label items
 * for low-literacy accessibility (CLAUDE.md §5).</p>
 *
 * <p>Accessibility (WCAG 2.1 AA): the sidenav toggle is a real `<button>` with `aria-expanded`/
 * `aria-controls`; nav items expose `aria-current` via `routerLinkActive`; the language control is a
 * labelled `<select>`; skip-to-content is provided in the template. All labels come from i18n.</p>
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TranslateModule],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent {
  private readonly auth = inject(AuthService);
  private readonly localeService = inject(LocaleService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** Whether the sidenav is open (true on desktop by default; toggled on mobile). */
  readonly sidenavOpen = signal(true);

  /** The active locale, surfaced for the header `<select>` binding. */
  readonly locale = this.localeService.locale;

  /** The current session (for greeting + role-gated nav), read from {@link AuthService}. */
  readonly session = this.auth.session;

  /**
   * Whether the current user may see Admin-only nav (issue-category CRUD, etc.). UI hint only — server gates.
   * Honours the role hierarchy: ROOT implies ADMIN (mirrors the backend RoleHierarchy ROOT > ADMIN > MODERATOR).
   */
  readonly isAdmin = computed(() => {
    const roles = this.session()?.roles ?? [];
    return roles.includes('ROOT') || roles.includes('ADMIN');
  });

  /**
   * Whether the user may see Moderator/Admin nav (reports, responders, moderation). UI hint — server gates.
   * Hierarchy: ROOT and ADMIN both imply MODERATOR.
   */
  readonly canModerate = computed(() => {
    const roles = this.session()?.roles ?? [];
    return roles.includes('ROOT') || roles.includes('ADMIN') || roles.includes('MODERATOR');
  });

  /** Toggles the sidenav (mobile). */
  toggleSidenav(): void {
    this.sidenavOpen.update((open) => !open);
  }

  /**
   * Switches the UI language from the header control.
   * @param value the chosen locale code from the `<select>`.
   */
  onLocaleChange(value: string): void {
    this.localeService.use(value as SupportedLocale);
  }

  /** Logs out and returns to the login page. Always clears local state even if the network is down. */
  logout(): void {
    this.auth.logout().subscribe({
      next: () => this.afterLogout(),
      error: () => this.afterLogout(),
    });
  }

  /** Closes the sidenav after a nav click on mobile (keeps the content visible on a small screen). */
  onNavigate(): void {
    if (window.matchMedia('(max-width: 767.98px)').matches) {
      this.sidenavOpen.set(false);
    }
  }

  /** Shared post-logout routine: toast + redirect to login. */
  private afterLogout(): void {
    this.toast.info(this.translate.instant('auth.loggedOut'));
    void this.router.navigate(['/login']);
  }
}
