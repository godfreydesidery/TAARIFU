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
 * Typeahead picker for a single ward (Kata), bindable as a reactive-form control via
 * {@link ControlValueAccessor} — the GPS-free "manual ward picker" the PRD calls for (PRD §9.0, §22.6).
 *
 * <p>Responsibility: replace the raw ward-UUID text inputs in the responder, representative, profile and
 * report forms with a humane search. The admin types a ward name; the component debounces, calls
 * {@code GET /wards?q=} (optionally district-scoped) via {@link GeographyService}, and shows matches with
 * their council + district names so same-named wards are distinguishable. Selecting a row writes the ward
 * <b>public id</b> to the bound form control (the value the API expects), so callers keep their existing
 * {@code formControlName} and validation; nothing downstream changes shape.</p>
 *
 * <p>WHY a {@code ControlValueAccessor} (not just inputs/outputs): the forms already use typed reactive
 * controls (e.g. {@code wardId}); implementing CVA lets the picker drop in behind {@code formControlName}
 * with zero glue, preserving server-error mapping and {@code getRawValue()} flows (DRY, CLAUDE.md §3/§8).</p>
 *
 * <p>Data cost (PRD §15): an empty box issues NO request (the server returns an empty page for blank
 * {@code q} anyway); we debounce 300ms and de-dup so a low-data-mode user on a 2G link does not spray
 * queries per keystroke. Results are capped to a small page. Accessibility (WCAG 2.1 AA): a labelled
 * combobox-style input with a keyboard-operable results list, {@code role="status"} live region for the
 * busy/empty states, and visible selected-ward confirmation with a clear button.</p>
 */
@Component({
  selector: 'app-ward-picker',
  standalone: true,
  imports: [TranslateModule],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => WardPickerComponent),
      multi: true,
    },
  ],
  templateUrl: './ward-picker.component.html',
})
export class WardPickerComponent implements ControlValueAccessor {
  private readonly geography = inject(GeographyService);
  private readonly destroyRef = inject(DestroyRef);

  /** DOM id base so the label/input/listbox wire up for screen readers; unique per instance. */
  @Input() inputId = `ward-picker-${Math.random().toString(36).slice(2, 8)}`;

  /** Optional district public id to scope the search to one Wilaya (narrows + speeds the typeahead). */
  @Input() districtId: string | null = null;

  /** The current search text the user has typed. */
  readonly query = signal('');
  /** The current match list for the typed query. */
  readonly results = signal<WardSummary[]>([]);
  /** Whether a search request is in flight (drives the busy live region). */
  readonly searching = signal(false);
  /** Whether the results dropdown is open. */
  readonly open = signal(false);
  /** The label of the currently selected ward (id is held in {@link value}); `null` when none. */
  readonly selectedLabel = signal<string | null>(null);
  /** Whether the control is disabled (set by the forms API). */
  readonly disabled = signal(false);

  /** The bound ward public id, or `null`. The value the form control holds. */
  private value: string | null = null;

  /** Debounced search pipeline: keystroke → 300ms debounce → de-dup → cancel-prior request. */
  private readonly searchTerm$ = new Subject<string>();

  /** CVA change callback; pushes the selected ward id into the form control. */
  private onChange: (value: string | null) => void = () => {};
  /** CVA touched callback; fires on blur so validation/`touched` styling works. */
  private onTouched: () => void = () => {};

  constructor() {
    // switchMap cancels an in-flight request when the user types again, so only the latest query's
    // results land (no out-of-order flicker on a slow link).
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
          this.results.set(page.content);
          this.searching.set(false);
          this.open.set(true);
        },
        error: () => {
          // Fail safe: a search failure clears results but never blocks the form (PRD fail-safe).
          this.results.set([]);
          this.searching.set(false);
        },
      });
  }

  /** Handles each keystroke: tracks the query and feeds the debounced pipeline (blank → no results). */
  onInput(raw: string): void {
    this.query.set(raw);
    // A new free-text edit invalidates any prior selection until the user picks a row again.
    if (this.selectedLabel() !== null) {
      this.selectedLabel.set(null);
      this.writeValueInternal(null);
    }
    const trimmed = raw.trim();
    if (trimmed.length === 0) {
      this.results.set([]);
      this.open.set(false);
      return;
    }
    this.searchTerm$.next(trimmed);
  }

  /** Selects a ward row: writes its id to the control, shows a confirmation label, closes the list. */
  select(ward: WardSummary): void {
    this.writeValueInternal(ward.id);
    this.selectedLabel.set(this.labelFor(ward));
    this.query.set('');
    this.results.set([]);
    this.open.set(false);
    this.onTouched();
  }

  /** Clears the current selection (back to "no ward chosen"). */
  clear(): void {
    this.writeValueInternal(null);
    this.selectedLabel.set(null);
    this.query.set('');
    this.results.set([]);
    this.open.set(false);
    this.onTouched();
  }

  /** Closes the dropdown on blur (deferred so a click on a result still registers) and marks touched. */
  onBlur(): void {
    this.onTouched();
    setTimeout(() => this.open.set(false), 150);
  }

  /** Builds a human label "Ward — Council, District" for a match, omitting unresolved ancestors. */
  labelFor(ward: WardSummary): string {
    const ancestry = [ward.councilName, ward.districtName].filter((p): p is string => !!p).join(', ');
    return ancestry ? `${ward.name} — ${ancestry}` : ward.name;
  }

  // --- ControlValueAccessor ---

  /** Forms API → component: pre-fills the held id (e.g. on edit). The label resolves once the user searches. */
  writeValue(value: string | null): void {
    this.value = value && value.length > 0 ? value : null;
    // We only have the id (not the name) on write; show the id as a fallback confirmation so an edit form
    // doesn't look empty. The label upgrades to the friendly name once the user re-picks from search.
    this.selectedLabel.set(this.value ? this.value : null);
  }

  /** Registers the change callback used to push the selected id into the form control. */
  registerOnChange(fn: (value: string | null) => void): void {
    this.onChange = fn;
  }

  /** Registers the touched callback used to flip the control's `touched` state on blur. */
  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  /** Reflects the form's disabled state into the template. */
  setDisabledState(isDisabled: boolean): void {
    this.disabled.set(isDisabled);
  }

  /** Sets the held value and notifies the form control (single write path). */
  private writeValueInternal(value: string | null): void {
    this.value = value;
    this.onChange(value);
  }
}
