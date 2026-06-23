import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/notifications/toast.service';
import {
  ActionCostPolicy,
  TokenReward,
  UpsertActionCostPolicy,
  UpsertTokenReward,
} from './tokens.models';
import { TokensService } from './tokens.service';

/** Free-quota / cap window tokens offered in the config forms (validated server-side). */
const QUOTA_PERIODS = ['DAY', 'WEEK', 'MONTH'];

/**
 * Token admin config — action-cost/free-quota policies + behaviour rewards (PRD §23.4, M17).
 *
 * <p>Responsibility: lists active cost/quota policies and rewards, lets an admin upsert (create or
 * supersede) and retire each. The backend REJECTS any policy whose action code is a binding democratic
 * action — the integrity fence (D18) is un-configurable-around — so a rejected upsert surfaces as a
 * CONFLICT/validation toast; this UI never implies tokens gate a signature/rating/poll. Loading/empty/
 * error states are handled; subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-token-config',
  standalone: true,
  imports: [RouterLink, ReactiveFormsModule, TranslateModule],
  templateUrl: './token-config.component.html',
})
export class TokenConfigComponent implements OnInit {
  private readonly tokens = inject(TokensService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly fb = inject(FormBuilder);

  /** Policy + reward UI state. */
  readonly policies = signal<ActionCostPolicy[]>([]);
  readonly rewards = signal<TokenReward[]>([]);
  readonly loading = signal(false);
  readonly errored = signal(false);
  readonly savingPolicy = signal(false);
  readonly savingReward = signal(false);

  /** Quota/cap window options. */
  readonly periods = QUOTA_PERIODS;

  /** The cost/quota policy upsert form. */
  readonly policyForm = this.fb.nonNullable.group({
    actionCode: ['', [Validators.required, Validators.pattern(/^[A-Z][A-Z0-9_]*$/), Validators.maxLength(64)]],
    roleName: [''],
    tokenCost: [0, [Validators.required, Validators.min(0)]],
    freeQuotaPeriod: ['DAY', [Validators.required]],
    freeQuotaCount: [0, [Validators.required, Validators.min(0)]],
  });

  /** The behaviour reward upsert form. */
  readonly rewardForm = this.fb.nonNullable.group({
    behaviour: ['', [Validators.required, Validators.maxLength(48)]],
    grantAmount: [1, [Validators.required, Validators.min(1)]],
    capCount: [0, [Validators.required, Validators.min(0)]],
    capPeriod: ['DAY', [Validators.required]],
  });

  /** Loads both lists on init. */
  ngOnInit(): void {
    this.loadAll();
  }

  /** Loads active policies + rewards. */
  loadAll(): void {
    this.loading.set(true);
    this.errored.set(false);
    this.tokens
      .listPolicies()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (policies) => {
          this.policies.set(policies);
          this.loadRewards();
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });
  }

  /** Loads active rewards (second half of {@link loadAll}). */
  private loadRewards(): void {
    this.tokens
      .listRewards()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rewards) => {
          this.rewards.set(rewards);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });
  }

  /** Upserts a cost/quota policy. No-ops if invalid/in-flight. */
  upsertPolicy(): void {
    if (this.policyForm.invalid || this.savingPolicy()) {
      this.policyForm.markAllAsTouched();
      return;
    }
    this.savingPolicy.set(true);
    const v = this.policyForm.getRawValue();
    const body: UpsertActionCostPolicy = {
      actionCode: v.actionCode,
      roleName: v.roleName || undefined,
      tokenCost: v.tokenCost,
      freeQuotaPeriod: v.freeQuotaPeriod,
      freeQuotaCount: v.freeQuotaCount,
    };
    this.tokens
      .upsertPolicy(body)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.savingPolicy.set(false);
          this.toast.success(this.translate.instant('common.saved'));
          this.policyForm.reset({ actionCode: '', roleName: '', tokenCost: 0, freeQuotaPeriod: 'DAY', freeQuotaCount: 0 });
          this.loadAll();
        },
        error: () => this.savingPolicy.set(false),
      });
  }

  /**
   * Retires a policy after a confirm, then reloads.
   * @param policy the policy to deactivate.
   */
  retirePolicy(policy: ActionCostPolicy): void {
    if (!confirm(this.translate.instant('tokens.confirmRetire'))) {
      return;
    }
    this.tokens
      .deactivatePolicy(policy.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('common.deleted'));
          this.loadAll();
        },
        error: () => undefined,
      });
  }

  /** Upserts a behaviour reward. No-ops if invalid/in-flight. */
  upsertReward(): void {
    if (this.rewardForm.invalid || this.savingReward()) {
      this.rewardForm.markAllAsTouched();
      return;
    }
    this.savingReward.set(true);
    const v = this.rewardForm.getRawValue();
    const body: UpsertTokenReward = {
      behaviour: v.behaviour,
      grantAmount: v.grantAmount,
      capCount: v.capCount,
      capPeriod: v.capPeriod,
    };
    this.tokens
      .upsertReward(body)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.savingReward.set(false);
          this.toast.success(this.translate.instant('common.saved'));
          this.rewardForm.reset({ behaviour: '', grantAmount: 1, capCount: 0, capPeriod: 'DAY' });
          this.loadAll();
        },
        error: () => this.savingReward.set(false),
      });
  }

  /**
   * Retires a reward after a confirm, then reloads.
   * @param reward the reward to deactivate.
   */
  retireReward(reward: TokenReward): void {
    if (!confirm(this.translate.instant('tokens.confirmRetire'))) {
      return;
    }
    this.tokens
      .deactivateReward(reward.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('common.deleted'));
          this.loadAll();
        },
        error: () => undefined,
      });
  }
}
