import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

import { environment } from '../../../environments/environment';
import { ApiResponse, Page } from './api-response.model';

/**
 * Thin, typed HTTP gateway over the Taarifu backend.
 *
 * <p>Responsibility: the ONE place that knows the API base URL ({@link environment.apiUrl}) and how to
 * UNWRAP the response envelope (ARCHITECTURE.md §5.1). Feature services call {@link get}/{@link getPage}/
 * {@link post}/{@link put}/{@link del} and receive the `data` payload directly — never the
 * `{ success, statusCode, message, data, meta }` wrapper. Error handling/normalisation is done centrally
 * by the {@link ApiResponseInterceptor}; auth headers + `Accept-Language` are added by the
 * {@link AuthInterceptor}. This keeps every feature service free of URL strings and envelope plumbing
 * (DRY, CLAUDE.md §3/§8).</p>
 *
 * <p>WHY a service and not just raw `HttpClient`: the unwrap of `data` and the page-meta extraction are
 * cross-cutting; centralising them stops 20 feature services from re-implementing (and drifting on)
 * envelope parsing.</p>
 */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.apiUrl;

  /**
   * GETs a single-object resource and unwraps `data`.
   *
   * @typeParam T the payload type.
   * @param path resource path relative to the API base (e.g. `/regions/{id}`); a leading slash optional.
   * @param params optional query params.
   * @returns the unwrapped `data` payload.
   */
  get<T>(path: string, params?: Record<string, string | number | boolean | undefined>): Observable<T> {
    return this.http
      .get<ApiResponse<T>>(this.url(path), { params: this.toParams(params) })
      .pipe(map((res) => res.data as T));
  }

  /**
   * GETs a PAGED list resource and unwraps both the row `content` and the {@link Page.meta}.
   *
   * <p>Backend paged endpoints return `data` = the row array and `meta` = `{page,size,total,totalPages}`.
   * This method bundles them into a {@link Page} so list components render server-side pagination from
   * the authoritative server counts (not client guesses).</p>
   *
   * @typeParam T the row type.
   * @param path resource path (e.g. `/regions`).
   * @param params query params — typically `page`, `size`, `sort`, `q`, plus per-resource filters.
   * @returns a {@link Page} of `T`.
   */
  getPage<T>(path: string, params?: Record<string, string | number | boolean | undefined>): Observable<Page<T>> {
    return this.http
      .get<ApiResponse<T[]>>(this.url(path), { params: this.toParams(params) })
      .pipe(
        map((res) => ({
          content: (res.data ?? []) as T[],
          // Defensive default: a non-paged success would carry no meta; treat as a single full page.
          meta: res.meta ?? { page: 0, size: (res.data ?? []).length, total: (res.data ?? []).length, totalPages: 1 },
        })),
      );
  }

  /**
   * POSTs a body and unwraps `data` (e.g. create, login).
   *
   * @typeParam T the response payload type.
   * @typeParam B the request body type.
   * @param path resource path.
   * @param body the request body.
   * @returns the unwrapped `data` payload.
   */
  post<T, B = unknown>(path: string, body: B): Observable<T> {
    return this.http.post<ApiResponse<T>>(this.url(path), body).pipe(map((res) => res.data as T));
  }

  /**
   * PUTs a body and unwraps `data` (e.g. update).
   *
   * @typeParam T the response payload type.
   * @typeParam B the request body type.
   * @param path resource path.
   * @param body the request body.
   * @returns the unwrapped `data` payload.
   */
  put<T, B = unknown>(path: string, body: B): Observable<T> {
    return this.http.put<ApiResponse<T>>(this.url(path), body).pipe(map((res) => res.data as T));
  }

  /**
   * DELETEs a resource. The backend returns a success envelope with no payload (`data: null`).
   *
   * @param path resource path (e.g. `/issue-categories/{id}`).
   * @returns `void` once the soft-delete succeeds.
   */
  del(path: string): Observable<void> {
    return this.http.delete<ApiResponse<void>>(this.url(path)).pipe(map(() => undefined));
  }

  /** Joins the base URL and a (possibly slash-prefixed) resource path without doubling the slash. */
  private url(path: string): string {
    return `${this.baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
  }

  /** Builds {@link HttpParams}, dropping `undefined` values so optional filters are omitted from the query. */
  private toParams(params?: Record<string, string | number | boolean | undefined>): HttpParams {
    let httpParams = new HttpParams();
    if (!params) {
      return httpParams;
    }
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null && value !== '') {
        httpParams = httpParams.set(key, String(value));
      }
    }
    return httpParams;
  }
}
