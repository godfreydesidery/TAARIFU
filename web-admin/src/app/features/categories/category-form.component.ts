import { Component, DestroyRef, Input, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ApiError } from '../../core/api/api-error';
import { ErrorDetail } from '../../core/api/api-response.model';
import { ToastService } from '../../core/notifications/toast.service';
import { CreateIssueCategory, UpdateIssueCategory } from './category.models';
import { CategoryService } from './category.service';

/** Routing-level + visibility option tokens (mirror the backend enums; validated server-side). */
const ROUTING_LEVELS = ['WARD', 'COUNCIL', 'DISTRICT', 'REGION', 'NATIONAL'];
const VISIBILITIES = ['PUBLIC', 'PRIVATE', 'ANONYMOUS'];

/**
 * Create/edit form for an issue category (UC-B14; `POST`/`PUT /issue-categories`).
 *
 * <p>Responsibility: ONE typed reactive form serving both create and edit, distinguished by the
 * route-bound {@link id} input (`null` = create). On edit it pre-loads the category and disables the
 * immutable `code` control (the server forbids editing it). It maps backend field-validation errors
 * (`data.errors[]`) back onto the matching form controls so the operator sees inline, localised messages
 * — the response interceptor deliberately does NOT toast validation errors (they belong on the field).
 * Subscriptions use {@link takeUntilDestroyed}.</p>
 *
 * <p>WHY route input binding: with `withComponentInputBinding()` the `:categoryId` path param is bound to
 * {@link id} directly — no manual `ActivatedRoute` snapshot plumbing (Angular 18 idiom).</p>
 */
@Component({
  selector: 'app-category-form',
  standalone: true,
  imports: [ReactiveFormsModule, TranslateModule],
  templateUrl: './category-form.component.html',
})
export class CategoryFormComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly categories = inject(CategoryService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  /** The category public id from the route (`/issue-categories/:categoryId/edit`); `null` on create. */
  @Input() categoryId: string | null = null;

  /** Whether a save is in flight. */
  readonly submitting = signal(false);
  /** Whether the existing category is being loaded (edit mode). */
  readonly loading = signal(false);

  /** Selectable routing-level and visibility tokens. */
  readonly routingLevels = ROUTING_LEVELS;
  readonly visibilities = VISIBILITIES;

  /**
   * The category form. `code` is validated to UPPER_SNAKE_CASE (matches the server constraint) and is
   * disabled in edit mode. SLA minutes are bounded ≥ 1 like the backend.
   */
  readonly form = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.pattern(/^[A-Z][A-Z0-9_]*$/), Validators.maxLength(64)]],
    name: ['', [Validators.required, Validators.maxLength(160)]],
    defaultRoutingLevel: ['WARD', [Validators.required]],
    defaultSlaTtfrMinutes: [1440, [Validators.required, Validators.min(1)]],
    defaultSlaTtrMinutes: [4320, [Validators.required, Validators.min(1)]],
    sensitive: [false],
    forcePrivate: [false],
    defaultVisibility: ['PUBLIC', [Validators.required]],
    icon: [''],
    active: [true],
  });

  /** True when editing an existing category. */
  get isEdit(): boolean {
    return this.categoryId !== null;
  }

  /** In edit mode, loads the category and disables the immutable code control. */
  ngOnInit(): void {
    if (!this.categoryId) {
      return;
    }
    this.loading.set(true);
    this.form.controls.code.disable();
    this.categories
      .get(this.categoryId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (category) => {
          this.form.patchValue({
            code: category.code,
            name: category.name,
            defaultRoutingLevel: category.defaultRoutingLevel,
            defaultSlaTtfrMinutes: category.defaultSlaTtfrMinutes,
            defaultSlaTtrMinutes: category.defaultSlaTtrMinutes,
            sensitive: category.sensitive,
            forcePrivate: category.forcePrivate,
            defaultVisibility: category.defaultVisibility,
            icon: category.icon ?? '',
            active: category.active,
          });
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          // The interceptor toasts the error; bounce back to the list.
          void this.router.navigate(['/issue-categories']);
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
        void this.router.navigate(['/issue-categories']);
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
    void this.router.navigate(['/issue-categories']);
  }

  /** Builds the create request from the form value. */
  private saveCreate() {
    const v = this.form.getRawValue();
    const body: CreateIssueCategory = {
      code: v.code,
      name: v.name,
      defaultRoutingLevel: v.defaultRoutingLevel,
      defaultSlaTtfrMinutes: v.defaultSlaTtfrMinutes,
      defaultSlaTtrMinutes: v.defaultSlaTtrMinutes,
      sensitive: v.sensitive,
      forcePrivate: v.forcePrivate,
      defaultVisibility: v.defaultVisibility,
      icon: v.icon || undefined,
    };
    return this.categories.create(body);
  }

  /** Builds the update request from the form value (code/parent are not editable). */
  private saveUpdate() {
    const v = this.form.getRawValue();
    const body: UpdateIssueCategory = {
      name: v.name,
      defaultRoutingLevel: v.defaultRoutingLevel,
      defaultSlaTtfrMinutes: v.defaultSlaTtfrMinutes,
      defaultSlaTtrMinutes: v.defaultSlaTtrMinutes,
      sensitive: v.sensitive,
      forcePrivate: v.forcePrivate,
      defaultVisibility: v.defaultVisibility,
      icon: v.icon || undefined,
      active: v.active,
    };
    return this.categories.update(this.categoryId as string, body);
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
