import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

/**
 * Admin dashboard landing page.
 *
 * <p>Responsibility: the post-login home — a lightweight, link-only overview that routes the operator to
 * the three feature areas (geography, representatives/parties, issue categories). Intentionally
 * data-free for now (no extra API calls on landing) to keep the first authenticated paint fast and cheap
 * on a low-end device / slow link (PRD §15). It establishes the page pattern that richer stat widgets
 * can later slot into via `@defer`.</p>
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink, TranslateModule],
  templateUrl: './dashboard.component.html',
})
export class DashboardComponent {}
