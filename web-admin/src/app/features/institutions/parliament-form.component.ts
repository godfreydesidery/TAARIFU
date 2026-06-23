import { Component, DestroyRef, Input, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ApiError } from '../../core/api/api-error';
import { ErrorDetail } from '../../core/api/api-response.model';
import { ToastService } from '../../core/notifications/toast.service';
import { LEGISLATURES, ParliamentWrite } from './institutions-admin.models';
import { InstitutionsAdminService } from './institutions-admin.service';

/**
 * Create/edit form for a parliament term (PRD §9.1; UC-B12; `POST`/`PUT /admin/institutions/parliaments`).
 *
 * <p>Responsibility: ONE typed reactive form serving both create and edit, distinguished by the
 * route-bound {@link parliamentId} input (`null` = create). On edit it pre-loads the term. Backend field
 * validation errors are mapped onto the matching controls. Subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-parliament-form',
  standalone: true,
  imports: [ReactiveFormsModule, TranslateModule],
  templateUrl: './parliament-form.component.html',
})
export class ParliamentFormComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly institutions = inject(InstitutionsAdminService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  /** The parliament public id from the route; `null` on create. */
  @Input() parliamentId: string | null = null;

  /** Save/load flags. */
  readonly submitting = signal(false);
  readonly loading = signal(false);

  /** Selectable legislature tokens. */
  readonly legislatures = LEGISLATURES;

  /** The parliament form. */
  readonly form = this.fb.nonNullable.group({
    termNumber: [12, [Validators.required, Validators.min(1)]],
    name: ['', [Validators.required, Validators.maxLength(160)]],
    legislature: ['UNION_PARLIAMENT'],
    startDate: ['', [Validators.required]],
    endDate: [''],
    current: [false],
  });

  /** True when editing. */
  get isEdit(): boolean {
    return this.parliamentId !== null;
  }

  /** In edit mode, loads the term. */
  ngOnInit(): void {
    if (!this.parliamentId) {
      return;
    }
    this.loading.set(true);
    this.institutions
      .getParliament(this.parliamentId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (p) => {
          this.form.patchValue({
            termNumber: p.termNumber ?? 1,
            name: p.name,
            legislature: p.legislature ?? 'UNION_PARLIAMENT',
            startDate: p.startDate ?? '',
            endDate: p.endDate ?? '',
            current: p.current,
          });
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          void this.router.navigate(['/institutions/parliaments']);
        },
      });
  }

  /** Submits create or update. No-ops if invalid/in-flight. */
  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    const v = this.form.getRawValue();
    const body: ParliamentWrite = {
      termNumber: v.termNumber,
      name: v.name,
      legislature: v.legislature || undefined,
      startDate: v.startDate,
      endDate: v.endDate || null,
      current: v.current,
    };
    const request$ = this.isEdit
      ? this.institutions.updateParliament(this.parliamentId as string, body)
      : this.institutions.createParliament(body);
    request$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.submitting.set(false);
        this.toast.success(this.translate.instant('common.saved'));
        void this.router.navigate(['/institutions/parliaments']);
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
    void this.router.navigate(['/institutions/parliaments']);
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
