# Taarifu Backend

Spring Boot 3.3 (Java 21) modular-monolith backend for **Taarifu**, the Tanzania civic-engagement
platform. This is the **foundation increment**: a shared kernel, the **geography** read vertical slice,
and the **identity** data layer. See [`../docs/architecture/ARCHITECTURE.md`](../docs/architecture/ARCHITECTURE.md)
and [`FOUNDATION-SCOPE.md`](../docs/architecture/FOUNDATION-SCOPE.md) for the design this builds to.

## Stack
Java 21 · Spring Boot 3.3.5 (Web, Security, Data JPA, Validation, Actuator) · PostgreSQL 16 + PostGIS
3.4 · Flyway (`ddl-auto=validate`) · springdoc-openapi · JWT (Nimbus) · JUnit 5 + Testcontainers.

## Module layout (`com.taarifu.*`)
- `common` — shared kernel: `BaseEntity`, the single `ApiResponse` envelope + `GlobalExceptionHandler`,
  pagination, JPA auditing, code generation, i18n (SW default + EN), security (stateless JWT,
  `@EnableMethodSecurity`, BCrypt, tight CORS), field-level PII crypto, OpenAPI/Jackson config.
- `geography` — full read slice: `Location` (closure-table hierarchy), `Constituency`, effective-dated
  `WardConstituency`, `Geocoder` port (PostGIS primary + stub), services, controllers, DTOs.
- `identity` — data layer only: `User`, `Profile` (encrypted `idNo` + blind-index `idHash`),
  `ProfileLocation` (private PII; one primary, one electoral), `Role`, `RoleAssignment` (scoped),
  `VerificationRequest`, `RefreshToken`, enums, repositories, `IdentityVerificationProvider` port.

> The remaining modules (`reporting`, `engagement`, …) exist as empty packages to lock the layout;
> no code yet (FOUNDATION-SCOPE §1).

## Prerequisites
- JDK 21 (the Maven wrapper `./mvnw` handles Maven itself).
- Docker (for PostgreSQL+PostGIS locally and for Testcontainers integration tests).

## Configuration (no secrets in source)
All secrets/config come from the environment — see [`application-sample.yml`](src/main/resources/application-sample.yml).
Minimum to run locally:

```bash
export TAARIFU_DB_URL=jdbc:postgresql://localhost:5432/taarifu
export TAARIFU_DB_USER=taarifu
export TAARIFU_DB_PASSWORD=taarifu
export TAARIFU_JWT_SECRET=$(openssl rand -base64 48)
export TAARIFU_CRYPTO_DEV_KEY=$(openssl rand -base64 32)
# Optional: export TAARIFU_GEOCODER=stub   (dev/test, no ward geometry needed)
```

Start a local PostGIS database:

```bash
docker run --name taarifu-db -e POSTGRES_DB=taarifu -e POSTGRES_USER=taarifu \
  -e POSTGRES_PASSWORD=taarifu -p 5432:5432 -d postgis/postgis:16-3.4
```

> The Flyway migrations (V001 baseline, V002 geography, V101/V102 identity) are owned by the
> **database engineer** and land next. Until they exist, `ddl-auto=validate` has nothing to validate
> against — run the app once migrations are committed.

## Run

```bash
./mvnw spring-boot:run
```

- API root: `http://localhost:8081/api/v1`
- Swagger UI: `http://localhost:8081/api/v1/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8081/api/v1/openapi.json`
- Health: `http://localhost:8081/api/v1/actuator/health`

Sample public reads (no auth required):

```bash
curl http://localhost:8081/api/v1/regions
curl "http://localhost:8081/api/v1/locations/resolve?lat=-3.07&lng=37.35"
```

## Test

```bash
./mvnw test
```

Integration tests use **Testcontainers** with a real **PostGIS** image (a Docker daemon must be
running). They cover the geography read endpoints (envelope + pagination), repository/constraint
behaviour, the effective-dated ward→constituency resolution, and an application-context-loads check.

## Build a runnable jar / image

```bash
./mvnw clean package          # target/taarifu-backend-*.jar
docker build -t taarifu-backend .
```

## Conventions (binding)
- One response envelope (`ApiResponse`) everywhere; controllers use `ResponseFactory`.
- UUID public ids in URLs; internal `Long` PKs never exposed (ADR-0006).
- Method-level security, deny-by-default; reference reads are explicitly `permitAll()`.
- PII (national/voter ID) encrypted at rest; never logged (PRD §18, PDPA).
- Swahili-first messages; every error code resolves SW/EN (ADR-0010).
- Every component is documented with Javadoc (CLAUDE.md §8).
