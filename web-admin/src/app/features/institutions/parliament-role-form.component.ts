import { Component, DestroyRef, Input, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ApiError } from '../../core/api/api-error';
import { ErrorDetail } from '../../core/api/api-response.model';
import { ToastService } from '../../core/notifications/toast.service';
import { ParliamentRoleWrite } from './institutions-admin.models';
import { InstitutionsAdminService } from './institutions-admin.service';

/**
 * Create/edit form for a parliament role (PRD §9.1; UC-B13;
 * `POST`/`PUT /admin/institutions/parliament-roles`).
 *
 * <p>Responsibility: ONE typed reactive form for create + edit, distinguished by the route-bound
 * {@link roleId} input (`null` = create). The `code` is immutable in edit mode (matches the server). The
 * role is loaded in edit mode by scanning the paged role list (no single-get endpoint). Backend field
 * validation errors are mapped onto controls. Subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-parliament-role-form',
  standalone: true,
  imports: [ReactiveFormsModule, TranslateModule],
  templateUrl: './parliament-role-form.component.html',
})
export class ParliamentRoleFormComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly institutions = inject(InstitutionsAdminService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  /** The role public id from the route; `null` on create. */
  @Input() roleId: string | null = null;

  /** Save/load flags. */
  readonly submitting = signal(false);
  readonly loading = signal(false);

  /** The role form. `code` is UPPER_SNAKE_CASE and immutable in edit mode. */
  readonly form = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.pattern(/^[A-Z][A-Z0-9_]*$/), Validators.maxLength(48)]],
    name: ['', [Validators.required, Validators.maxLength(160)]],
    description: ['', [Validators.maxLength(512)]],
  });

  /** True when editing. */
  get isEdit(): boolean {
    return this.roleId !== null;
  }

  /** In edit mode, loads the role by scanning the list and disables the immutable code. */
  ngOnInit(): void {
    if (!this.roleId) {
      return;
    }
    this.loading.set(true);
    this.form.controls.code.disable();
    this.institutions
      .listParliamentRoles({ page: 0, size: 100, sort: 'name,asc' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          const role = result.content.find((r) => r.id === this.roleId);
          if (role) {
            this.form.patchValue({ code: role.code, name: role.name, description: role.description ?? '' });
          }
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          void this.router.navigate(['/institutions/parliament-roles']);
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
    const body: ParliamentRoleWrite = {
      code: v.code,
      name: v.name,
      description: v.description || undefined,
    };
    const request$ = this.isEdit
      ? this.institutions.updateParliamentRole(this.roleId as string, body)
      : this.institutions.createParliamentRole(body);
    request$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.submitting.set(false);
        this.toast.success(this.translate.instant('common.saved'));
        void this.router.navigate(['/institutions/parliament-roles']);
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
    void this.router.navigate(['/institutions/parliament-roles']);
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
