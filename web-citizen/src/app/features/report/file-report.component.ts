import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { finalize } from 'rxjs/operators';

import { ApiError } from '../../core/api/api-error';
import { AreaPickerComponent } from '../../shared/area-picker.component';
import { ErrorDetail } from '../../core/api/api-response.model';
import { FileReportRequest, IssueCategory } from './report.models';
import { ReportService } from './report.service';
import { ToastService } from '../../core/notifications/toast.service';
import { WardSummary } from './geography.service';

/**
 * File a report — the citizen's primary action (PRD §10 US-3.1, UC-D01). Target: under ~2 minutes.
 *
 * <p>Responsibility: a focused, low-friction form — pick a category, write a short title + description,
 * resolve the ward via the {@link AreaPickerComponent}, optionally attach a GPS point, and choose
 * anonymity (only meaningful for a sensitive category). It is OFFLINE-FIRST: tapping "Send" while offline
 * (or when the send fails on a flaky link) saves a durable draft via {@link ReportService} and tells the
 * citizen it will send when back online — the citizen path never hard-fails. Server validation errors are
 * mapped back to the offending fields. Swahili-first; large targets; sensitive categories force PRIVATE and
 * surface the anonymity toggle (Appendix D.4).</p>
 *
 * <p>Privacy/PDPA: the GPS point is opt-in and only sent if the citizen taps "use my location"; we never
 * read location silently. Anonymity is an explicit, auditable flag honoured only for sensitive categories.</p>
 */
@Component({
  selector: 'app-file-report',
  standalone: true,
  imports: [ReactiveFormsModule, TranslateModule, AreaPickerComponent, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './file-report.component.html',
  styleUrl: './file-report.component.scss',
})
export class FileReportComponent {
  private readonly fb = inject(FormBuilder);
  private readonly reports = inject(ReportService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** Active issue categories for the picker. */
  protected readonly categories = signal<IssueCategory[]>([]);
  /** The resolved ward (set by the area picker). */
  protected readonly ward = signal<WardSummary | null>(null);
  /** Captured GPS point, or null. */
  protected readonly geo = signal<{ lat: number; lng: number } | null>(null);
  /** True while submitting. */
  protected readonly busy = signal(false);
  /** Field-level server validation errors keyed by field name (for inline display). */
  protected readonly fieldErrors = signal<Record<string, string>>({});

  /** The selected category object (drives sensitive/anonymity UI). */
  protected readonly selectedCategory = computed<IssueCategory | null>(() => {
    const id = this.form.controls.categoryId.value;
    return this.categories().find((c) => c.id === id) ?? null;
  });

  /** Whether the chosen category is sensitive (enables the anonymity toggle, forces PRIVATE). */
  protected readonly isSensitive = computed(() => this.selectedCategory()?.sensitive ?? false);

  /** The report form. Title ≤200, description ≤4000 per FileReportDto. */
  protected readonly form = this.fb.nonNullable.group({
    categoryId: ['', Validators.required],
    title: ['', [Validators.required, Validators.maxLength(200)]],
    description: ['', [Validators.required, Validators.maxLength(4000)]],
    anonymous: [false],
  });

  constructor() {
    this.reports.listCategories().subscribe({
      next: (cats) => this.categories.set(cats.filter((c) => c.active)),
      error: () => {},
    });
  }

  /** Ward chosen in the picker. */
  protected onWardSelected(ward: WardSummary): void {
    this.ward.set(ward);
  }

  /**
   * Opt-in GPS capture. Only invoked on an explicit tap (privacy). Coarse failure (denied/unavailable) is
   * surfaced gently; the report can still be filed with just the ward.
   */
  protected useMyLocation(): void {
    if (!('geolocation' in navigator)) {
      this.toast.info(this.translate.instant('report.geoUnavailable'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        this.geo.set({ lat: pos.coords.latitude, lng: pos.coords.longitude });
        this.toast.success(this.translate.instant('report.geoCaptured'));
      },
      () => this.toast.info(this.translate.instant('report.geoDenied')),
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 60000 },
    );
  }

  /** Clears a captured GPS point. */
  protected clearLocation(): void {
    this.geo.set(null);
  }

  /** Submits the report (online) or saves a draft (offline). */
  protected submit(): void {
    this.fieldErrors.set({});
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const ward = this.ward();
    if (!ward) {
      this.toast.error(this.translate.instant('report.wardRequired'));
      return;
    }

    const cat = this.selectedCategory();
    const point = this.geo();
    const request: FileReportRequest = {
      categoryId: this.form.controls.categoryId.value,
      title: this.form.controls.title.value.trim(),
      description: this.form.controls.description.value.trim(),
      wardId: ward.id,
      latitude: point?.lat ?? null,
      longitude: point?.lng ?? null,
      // A force-private category overrides visibility; otherwise leave to the category default (null).
      visibility: cat?.forcePrivate ? 'PRIVATE' : null,
      anonymous: this.isSensitive() ? this.form.controls.anonymous.value : false,
    };

    this.busy.set(true);
    this.reports
      .file(request)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (report) => {
          if (report) {
            // Sent online → go to its tracking page.
            this.toast.success(this.translate.instant('report.filed', { code: report.code }));
            void this.router.navigate(['/track', report.id]);
          } else {
            // Saved offline as a draft → go to the track list where the pending draft shows.
            void this.router.navigate(['/track']);
          }
        },
        error: (err) => this.applyServerErrors(err),
      });
  }

  /** Maps server-side validation errors to the form fields for inline display. */
  private applyServerErrors(err: unknown): void {
    if (err instanceof ApiError && err.isValidation) {
      const map: Record<string, string> = {};
      err.errors.forEach((e: ErrorDetail) => {
        if (e.field) {
          map[e.field] = e.message;
        }
      });
      this.fieldErrors.set(map);
    }
    // Tier-too-low / other errors are toasted centrally by the interceptor.
  }
}
