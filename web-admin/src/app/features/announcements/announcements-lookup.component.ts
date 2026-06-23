import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

/**
 * Announcements landing — a lookup-by-id entry to the detail view, with an explicit API-gap note.
 *
 * <p>Responsibility: until the backend exposes a console-wide announcements list/queue (only the public
 * {@code GET /announcements/{id}} detail read and the author-scoped {@code GET /announcements/mine} exist
 * today), this gives staff a humane way in: paste/enter a published announcement's public id and open its
 * detail. It states the gap plainly rather than faking a list — consistent with the existing graceful
 * in-UI gap notes elsewhere in the console (e.g. users directory).</p>
 *
 * <p>WHY a lookup (not a fabricated list): inventing a list with no backing endpoint would mislead; a
 * by-id lookup is honest, immediately useful for verifying a specific announcement, and trivially
 * replaceable by a real list once the endpoint lands.</p>
 */
@Component({
  selector: 'app-announcements-lookup',
  standalone: true,
  imports: [FormsModule, TranslateModule],
  templateUrl: './announcements-lookup.component.html',
})
export class AnnouncementsLookupComponent {
  private readonly router = inject(Router);

  /** The announcement id the user typed/pasted. */
  readonly idInput = signal('');

  /** Navigates to the detail view for the entered id. No-ops on blank input. */
  open(): void {
    const id = this.idInput().trim();
    if (id.length === 0) {
      return;
    }
    void this.router.navigate(['/announcements', id]);
  }
}
