import { Component, DestroyRef, Input, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ApiError } from '../../core/api/api-error';
import { ErrorDetail } from '../../core/api/api-response.model';
import { ToastService } from '../../core/notifications/toast.service';
import {
  CreateOrganisation,
  ORGANISATION_STATUSES,
  ORGANISATION_TYPES,
  UpdateOrganisation,
} from './responders.models';
import { RespondersService } from './responders.service';

/**
 * Create/edit form for a responder organisation (PRD §24, D20; `POST`/`PUT /responders/admin/organisations`).
 *
 * <p>Responsibility: ONE typed reactive form serving both create and edit, distinguished by the
 * route-bound {@link organisationId} input (`null` = create). On edit it pre-loads the organisation (via
 * the list service's first page lookup) and exposes the status field; on create status defaults to
 * PENDING server-side. Verification is NOT set here — it is a separate, audited toggle on the list/detail
 * (the §24.4 go-live gate). Backend field-validation errors are mapped onto the matching controls.
 * Subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-organisation-form',
  standalone: true,
  imports: [ReactiveFormsModule, TranslateModule],
  templateUrl: './organisation-form.component.html',
})
export class OrganisationFormComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly responders = inject(RespondersService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  /** The organisation public id from the route; `null` on create. */
  @Input() organisationId: string | null = null;

  /** Whether a save is in flight. */
  readonly submitting = signal(false);
  /** Whether the existing organisation is being loaded (edit mode). */
  readonly loading = signal(false);

  /** Selectable type + status tokens. */
  readonly types = ORGANISATION_TYPES;
  readonly statuses = ORGANISATION_STATUSES;

  /** The organisation form. Email/phone optional; status only meaningful in edit. */
  readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
    type: ['GOVERNMENT', [Validators.required]],
    status: ['PENDING', [Validators.required]],
    contactPhone: ['', [Validators.maxLength(32)]],
    contactEmail: ['', [Validators.email, Validators.maxLength(200)]],
    websiteUrl: ['', [Validators.maxLength(300)]],
  });

  /** True when editing an existing organisation. */
  get isEdit(): boolean {
    return this.organisationId !== null;
  }

  /** In edit mode, loads the organisation by scanning the admin list (no single-get endpoint exists). */
  ngOnInit(): void {
    if (!this.organisationId) {
      return;
    }
    this.loading.set(true);
    this.responders
      .listOrganisations({ page: 0, size: 100, sort: 'name,asc' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          const org = result.content.find((o) => o.id === this.organisationId);
          if (org) {
            this.form.patchValue({
              name: org.name,
              type: org.type,
              status: org.status,
              contactPhone: org.contactPhone ?? '',
              contactEmail: org.contactEmail ?? '',
              websiteUrl: org.websiteUrl ?? '',
            });
          }
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          void this.router.navigate(['/responders']);
        },
      });
  }

  /** Submits create or update; on success toasts and returns to the list. No-ops if invalid/in-flight. */
  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    const request$ = this.isEdit ? this.saveUpdate() : this.saveCreate();
    request$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.submitting.set(false);
        this.toast.success(this.translate.instant('common.saved'));
        void this.router.navigate(['/responders']);
      },
      error: (error: unknown) => {
        this.submitting.set(false);
        if (error instanceof ApiError && error.isValidation) {
          this.applyServerErrors(error.errors);
        }
      },
    });
  }

  /** Cancels and returns to the list. */
  cancel(): void {
    void this.router.navigate(['/responders']);
  }

  /** Builds the create request (status is server-defaulted to PENDING). */
  private saveCreate() {
    const v = this.form.getRawValue();
    const body: CreateOrganisation = {
      name: v.name,
      type: v.type,
      contactPhone: v.contactPhone || undefined,
      contactEmail: v.contactEmail || undefined,
      websiteUrl: v.websiteUrl || undefined,
    };
    return this.responders.createOrganisation(body);
  }

  /** Builds the update request (includes status). */
  private saveUpdate() {
    const v = this.form.getRawValue();
    const body: UpdateOrganisation = {
      name: v.name,
      type: v.type,
      status: v.status,
      contactPhone: v.contactPhone || undefined,
      contactEmail: v.contactEmail || undefined,
      websiteUrl: v.websiteUrl || undefined,
    };
    return this.responders.updateOrganisation(this.organisationId as string, body);
  }

  /** Maps backend field validation errors onto the matching form controls (inline display). */
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
