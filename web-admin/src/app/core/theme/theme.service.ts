import { Injectable, signal } from '@angular/core';

/** The colour themes the console ships with. */
export type Theme = 'light' | 'dark';

/**
 * Owns the active colour theme (light/dark) for the admin console.
 *
 * <p>Responsibility: the single source of truth for the UI theme. It writes a `data-theme` attribute on
 * `<html>` which the global stylesheet uses to swap the `--tf-*` CSS custom-property roles (no rebuild,
 * no per-component code). The chosen theme is persisted so it survives reloads, and exposed as a signal so
 * the topbar toggle updates reactively. When the user has made no choice yet, the OS preference
 * (`prefers-color-scheme`) is honoured for first paint.</p>
 *
 * <p>WHY a data-attribute and not a CSS class: it lets `color-scheme` + the role-variable block target
 * `:root[data-theme='dark']` cleanly, and keeps the theme decision in ONE place (DRY, CLAUDE.md §3).</p>
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private static readonly STORAGE_KEY = 'taarifu.admin.theme';

  /** Reactive active theme; drives the topbar toggle's icon/label. */
  private readonly themeSignal = signal<Theme>(this.initialTheme());

  /** Read-only view of the active theme. */
  readonly theme = this.themeSignal.asReadonly();

  /**
   * Applies the resolved startup theme to `<html>`. Called once from the app bootstrap so the correct
   * theme is active on first paint (avoids a light→dark flash).
   */
  init(): void {
    this.apply(this.themeSignal());
  }

  /** Flips between light and dark and persists the choice. */
  toggle(): void {
    this.set(this.themeSignal() === 'dark' ? 'light' : 'dark');
  }

  /**
   * Sets the active theme, applies it to `<html>`, and persists it.
   * @param theme the theme to activate.
   */
  set(theme: Theme): void {
    this.themeSignal.set(theme);
    this.apply(theme);
    try {
      localStorage.setItem(ThemeService.STORAGE_KEY, theme);
    } catch {
      // Storage may be unavailable (private mode); the in-memory signal still drives the UI.
    }
  }

  /** Writes the `data-theme` attribute the global stylesheet reacts to. */
  private apply(theme: Theme): void {
    document.documentElement.setAttribute('data-theme', theme);
  }

  /** Resolves the startup theme: persisted choice → OS preference → light. */
  private initialTheme(): Theme {
    try {
      const saved = localStorage.getItem(ThemeService.STORAGE_KEY);
      if (saved === 'light' || saved === 'dark') {
        return saved;
      }
    } catch {
      // ignore — fall through to OS preference
    }
    const prefersDark =
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-color-scheme: dark)').matches;
    return prefersDark ? 'dark' : 'light';
  }
}
