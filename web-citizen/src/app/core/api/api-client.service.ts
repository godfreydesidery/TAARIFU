import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

import { environment } from '../../../environments/environment';
import { ApiResponse, Page } from './api-response.model';

/**
 * Thin, typed HTTP gateway over the Taarifu backend (citizen PWA).
 *
 * <p>Responsibility: the ONE place that knows the API base URL ({@link environment.apiUrl}) and how to
 * UNWRAP the response envelope. Feature services call {@link get}/{@link getPage}/{@link post} and receive
 * the `data` payload directly — never the `{ success, statusCode, message, data, meta }` wrapper. Error
 * normalisation is done centrally by the {@link ApiResponseInterceptor}; auth headers + `Accept-Language`
 * by the {@link AuthInterceptor}. This keeps every feature service free of URL strings and envelope
 * plumbing (DRY).</p>
 */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.apiUrl;

  /**
   * GETs a single-object resource and unwraps `data`.
   * @typeParam T the payload type.
   * @param path resource path relative to the API base (leading slash optional).
   * @param params optional query params.
   * @returns the unwrapped `data` payload.
   */
  get<T>(path: string, params?: QueryParams): Observable<T> {
    return this.http
      .get<ApiResponse<T>>(this.url(path), { params: this.toParams(params) })
      .pipe(map((res) => res.data as T));
  }

  /**
   * GETs a PAGED list resource and unwraps both the row `content` and the {@link Page.meta}.
   * @typeParam T the row type.
   * @param path resource path.
   * @param params query params — typically `page`, `size`, `sort`, plus per-resource filters.
   * @returns a {@link Page} of `T`.
   */
  getPage<T>(path: string, params?: QueryParams): Observable<Page<T>> {
    return this.http.get<ApiResponse<T[]>>(this.url(path), { params: this.toParams(params) }).pipe(
      map((res) => ({
        content: (res.data ?? []) as T[],
        // Defensive default: a non-paged success would carry no meta; treat as a single full page.
        meta:
          res.meta ?? {
            page: 0,
            size: (res.data ?? []).length,
            total: (res.data ?? []).length,
            totalPages: 1,
          },
      })),
    );
  }

  /**
   * POSTs a body and unwraps `data` (e.g. file a report, request OTP, verify OTP).
   * @typeParam T the response payload type.
   * @typeParam B the request body type.
   * @param path resource path.
   * @param body the request body.
   * @returns the unwrapped `data` payload.
   */
  post<T, B = unknown>(path: string, body: B): Observable<T> {
    return this.http.post<ApiResponse<T>>(this.url(path), body).pipe(map((res) => res.data as T));
  }

  /** Joins the base URL and a (possibly slash-prefixed) resource path without doubling the slash. */
  private url(path: string): string {
    return `${this.baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
  }

  /** Builds {@link HttpParams}, dropping empty values so optional filters are omitted from the query. */
  private toParams(params?: QueryParams): HttpParams {
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

/** Loose query-param bag accepted by the gateway; empty/undefined entries are dropped. */
export type QueryParams = Record<string, string | number | boolean | undefined | null>;
