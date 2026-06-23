import {
  Component,
  DestroyRef,
  forwardRef,
  inject,
  Input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';

import { GeographyService } from '../../features/geography/geography.service';
import { WardSummary } from '../../features/geography/geography.models';

/**
 * Multi-select typeahead picker for coverage/target areas, bindable as a reactive-form control via
 * {@link ControlValueAccessor} (PRD §24 responder coverage; §12 announcement targeting).
 *
 * <p>Responsibility: replace the comma-separated area-UUID text input (responder {@code coverageAreaIds};
 * announcement {@code areaIds}) with a ward (Kata) typeahead. The geo-area unit a client pins by hand is
 * the ward — the same {@code GET /wards?q=} search the manual ward picker uses (PRD §9.0, §22.6) — so this
 * is the multi-select sibling of {@code app-ward-picker}: type a ward name, add matches as chips. The bound
 * control value is a {@code string[]} of ward public ids (the shape the API expects).</p>
 *
 * <p>WHY ward-level areas: the platform's pin-by-hand granularity is the ward (PRD §9.0); coarser scopes
 * (region/district/council) are selected via the responder's {@code coverageType} token, while CUSTOM/WARD
 * coverage enumerates specific wards here. WHY {@code string[]} CVA: the form models the ids as a list;
 * emitting a clean array removes the brittle split/trim of the legacy comma input (DRY, CLAUDE.md §8).</p>
 *
 * <p>Data cost (PRD §15): debounced 300ms, de-duped, prior request cancelled via {@code switchMap}; an
 * empty box issues no request. Accessibility (WCAG 2.1 AA): labelled combobox, keyboard-operable options,
 * removable chips with accessible-named clear buttons, live busy/empty regions.</p>
 */
@Component({
  selector: 'app-area-picker',
  standalone: true,
  imports: [TranslateModule],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => AreaPickerComponent),
      multi: true,
    },
  ],
  templateUrl: './area-picker.component.html',
})
export class AreaPickerComponent implements ControlValueAccessor {
  private readonly geography = inject(GeographyService);
  private readonly destroyRef = inject(DestroyRef);

  /** DOM id base so the label/input/listbox wire up for screen readers; unique per instance. */
  @Input() inputId = `area-picker-${Math.random().toString(36).slice(2, 8)}`;

  /** Optional district public id to scope the ward search to one Wilaya. */
  @Input() districtId: string | null = null;

  /** The current search text. */
  readonly query = signal('');
  /** The current match list (excludes already-selected wards). */
  readonly results = signal<WardSummary[]>([]);
  /** Whether a search request is in flight. */
  readonly searching = signal(false);
  /** Whether the results dropdown is open. */
  readonly open = signal(false);
  /** Whether the control is disabled. */
  readonly disabled = signal(false);

  /** The selected ward public ids (the bound control value). */
  readonly selectedIds = signal<string[]>([]);
  /** Friendly labels for the selected ward ids, resolved as the user adds them. */
  private readonly labels = new Map<string, string>();

  private readonly searchTerm$ = new Subject<string>();
  private onChange: (value: string[]) => void = () => {};
  private onTouched: () => void = () => {};

  constructor() {
    this.searchTerm$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          this.searching.set(true);
          return this.geography.searchWards(q, {
            districtId: this.districtId ?? undefined,
            size: 10,
          });
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (page) => {
          const chosen = new Set(this.selectedIds());
          this.results.set(page.content.filter((w) => !chosen.has(w.id)));
          this.searching.set(false);
          this.open.set(true);
        },
        error: () => {
          this.results.set([]);
          this.searching.set(false);
        },
      });
  }

  /** Tracks input text and feeds the debounced search (blank → no results). */
  onInput(raw: string): void {
    this.query.set(raw);
    const trimmed = raw.trim();
    if (trimmed.length === 0) {
      this.results.set([]);
      this.open.set(false);
      return;
    }
    this.searchTerm$.next(trimmed);
  }

  /** Adds a ward to the coverage selection and notifies the form. */
  add(ward: WardSummary): void {
    if (this.selectedIds().includes(ward.id)) {
      return;
    }
    this.labels.set(ward.id, this.labelFor(ward));
    this.selectedIds.update((ids) => [...ids, ward.id]);
    this.query.set('');
    this.results.set([]);
    this.open.set(false);
    this.emit();
    this.onTouched();
  }

  /** Removes a ward from the selection and notifies the form. */
  remove(id: string): void {
    this.selectedIds.update((ids) => ids.filter((x) => x !== id));
    this.emit();
    this.onTouched();
  }

  /** Closes the dropdown on blur (deferred so a click on an option still registers) and marks touched. */
  onBlur(): void {
    this.onTouched();
    setTimeout(() => this.open.set(false), 150);
  }

  /** The chip label for a selected id (friendly name if known, else the raw id from a pre-filled edit). */
  chipLabel(id: string): string {
    return this.labels.get(id) ?? id;
  }

  /** Builds a "Ward — Council, District" label for a match, omitting unresolved ancestors. */
  labelFor(ward: WardSummary): string {
    const ancestry = [ward.councilName, ward.districtName].filter((p): p is string => !!p).join(', ');
    return ancestry ? `${ward.name} — ${ancestry}` : ward.name;
  }

  // --- ControlValueAccessor ---

  /** Forms API → component: pre-fills the selected ward ids (e.g. on edit). */
  writeValue(value: string[] | null): void {
    this.selectedIds.set(Array.isArray(value) ? [...value] : []);
  }

  /** Registers the change callback used to push the selected id array into the form control. */
  registerOnChange(fn: (value: string[]) => void): void {
    this.onChange = fn;
  }

  /** Registers the touched callback. */
  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  /** Reflects the form's disabled state into the template. */
  setDisabledState(isDisabled: boolean): void {
    this.disabled.set(isDisabled);
  }

  /** Pushes the current selection to the form control. */
  private emit(): void {
    this.onChange(this.selectedIds());
  }
}
