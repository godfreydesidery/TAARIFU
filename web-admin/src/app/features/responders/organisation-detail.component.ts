import { Component, DestroyRef, Input, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';
import { ToastService } from '../../core/notifications/toast.service';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { COVERAGE_TYPES, CreateResponder, RESPONDER_TYPES, Responder } from './responders.models';
import { RespondersService } from './responders.service';

/**
 * Organisation detail — its responder capabilities directory + create-responder form (PRD §24, D20).
 *
 * <p>Responsibility: lists the responders belonging to one organisation (paged) and lets an admin add a
 * new responder capability inline (type, coverage, handled categories). Authorization is enforced
 * SERVER-side. Loading/empty/error states are handled; subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-organisation-detail',
  standalone: true,
  imports: [RouterLink, ReactiveFormsModule, TranslateModule, PaginationComponent],
  templateUrl: './organisation-detail.component.html',
})
export class OrganisationDetailComponent implements OnInit {
  private readonly responders = inject(RespondersService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly fb = inject(FormBuilder);

  /** The organisation public id from the route (`/responders/:organisationId`). */
  @Input() organisationId = '';

  /** Responder list UI state. */
  readonly rows = signal<Responder[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);
  readonly creating = signal(false);
  readonly showForm = signal(false);

  /** Selectable type + coverage tokens. */
  readonly types = RESPONDER_TYPES;
  readonly coverageTypes = COVERAGE_TYPES;

  private readonly pageSize = 20;

  /**
   * The add-responder form. Categories/areas are entered as comma-separated UUIDs (kept simple for MVP;
   * a category/area picker is a follow-up when those reference lists are exposed for selection here).
   */
  readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
    responderType: ['GOVERNMENT', [Validators.required]],
    coverageType: ['NATIONAL', [Validators.required]],
    handledCategoryIds: [''],
    coverageAreaIds: [''],
    slaPolicy: ['', [Validators.maxLength(1000)]],
  });

  /** Loads the first page on init. */
  ngOnInit(): void {
    this.loadPage(0);
  }

  /**
   * Loads a page of the organisation's responders.
   * @param page zero-based page index.
   */
  loadPage(page: number): void {
    this.loading.set(true);
    this.errored.set(false);
    this.responders
      .listResponders(this.organisationId, { page, size: this.pageSize, sort: 'name,asc' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.rows.set(result.content);
          this.meta.set(result.meta);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });
  }

  /** Toggles the inline add-responder form. */
  toggleForm(): void {
    this.showForm.update((open) => !open);
  }

  /** Creates a responder capability under the organisation, then reloads. No-ops if invalid/in-flight. */
  createResponder(): void {
    if (this.form.invalid || this.creating()) {
      this.form.markAllAsTouched();
      return;
    }
    this.creating.set(true);
    const v = this.form.getRawValue();
    const body: CreateResponder = {
      name: v.name,
      responderType: v.responderType,
      coverageType: v.coverageType,
      handledCategoryIds: this.splitIds(v.handledCategoryIds),
      coverageAreaIds: this.splitIds(v.coverageAreaIds),
      slaPolicy: v.slaPolicy || undefined,
    };
    this.responders
      .createResponder(this.organisationId, body)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.creating.set(false);
          this.toast.success(this.translate.instant('common.saved'));
          this.form.reset({ responderType: 'GOVERNMENT', coverageType: 'NATIONAL', name: '', handledCategoryIds: '', coverageAreaIds: '', slaPolicy: '' });
          this.showForm.set(false);
          this.loadPage(this.meta()?.page ?? 0);
        },
        error: () => this.creating.set(false),
      });
  }

  /** Splits a comma/space-separated id list into a clean array (empty → undefined-safe empty). */
  private splitIds(raw: string): string[] {
    return raw
      .split(/[,\s]+/)
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
  }
}
