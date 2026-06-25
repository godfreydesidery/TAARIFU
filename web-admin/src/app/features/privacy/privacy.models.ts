/**
 * Data-Subject-Request (DSR) admin DTOs — mirror the backend privacy/admin contract for handling PDPA
 * data-subject ACCESS (export) and ERASURE (anonymisation) requests (UC-A17 / UC-S09; PRD §15, §25.1, §18).
 *
 * <p>WHY these shapes: under the Tanzania PDPA (2022/2023) a data subject may request a copy of their data
 * (access/export) or its erasure. The platform's policy (PRD §25.1) is "erasure = anonymisation, not deletion
 * of the civic record": PII is severed and replaced with a tombstone while counts/audit persist. These shapes
 * back the operator console that lists and actions those requests against the privacy/admin endpoints. The
 * SLA is fixed in policy — acknowledge ≤72h, complete ≤30 days (§25.1) — and surfaced here as a derived due
 * date so an operator can triage overdue requests.</p>
 *
 * <p>PII discipline (PRD §18, PDPA): a DSR row identifies the SUBJECT only by their account public id and a
 * MASKED contact — never a raw phone/email or national/voter ID. The console acts on the immutable
 * {@link DsrRequest.id}; it never renders the subject's actual PII (that would defeat the erasure it is
 * helping to perform).</p>
 */

/** DSR kind tokens (mirror the backend `DsrType`). ACCESS = data export; ERASURE = anonymisation (§25.1). */
export const DSR_TYPES = ['ACCESS', 'ERASURE'] as const;

/** One DSR type token. */
export type DsrType = (typeof DSR_TYPES)[number];

/**
 * DSR lifecycle status tokens (mirror the backend `DsrStatus`). PENDING → ACKNOWLEDGED → (IN_PROGRESS) →
 * COMPLETED, or REJECTED. A legal hold (PRD §25.1) parks a request without progressing it.
 */
export const DSR_STATUSES = [
  'PENDING',
  'ACKNOWLEDGED',
  'IN_PROGRESS',
  'COMPLETED',
  'REJECTED',
  'ON_HOLD',
] as const;

/** One DSR status token. */
export type DsrStatus = (typeof DSR_STATUSES)[number];

/**
 * One data-subject request row (mirrors the backend `DsrRequestDto`). PII-minimised: the subject is a public
 * id + masked contact only; no raw PII (PRD §18, §25.1).
 */
export interface DsrRequest {
  /** The request's immutable public id (the id every action command targets). */
  id: string;
  /** Request kind: data export (`ACCESS`) or anonymisation (`ERASURE`). */
  type: DsrType;
  /** Lifecycle status name. */
  status: DsrStatus;
  /** The subject account's public id (never the raw identity). */
  subjectAccountId: string;
  /** Masked subject contact for operator orientation (e.g. `+2557****1234`), or `null`. */
  subjectMaskedContact: string | null;
  /** Optional machine reason / channel the request came in on (e.g. `IN_APP`, `EMAIL`), or `null`. */
  channel: string | null;
  /** When the subject raised the request (ISO-8601). */
  requestedAt: string;
  /**
   * The completion-due instant (ISO-8601) per the §25.1 SLA (≤30 days from request), or `null` if the
   * server doesn't compute it. The UI flags a still-open request past this as overdue.
   */
  dueAt: string | null;
  /** When the request was acknowledged (ISO-8601), or `null` if not yet acknowledged. */
  acknowledgedAt: string | null;
  /** When the request was completed/closed (ISO-8601), or `null` if still open. */
  completedAt: string | null;
  /** `true` if the subject holds an active staff/representative role blocking self-erasure (note ᵇ, §25.1). */
  legalHold: boolean;
}

/** Request body for `POST /privacy/admin/dsr/{id}/acknowledge` — record acknowledgement (starts the SLA clock). */
export interface AcknowledgeDsrRequest {
  /** Optional internal note (never the subject's PII). */
  note?: string;
}

/**
 * Request body for `POST /privacy/admin/dsr/{id}/complete` — mark the export delivered / anonymisation done.
 * The actual export/anonymisation job runs server-side (UC-S09); this records the operator's completion.
 */
export interface CompleteDsrRequest {
  /** Optional internal note (e.g. delivery channel), never the subject's PII. */
  note?: string;
}

/** Request body for `POST /privacy/admin/dsr/{id}/reject` — reject with a required machine reason code. */
export interface RejectDsrRequest {
  /** Machine reason for rejection (e.g. `IDENTITY_UNVERIFIED`, `LEGAL_HOLD`); ≤64 chars, never PII. */
  reasonCode: string;
}
