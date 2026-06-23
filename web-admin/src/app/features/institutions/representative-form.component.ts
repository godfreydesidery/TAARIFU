import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ApiError } from '../../core/api/api-error';
import { ErrorDetail } from '../../core/api/api-response.model';
import { ToastService } from '../../core/notifications/toast.service';
import {
  LEGISLATURES,
  REPRESENTATIVE_MANDATES,
  REPRESENTATIVE_STATUSES,
  REPRESENTATIVE_TYPES,
  RepresentativeWrite,
} from './institutions-admin.models';
import { InstitutionsAdminService } from './institutions-admin.service';

/**
 * Representative create/link form (PRD §6.4 D12, US-0.6; UC-C04; `POST /admin/institutions/representatives`).
 *
 * <p>Responsibility: links the REPRESENTATIVE role to an <b>existing</b> account's profile. Per the
 * single-account/additive-role rule (§6.4 D12), an elected citizen is NEVER re-registered — the admin
 * supplies the existing {@code profileId} and the role + scope (constituency/ward, party, term) attach to
 * that one account; they keep their Citizen role. The backend enforces mandate⇄geography (constituency
 * required for CONSTITUENCY mandate; nullable for special-seats/nominated) and the one-SITTING-MP
 * invariant — violations surface as CONFLICT/validation toasts. The form does NOT mint a new identity and
 * never touches verified ID fields (those are owned by verification). Subscriptions use
 * {@link takeUntilDestroyed}.</p>
 *
 * <p>WHY UUID inputs (not pickers) for profile/constituency/ward/party/parliament: those reference lists
 * are large and live in other features; a typeahead picker per field is a follow-up. For MVP the admin
 * pastes the public ids (which they obtain from the respective lists), and the server validates each.</p>
 */
@Component({
  selector: 'app-representative-form',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, TranslateModule],
  templateUrl: './representative-form.component.html',
})
export class RepresentativeFormComponent {
  private readonly fb = inject(FormBuilder);
  private readonly institutions = inject(InstitutionsAdminService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  /** Whether a save is in flight. */
  readonly submitting = signal(false);

  /** Selectable tokens. */
  readonly types = REPRESENTATIVE_TYPES;
  readonly mandates = REPRESENTATIVE_MANDATES;
  readonly statuses = REPRESENTATIVE_STATUSES;
  readonly legislatures = LEGISLATURES;

  /** The representative link form. `profileId` is the EXISTING account's profile (additive role). */
  readonly form = this.fb.nonNullable.group({
    profileId: ['', [Validators.required]],
    type: ['MP', [Validators.required]],
    mandate: ['CONSTITUENCY', [Validators.required]],
    constituencyId: [''],
    wardId: [''],
    partyId: [''],
    legislature: ['UNION_PARLIAMENT'],
    parliamentId: [''],
    parliamentRoleId: [''],
    status: ['PENDING_VERIFICATION', [Validators.required]],
    electedAt: [''],
    bio: ['', [Validators.maxLength(4000)]],
  });

  /** Submits the create/link request. No-ops if invalid/in-flight. */
  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    const v = this.form.getRawValue();
    const body: RepresentativeWrite = {
      profileId: v.profileId || null,
      type: v.type,
      mandate: v.mandate,
      constituencyId: v.constituencyId || null,
      wardId: v.wardId || null,
      partyId: v.partyId || null,
      legislature: v.legislature || undefined,
      parliamentId: v.parliamentId || null,
      parliamentRoleId: v.parliamentRoleId || null,
      status: v.status,
      electedAt: v.electedAt || null,
      bio: v.bio || null,
    };
    this.institutions
      .createRepresentative(body)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.submitting.set(false);
          this.toast.success(this.translate.instant('common.saved'));
          void this.router.navigate(['/representatives']);
        },
        error: (error: unknown) => {
          this.submitting.set(false);
          if (error instanceof ApiError && error.isValidation) {
            this.applyServerErrors(error.errors);
          }
        },
      });
  }

  /** Cancels and returns to the representatives directory. */
  cancel(): void {
    void this.router.navigate(['/representatives']);
  }

  /** Maps backend field validation errors onto the matching form controls. */
  private applyServerErrors(errors: ErrorDetail[]): void {
    for (const detail of errors) {
      if (!detail.field) {
        continue;
      }
      const control = this.form.get(detail.field);
      if (control) {
        control.setErrors({ server: detail.message });
        control.markAsTouched();
      }
    }
  }
}
