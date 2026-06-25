import { ChangeDetectionStrategy, Component, EventEmitter, OnInit, Output, inject, signal } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { District, GeographyService, Region, WardSummary } from '../features/report/geography.service';

/**
 * A cascading Tanzanian administrative area picker: Mkoa (Region) → Wilaya (District) → Kata (Ward).
 *
 * <p>Responsibility: a reusable, accessible control that resolves a citizen down to a WARD — the minimum
 * pin granularity for both filing a report and finding a representative (PRD §9.0). Each level lazily loads
 * the next only when the parent is chosen (data-cost discipline; the lists are SW-cached). Uses correct
 * Swahili civic labels (Mkoa/Wilaya/Kata). Emits the chosen {@link WardSummary} via {@link wardSelected}
 * so the host (file-report, find-rep) stays decoupled from the geography plumbing (SRP/DRY).</p>
 *
 * <p>Accessibility: native `<select>`s (best screen-reader + low-end support), explicit labels, and a busy
 * hint while a level loads.</p>
 */
@Component({
  selector: 'app-area-picker',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="area-picker">
      <div class="mb-2">
        <label class="form-label" for="ap-region">{{ 'geo.region' | translate }}</label>
        <select id="ap-region" class="form-select form-select-lg" (change)="onRegion($any($event.target).value)">
          <option value="">{{ 'geo.choose' | translate }}</option>
          @for (r of regions(); track r.id) {
            <option [value]="r.id">{{ r.name }}</option>
          }
        </select>
      </div>

      @if (districts().length) {
        <div class="mb-2">
          <label class="form-label" for="ap-district">{{ 'geo.district' | translate }}</label>
          <select id="ap-district" class="form-select form-select-lg" (change)="onDistrict($any($event.target).value)">
            <option value="">{{ 'geo.choose' | translate }}</option>
            @for (d of districts(); track d.id) {
              <option [value]="d.id">{{ d.name }}</option>
            }
          </select>
        </div>
      }

      @if (wards().length) {
        <div class="mb-2">
          <label class="form-label" for="ap-ward">{{ 'geo.ward' | translate }}</label>
          <select id="ap-ward" class="form-select form-select-lg" (change)="onWard($any($event.target).value)">
            <option value="">{{ 'geo.choose' | translate }}</option>
            @for (w of wards(); track w.id) {
              <option [value]="w.id">{{ w.name }} — {{ w.councilName }}</option>
            }
          </select>
        </div>
      }

      @if (loading()) {
        <p class="text-muted small" aria-live="polite">{{ 'common.loading' | translate }}</p>
      }
    </div>
  `,
})
export class AreaPickerComponent implements OnInit {
  private readonly geo = inject(GeographyService);

  /** Emits the chosen ward (the resolved area) when the citizen picks a Kata. */
  @Output() readonly wardSelected = new EventEmitter<WardSummary>();

  /** Loaded regions. */
  protected readonly regions = signal<Region[]>([]);
  /** Districts of the chosen region. */
  protected readonly districts = signal<District[]>([]);
  /** Wards of the chosen district. */
  protected readonly wards = signal<WardSummary[]>([]);
  /** True while any level is loading. */
  protected readonly loading = signal(false);

  /** Loads the top-level region list on init. */
  ngOnInit(): void {
    this.loading.set(true);
    this.geo.listRegions().subscribe({
      next: (regions) => {
        this.regions.set(regions);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  /** Region chosen → load its districts; clear deeper levels. */
  protected onRegion(regionId: string): void {
    this.districts.set([]);
    this.wards.set([]);
    if (!regionId) {
      return;
    }
    this.loading.set(true);
    this.geo.listDistricts(regionId).subscribe({
      next: (districts) => {
        this.districts.set(districts);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  /** District chosen → load its wards; clear deeper levels. */
  protected onDistrict(districtId: string): void {
    this.wards.set([]);
    if (!districtId) {
      return;
    }
    this.loading.set(true);
    this.geo.listWards(districtId).subscribe({
      next: (wards) => {
        this.wards.set(wards);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  /** Ward chosen → emit the full ward summary to the host. */
  protected onWard(wardId: string): void {
    const ward = this.wards().find((w) => w.id === wardId);
    if (ward) {
      this.wardSelected.emit(ward);
    }
  }
}
