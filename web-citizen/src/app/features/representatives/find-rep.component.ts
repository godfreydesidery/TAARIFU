import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { finalize } from 'rxjs/operators';

import { AreaPickerComponent } from '../../shared/area-picker.component';
import { MyRepresentatives } from './representatives.models';
import { RepresentativesService } from './representatives.service';
import { WardSummary } from '../report/geography.service';

/**
 * "Find my representative" — pick your ward (Kata) and see your MP (Mbunge) and Councillor (Diwani).
 *
 * <p>Responsibility: the public discovery flow for accountability (PRD §8). The citizen resolves their area
 * via the reusable {@link AreaPickerComponent} (Mkoa → Wilaya → Kata), then we call
 * `/representatives/by-ward/{wardId}` and present the MP for the ward's constituency (Jimbo) and the
 * Councillor for the ward. No login required (guest-readable, SW-cached). Each rep card shows name-less
 * but role/party/area context from the summary DTO; correct Swahili civic terms are used throughout.</p>
 */
@Component({
  selector: 'app-find-rep',
  standalone: true,
  imports: [TranslateModule, AreaPickerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './find-rep.component.html',
  styleUrl: './find-rep.component.scss',
})
export class FindRepComponent {
  private readonly reps = inject(RepresentativesService);

  /** The resolved representatives for the chosen ward, or null before a ward is picked. */
  protected readonly result = signal<MyRepresentatives | null>(null);
  /** True while resolving. */
  protected readonly loading = signal(false);
  /** The chosen ward label for display. */
  protected readonly wardLabel = signal<string>('');

  /** Ward chosen in the picker → resolve the citizen's representatives. */
  protected onWardSelected(ward: WardSummary): void {
    this.wardLabel.set(`${ward.name} — ${ward.councilName}`);
    this.loading.set(true);
    this.result.set(null);
    this.reps
      .findByWard(ward.id)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (res) => this.result.set(res),
        // Error is toasted centrally; leave the result null so the UI shows the empty/retry path.
        error: () => {},
      });
  }
}
