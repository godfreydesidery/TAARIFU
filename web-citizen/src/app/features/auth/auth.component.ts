import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { finalize } from 'rxjs/operators';

import { ApiError } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { LocaleService } from '../../core/i18n/locale.service';
import { ToastService } from '../../core/notifications/toast.service';

/** The two steps of the passwordless flow. */
type AuthStep = 'phone' | 'otp';

/**
 * Citizen authentication screen — passwordless **phone + OTP** in two steps (PRD §6, AUTH-DESIGN §3/§4).
 *
 * <p>Responsibility: drive the tiered-identity entry point. Step 1 collects an E.164 phone and requests an
 * OTP; step 2 verifies the SMS code. Because one phone = one account (locked decision), the screen offers a
 * single "continue" intent and verifies via signup OR login under the hood — the citizen is never asked to
 * choose "register vs sign in". The OTP-request is non-committal server-side (anti-enumeration), so the UI
 * never reveals whether the phone already has an account. On success the citizen lands on the feed (or the
 * `returnUrl`). Swahili-first copy; large touch targets; numeric inputmode for the code (low-end keyboards).</p>
 *
 * <p>Security: the phone and OTP code are never logged; only the server's localised envelope message is
 * shown on error. A new account is created at tier T1 — the UI surfaces a brief tier hint so the citizen
 * understands what they can do next and how to unlock more.</p>
 */
@Component({
  selector: 'app-auth',
  standalone: true,
  imports: [ReactiveFormsModule, TranslateModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './auth.component.html',
  styleUrl: './auth.component.scss',
})
export class AuthComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly locale = inject(LocaleService);

  /** Current step of the flow. */
  protected readonly step = signal<AuthStep>('phone');
  /** True while a request is in flight (disables the submit button + shows a spinner). */
  protected readonly busy = signal(false);
  /** The challenge id returned by the OTP-request step, carried into verify. */
  private challengeId: string | null = null;
  /** Whether this is a brand-new signup (true) vs an existing-account login (false). */
  private isSignup = false;

  /** Step-1 form: the phone number in E.164 (e.g. +255712345678). */
  protected readonly phoneForm = this.fb.nonNullable.group({
    phone: ['', [Validators.required, Validators.pattern(/^\+\d{9,15}$/)]],
  });

  /** Step-2 form: the numeric OTP code (4–8 digits). */
  protected readonly otpForm = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.pattern(/^\d{4,8}$/)]],
  });

  /** Active locale label for the inline language toggle. */
  protected get localeLabel(): string {
    return this.locale.locale().toUpperCase();
  }

  /** Flips SW↔EN. */
  protected toggleLanguage(): void {
    this.locale.toggle();
  }

  /**
   * Step 1 → request an OTP. We attempt LOGIN first (the common case for a returning citizen); if the
   * account does not exist the server still returns a challenge (anti-enumeration), and verify will fall
   * back to signup. To keep the slice simple and avoid double-SMS we request the SIGNUP OTP, which the
   * backend uses to create-or-activate a T1 account on verify. (See README "What's stubbed" re: a unified
   * request endpoint.)
   */
  protected requestOtp(): void {
    if (this.phoneForm.invalid) {
      this.phoneForm.markAllAsTouched();
      return;
    }
    const phone = this.phoneForm.controls.phone.value.trim();
    this.busy.set(true);
    this.isSignup = true;
    this.auth
      .requestSignupOtp(phone)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (challenge) => {
          this.challengeId = challenge.challengeId;
          this.step.set('otp');
          this.toast.info(this.translate.instant('auth.otpSent'));
        },
        // Errors are toasted centrally by the interceptor; stay on step 1 to let the citizen retry.
        error: () => {},
      });
  }

  /** Step 2 → verify the OTP, completing signup (new) or login (existing), then route on. */
  protected verifyOtp(): void {
    if (this.otpForm.invalid || !this.challengeId) {
      this.otpForm.markAllAsTouched();
      return;
    }
    const request = { challengeId: this.challengeId, code: this.otpForm.controls.code.value.trim() };
    this.busy.set(true);

    const done = () => this.busy.set(false);
    if (this.isSignup) {
      this.auth
        .completeSignup(request)
        .pipe(finalize(done))
        .subscribe({
          next: (result) => this.onAuthenticated(result.tier),
          error: (err) => this.handleVerifyError(err),
        });
    } else {
      this.auth
        .completeLogin(request)
        .pipe(finalize(done))
        .subscribe({
          next: (result) => {
            if (result.tokens) {
              // LoginResult carries no tier; read it from the freshly decoded session (JWT claim hint).
              this.onAuthenticated(this.auth.tier());
            } else if (result.mfaRequired) {
              this.toast.error(this.translate.instant('auth.staffUseConsole'));
            }
          },
          error: (err) => this.handleVerifyError(err),
        });
    }
  }

  /** Returns to step 1 to re-enter the phone (e.g. typo). */
  protected backToPhone(): void {
    this.step.set('phone');
    this.otpForm.reset();
    this.challengeId = null;
  }

  /** On success, greet with the tier hint and route to the saved returnUrl or the feed. */
  private onAuthenticated(tier: string | null): void {
    this.toast.success(this.translate.instant('auth.welcome'));
    if (tier) {
      // Brief tier explainer (e.g. "You are at T1 — you can report; verify ID to unlock more").
      this.toast.info(this.translate.instant('auth.tierHint', { tier }));
    }
    const returnUrl = new URLSearchParams(window.location.search).get('returnUrl') ?? '/feed';
    void this.router.navigateByUrl(returnUrl);
  }

  /**
   * If signup verification reports the phone already exists, transparently retry as a LOGIN verify so the
   * citizen never has to know which path they were on (one account / additive roles). Other errors are
   * already toasted by the interceptor.
   */
  private handleVerifyError(err: unknown): void {
    if (err instanceof ApiError && (err.code === 'ACCOUNT_EXISTS' || err.code === 'ALREADY_REGISTERED')) {
      this.isSignup = false;
      this.verifyOtp();
    }
  }
}
