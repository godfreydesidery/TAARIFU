import {
  Component,
  DestroyRef,
  forwardRef,
  inject,
  Input,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';

import { CategoryService } from '../../features/categories/category.service';
import { IssueCategory } from '../../features/categories/category.models';

/**
 * Multi-select typeahead picker for reportable issue categories (Aina za Matatizo), bindable as a
 * reactive-form control via {@link ControlValueAccessor} (PRD §9.1, §24).
 *
 * <p>Responsibility: replace the comma-separated category-UUID text input in the responder-capability form
 * (and any "which categories does this responder handle / this rule cover" surface) with a humane chooser.
 * The active taxonomy is small and stable, so this fetches the ACTIVE category list once
 * ({@code GET /issue-categories}) and filters client-side as the admin types — no per-keystroke round-trip
 * on a slow link (PRD §15). Selected categories are held as chips; the bound control value is a
 * {@code string[]} of category public ids (the shape the API expects for {@code handledCategoryIds}).</p>
 *
 * <p>WHY a {@code ControlValueAccessor} writing {@code string[]} (not a comma string): the form already
 * models {@code handledCategoryIds} as an id list; emitting a clean array removes the brittle
 * split/trim/parse step and lets the form bind with {@code formControlName} directly (DRY, CLAUDE.md §8).
 * WHY the active-only list: retired categories must not be selectable for new routing (a retired category
 * is hidden from the citizen picker; routing to it would strand reports).</p>
 *
 * <p>Accessibility (WCAG 2.1 AA): a labelled search input, a keyboard-operable option list, and removable
 * selection chips each with an accessible-named clear button. Empty/loading/error states are surfaced.</p>
 */
@Component({
  selector: 'app-category-picker',
  standalone: true,
  imports: [TranslateModule],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => CategoryPickerComponent),
      multi: true,
    },
  ],
  templateUrl: './category-picker.component.html',
})
export class CategoryPickerComponent implements OnInit, ControlValueAccessor {
  private readonly categories = inject(CategoryService);
  private readonly destroyRef = inject(DestroyRef);

  /** DOM id base so the label/input/listbox wire up for screen readers; unique per instance. */
  @Input() inputId = `category-picker-${Math.random().toString(36).slice(2, 8)}`;

  /** The current search text. */
  readonly query = signal('');
  /** The full active taxonomy, fetched once. */
  private readonly all = signal<IssueCategory[]>([]);
  /** Whether the taxonomy is loading. */
  readonly loading = signal(false);
  /** Whether the taxonomy failed to load (the picker degrades to a disabled state, never blocks the form). */
  readonly errored = signal(false);
  /** Whether the results dropdown is open. */
  readonly open = signal(false);
  /** Whether the control is disabled (set by the forms API). */
  readonly disabled = signal(false);

  /** The selected category public ids (the bound control value). */
  readonly selectedIds = signal<string[]>([]);

  /** CVA callbacks. */
  private onChange: (value: string[]) => void = () => {};
  private onTouched: () => void = () => {};

  /** Loads the active taxonomy once on init. */
  ngOnInit(): void {
    this.loading.set(true);
    this.categories
      // A large size pulls the whole small taxonomy in one call; sort by name for a stable list.
      .listActive({ page: 0, size: 100, sort: 'name,asc' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.all.set(page.content);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });
  }

  /** Matches for the typed query that are NOT already selected (case-insensitive, name or code). */
  filtered(): IssueCategory[] {
    const q = this.query().trim().toLowerCase();
    const chosen = new Set(this.selectedIds());
    return this.all()
      .filter((c) => !chosen.has(c.id))
      .filter((c) => !q || c.name.toLowerCase().includes(q) || c.code.toLowerCase().includes(q))
      .slice(0, 20);
  }

  /** The selected categories, resolved to their full objects for chip rendering. */
  selectedCategories(): IssueCategory[] {
    const chosen = new Set(this.selectedIds());
    return this.all().filter((c) => chosen.has(c.id));
  }

  /** Tracks input text and opens the dropdown. */
  onInput(raw: string): void {
    this.query.set(raw);
    this.open.set(true);
  }

  /** Adds a category to the selection and notifies the form. */
  add(category: IssueCategory): void {
    if (this.selectedIds().includes(category.id)) {
      return;
    }
    this.selectedIds.update((ids) => [...ids, category.id]);
    this.query.set('');
    this.open.set(false);
    this.emit();
    this.onTouched();
  }

  /** Removes a category from the selection and notifies the form. */
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

  /** A best-effort label for a selected id even before the taxonomy resolves (falls back to the id). */
  labelFor(id: string): string {
    return this.all().find((c) => c.id === id)?.name ?? id;
  }

  // --- ControlValueAccessor ---

  /** Forms API → component: pre-fills the selected ids (e.g. on edit). */
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
