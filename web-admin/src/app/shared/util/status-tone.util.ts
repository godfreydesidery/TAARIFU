import { StatusTone } from '../components/status-badge.component';

/**
 * Maps a backend status/priority token to a {@link StatusTone} for the soft status badge.
 *
 * <p>Responsibility: ONE place that decides which colour a domain status reads as, so the report queue,
 * case detail, responder list, moderation queue, and user directory all colour the same status the same
 * way (DRY, CLAUDE.md §3). The mapping is conservative: terminal/positive → success, in-flight → info,
 * needs-attention → warning, failure/breach → danger, everything unknown → neutral.</p>
 *
 * @param token a status/priority token (case-insensitive), e.g. `RESOLVED`, `URGENT`, `PENDING`.
 * @returns the badge tone to use.
 */
export function statusTone(token: string | null | undefined): StatusTone {
  switch ((token ?? '').toUpperCase()) {
    // Positive / done.
    case 'RESOLVED':
    case 'CONFIRMED':
    case 'CLOSED':
    case 'ACTIVE':
    case 'SITTING':
    case 'PUBLISHED':
    case 'VERIFIED':
    case 'ACTIONED':
    case 'UPHELD':
    case 'SUCCEEDED':
      return 'success';

    // In-flight / informational.
    case 'IN_PROGRESS':
    case 'TRIAGED':
    case 'ASSIGNED':
    case 'IN_REVIEW':
    case 'SCHEDULED':
    case 'NORMAL':
    case 'REFUNDED':
      return 'info';

    // Needs attention.
    case 'SUBMITTED':
    case 'PENDING':
    case 'INITIATED':
    case 'PENDING_VERIFICATION':
    case 'ESCALATED':
    case 'HIGH':
    case 'OPEN':
    case 'DRAFT':
      return 'warning';

    // Failure / breach / negative.
    case 'REJECTED':
    case 'SUSPENDED':
    case 'DISABLED':
    case 'URGENT':
    case 'CRITICAL':
    case 'OVERTURNED':
    case 'EXPIRED':
      return 'danger';

    // De-emphasised.
    case 'DUPLICATE':
    case 'DISMISSED':
    case 'FORMER':
    case 'LOW':
    case 'VOIDED':
      return 'neutral';

    default:
      return 'neutral';
  }
}
