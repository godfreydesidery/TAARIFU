import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ApiError } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/notifications/toast.service';
import { LocaleService, SupportedLocale } from '../../core/i18n/locale.service';
import { ThemeService } from '../../core/theme/theme.service';

/**
 * Login page for the admin console (US-0.1; `POST /auth/login/password`).
 *
 * <p>Responsibility: a typed reactive form (account key + password) that authenticates an admin/staff
 * user via {@link AuthService.loginWithPassword}, then routes to the originally-requested URL (or the
 * dashboard). It surfaces failures inline and via toast, and handles the MFA-required case by informing
 * the user that the staff TOTP step is needed (the second factor itself is out of scope for this
 * foundation slice).</p>
 *
 * <p>Security/UX: the password field is never logged; on failure the message is the server's localised,
 * non-enumerating text. The form disables the submit button while in flight to prevent double-submits.
 * All strings are i18n keys (CLAUDE.md §5).</p>
 */
@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule, TranslateModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly localeService = inject(LocaleService);
  private readonly themeService = inject(ThemeService);

  /** Whether a login request is in flight (drives the disabled/spinner state). */
  readonly submitting = signal(false);

  /** Active locale for the on-screen language switch. */
  readonly locale = this.localeService.locale;

  /** Active theme for the on-screen theme toggle. */
  readonly theme = this.themeService.theme;

  /** The reactive login form: account key (phone/email) + password, both required. */
  readonly form = this.fb.nonNullable.group({
    accountKey: ['', [Validators.required]],
    password: ['', [Validators.required]],
  });

  /**
   * Submits the login form. On success stores tokens (via the service) and navigates; on MFA-required
   * informs the user; on error shows the localised failure. No-ops if the form is invalid or in flight.
   */
  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    const { accountKey, password } = this.form.getRawValue();

    this.auth.loginWithPassword({ accountKey, password }).subscribe({
      next: (result) => {
        this.submitting.set(false);
        if (result.mfaRequired || !result.tokens) {
          // Staff TOTP second factor needed — out of scope for this foundation slice; inform the user.
          this.toast.info(this.translate.instant('auth.mfaRequired'));
          return;
        }
        const returnUrl = this.readReturnUrl();
        void this.router.navigateByUrl(returnUrl);
      },
      error: (error: unknown) => {
        this.submitting.set(false);
        // The interceptor already toasted non-401s; ensure the user always sees a clear login failure.
        if (error instanceof ApiError && error.isUnauthenticated) {
          this.toast.error(this.translate.instant('auth.loginFailed'));
        }
      },
    });
  }

  /** Switches the UI language from the login screen. */
  onLocaleChange(value: string): void {
    this.localeService.use(value as SupportedLocale);
  }

  /** Toggles light/dark theme from the login screen. */
  toggleTheme(): void {
    this.themeService.toggle();
  }

  /** Reads the post-login return URL from the query string, defaulting to the dashboard. */
  private readReturnUrl(): string {
    const params = new URLSearchParams(window.location.search);
    return params.get('returnUrl') ?? '/dashboard';
  }
}
