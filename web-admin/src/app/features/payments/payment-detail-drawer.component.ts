import {
  Component,
  DestroyRef,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/notifications/toast.service';
import { StatusBadgeComponent } from '../../shared/components/status-badge.component';
import { statusTone } from '../../shared/util/status-tone.util';
import { Payment, RefundTopUpRequest } from './payments.models';
import { PaymentsService } from './payments.service';
import { buildTimeline, canRefund, canVoid, minorToMajor } from './payments.util';

/** The two operator money-movement actions this drawer can perform. */
type PaymentAction = 'refund' | 'void';

/**
 * Payment (top-up) detail drawer — full admin view + the operator money-movement actions
 * (ADR-0015 addendum; PRD §18, §23). Opened when an operator clicks a payments-ledger row.
 *
 * <p>Responsibility: a right-anchored slide-over that loads ONE top-up via {@code GET /admin/payments/{id}},
 * renders its full admin detail and a DERIVED status timeline, and lets an operator REFUND a settled top-up
 * (SUCCEEDED → REFUNDED) or VOID an un-settled one (INITIATED/PENDING → VOIDED). Each action requires a
 * non-blank audit reason and an explicit two-stage CONFIRM (no destructive one-click); on success it toasts,
 * reloads the detail (server-authoritative state, never an optimistic guess), and notifies the parent list to
 * refresh. The buttons are enabled ONLY for the states the backend allows (the client gate mirrors the server
 * state machine but is not the authority — the server re-checks and may 409, which the central interceptor
 * surfaces as a localised toast).</p>
 *
 * <p><b>Privacy + fence (PRD §18, D18):</b> the detail carries NO MSISDN (never stored) and no national/voter
 * ID — only an opaque buyer UUID and redacted machine reason codes. Refund/void touch ONLY the convenience
 * wallet; nothing here reads or gates on a token balance, and tokens never buy democratic weight (§23). The
 * standing fence note is shown so an operator never mistakes this for a democratic-weight surface.</p>
 *
 * <p><b>Accessibility (WCAG 2.1 AA):</b> the panel is a labelled {@code role="dialog"} with
 * {@code aria-modal}, closes on Esc and backdrop click, moves focus to the close button on open, and exposes
 * a polite live region for load/action status. Subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-payment-detail-drawer',
  standalone: true,
  imports: [FormsModule, DatePipe, DecimalPipe, TranslateModule, StatusBadgeComponent],
  templateUrl: './payment-detail-drawer.component.html',
})
export class PaymentDetailDrawerComponent implements OnChanges {
  private readonly payments = inject(PaymentsService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  /** The top-up public id to show; `null` keeps the drawer closed. Set by the parent list on row click. */
  @Input() paymentId: string | null = null;

  /** Emitted when the drawer is dismissed (Esc/backdrop/close) so the parent can clear its selection. */
  @Output() readonly closed = new EventEmitter<void>();

  /** Emitted after a successful refund/void so the parent list can reload the page (server-truth). */
  @Output() readonly changed = new EventEmitter<void>();

  /** The close button — focus lands here on open for keyboard users. */
  @ViewChild('closeBtn') closeBtn?: ElementRef<HTMLButtonElement>;

  /** Detail UI state. */
  readonly payment = signal<Payment | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  /** Action UI state: which action is being composed (`null` = none), its reason, and whether it's confirming. */
  readonly pendingAction = signal<PaymentAction | null>(null);
  readonly reason = signal('');
  readonly confirming = signal(false);
  readonly acting = signal(false);

  /** Derived: the status timeline for the loaded payment. */
  readonly timeline = computed(() => buildTimeline(this.payment()));

  /** Derived: whether each action is allowed for the current state (UX gate; server is the authority). */
  readonly refundable = computed(() => canRefund(this.payment()));
  readonly voidable = computed(() => canVoid(this.payment()));

  /** Presentation helpers. */
  readonly tone = statusTone;
  readonly minorToMajor = minorToMajor;

  /**
   * Reacts to a new `paymentId`: opens + loads when set, resets when cleared.
   * @param changes the input changes.
   */
  ngOnChanges(changes: SimpleChanges): void {
    if (!('paymentId' in changes)) {
      return;
    }
    this.resetActionState();
    if (this.paymentId) {
      this.load();
      // Defer focus until the panel is in the DOM.
      setTimeout(() => this.closeBtn?.nativeElement.focus(), 0);
    } else {
      this.payment.set(null);
    }
  }

  /** Loads (or reloads) the payment detail. */
  load(): void {
    if (!this.paymentId) {
      return;
    }
    this.loading.set(true);
    this.errored.set(false);
    this.payments
      .get(this.paymentId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (p) => {
          this.payment.set(p);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });
  }

  /** Closes the drawer (Esc/backdrop/close button). No-op while an action is in flight. */
  close(): void {
    if (this.acting()) {
      return;
    }
    this.resetActionState();
    this.closed.emit();
  }

  /**
   * Begins composing an action — reveals the reason field for refund/void.
   * @param action which money-movement to compose.
   */
  startAction(action: PaymentAction): void {
    this.pendingAction.set(action);
    this.reason.set('');
    this.confirming.set(false);
  }

  /** Cancels the in-progress action composition, returning to the detail view. */
  cancelAction(): void {
    this.resetActionState();
  }

  /**
   * Moves from composing to confirming — only with a non-blank reason. The explicit confirm step prevents an
   * accidental one-click money movement.
   */
  requestConfirm(): void {
    if (!this.reason().trim()) {
      return;
    }
    this.confirming.set(true);
  }

  /** Returns from the confirm step back to editing the reason. */
  backToEdit(): void {
    this.confirming.set(false);
  }

  /**
   * Executes the confirmed refund/void: runs the mutation, toasts the localised outcome, reloads the detail
   * (server-authoritative), and notifies the parent list. No-ops without a chosen action or while acting.
   */
  submit(): void {
    const action = this.pendingAction();
    const reason = this.reason().trim();
    if (!action || !reason || this.acting() || !this.paymentId) {
      return;
    }
    const request: RefundTopUpRequest = { reason };
    const call$ =
      action === 'refund'
        ? this.payments.refund(this.paymentId, request)
        : this.payments.voidTopUp(this.paymentId, request);
    const successKey = action === 'refund' ? 'payments.refundDone' : 'payments.voidDone';

    this.acting.set(true);
    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (updated) => {
        this.acting.set(false);
        this.payment.set(updated);
        this.resetActionState();
        this.toast.success(this.translate.instant(successKey));
        this.changed.emit();
      },
      // The error interceptor already toasts a user-safe message (e.g. the 409 not-refundable);
      // just clear the busy state and let the operator retry or close.
      error: () => this.acting.set(false),
    });
  }

  /** Clears the action composition state (reason, confirm flag, chosen action). */
  private resetActionState(): void {
    this.pendingAction.set(null);
    this.reason.set('');
    this.confirming.set(false);
  }
}
