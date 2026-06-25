/**
 * TypeScript mirror of the backend's single response envelope `ApiResponse<T>` (PRD §17). Used to type
 * the raw HTTP body BEFORE the {@link ApiResponseInterceptor} unwraps it.
 *
 * <p>The backend wraps EVERY response — success and error — in this shape. On success `data` is the
 * payload; on error `data` is an {@link ApiError} carrying the stable machine `code` and (for validation
 * failures) field `errors[]`. The top-level `statusCode` is the integer HTTP status; the
 * language-independent machine code lives at `data.code` on error.</p>
 *
 * @typeParam T the success payload type.
 */
export interface ApiResponse<T> {
  /** Boolean outcome flag. */
  success: boolean;
  /** Integer HTTP status (200, 201, 202, 400, 403, 404, 409, 429, 500 …). */
  statusCode: number;
  /** Localised human-readable message (Swahili default, English secondary). Safe to show in a toast. */
  message: string;
  /** On success the payload `T`; on error an {@link ApiError}. May be absent (omitted as `null`). */
  data: T | null;
  /** Pagination metadata for paged responses; `null`/absent otherwise. */
  meta?: PageMeta | null;
  /** Server response instant, ISO-8601 UTC. */
  timestamp: string;
}

/**
 * Pagination metadata carried in {@link ApiResponse.meta} for paged list endpoints. Tells the client how
 * to page without re-deriving it from the payload.
 */
export interface PageMeta {
  /** Zero-based index of the returned page. */
  page: number;
  /** Page size actually applied (after the server-side cap of 100). */
  size: number;
  /** Total number of matching elements across all pages. */
  total: number;
  /** Total number of pages given {@link size}. */
  totalPages: number;
}

/**
 * Structured error payload carried inside {@link ApiResponse.data} on EVERY error response. The client
 * branches on {@link code}, NEVER on the localised `message`.
 */
export interface ApiErrorBody {
  /**
   * Stable, language-independent machine error code (e.g. `TIER_TOO_LOW`, `NOT_FOUND`,
   * `VALIDATION_FAILED`, `UNAUTHENTICATED`). The discriminator the UI branches on — several distinct
   * domain errors share one HTTP status, so the status alone is insufficient.
   */
  code: string;
  /** Field-level Bean Validation failures for a `VALIDATION_FAILED` response; absent otherwise. */
  errors?: ErrorDetail[];
}

/**
 * A single field-level validation error entry returned inside `data.errors[]`. Lets a form highlight the
 * exact offending control.
 */
export interface ErrorDetail {
  /** The rejected field path (e.g. `title`), or `null` for object-level errors. */
  field: string | null;
  /** Stable machine code for the violated constraint (e.g. `NotBlank`). */
  code: string;
  /** Localised human-readable description of the violation. */
  message: string;
}

/**
 * The decoded result of a successful PAGED request, produced by the {@link ApiClient} after unwrapping
 * the envelope: the list `content` plus its {@link PageMeta}.
 *
 * @typeParam T the row type.
 */
export interface Page<T> {
  /** The page of rows. */
  content: T[];
  /** Page metadata (page, size, total, totalPages). */
  meta: PageMeta;
}
