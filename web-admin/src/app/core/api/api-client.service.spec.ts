import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { ApiClient } from './api-client.service';
import { environment } from '../../../environments/environment';

describe('ApiClient', () => {
  let api: ApiClient;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ApiClient, provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ApiClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('unwraps the envelope data on get()', () => {
    let result: { name: string } | undefined;
    api.get<{ name: string }>('/widget/1').subscribe((r) => (result = r));

    const req = httpMock.expectOne(`${environment.apiUrl}/widget/1`);
    req.flush({
      success: true,
      statusCode: 200,
      message: 'OK',
      data: { name: 'Mkoa' },
      meta: null,
      timestamp: '2026-06-23T00:00:00Z',
    });

    expect(result).toEqual({ name: 'Mkoa' });
  });

  it('bundles content + meta on getPage()', () => {
    let page: { content: unknown[]; meta: { total: number } } | undefined;
    api.getPage<{ id: string }>('/widgets', { page: 0, size: 2 }).subscribe((p) => (page = p));

    const req = httpMock.expectOne((r) => r.url === `${environment.apiUrl}/widgets`);
    req.flush({
      success: true,
      statusCode: 200,
      message: 'OK',
      data: [{ id: 'a' }, { id: 'b' }],
      meta: { page: 0, size: 2, total: 5, totalPages: 3 },
      timestamp: '2026-06-23T00:00:00Z',
    });

    expect(page?.content.length).toBe(2);
    expect(page?.meta.total).toBe(5);
  });
});
