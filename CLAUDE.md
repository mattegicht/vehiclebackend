# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build (skip tests)
./mvnw clean package -DskipTests

# Run locally
./mvnw spring-boot:run

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=VehicleServiceTest

# Build Docker image
docker build -t vehiclebackend .

# Run with Docker (MySQL on host)
docker run -p 8080:8080 \
  -e DB_URL=jdbc:mysql://host.docker.internal:3308/vehicledb \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=secret \
  -e JWT_SECRET=<32+-char-hex-string> \
  vehiclebackend
```

## Environment Variables

| Variable | Default | Notes |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3308/vehicledb` | Supports MySQL or PostgreSQL |
| `DB_USERNAME` | `root` | |
| `DB_PASSWORD` | (none — **required**) | App fails to start if unset |
| `JWT_SECRET` | (none — **required**) | Must be ≥ 32 characters; app fails to start if unset |
| `PORT` | `8080` | |

Secrets live in a gitignored `.env` (see `.env.example`); `docker-compose` loads it automatically. For `mvn spring-boot:run`, export `DB_PASSWORD` and `JWT_SECRET` first.

## Architecture

**Stack:** Spring Boot 3.3.5 / Java 17 / JPA (Hibernate) / MySQL or PostgreSQL / JJWT 0.12.6

**Request flow:**
```
HTTP request
  → JwtFilter (extracts Bearer token, populates SecurityContext)
  → SecurityConfig route rules (/api/auth/** public, /api/admin/** ROLE_ADMIN, rest authenticated)
  → Controller (record-based DTOs, inline request/response types)
  → Service (owns business logic, resolves current user from SecurityContext)
  → JPA Repository
```

**Auth:** Stateless JWT. `JwtFilter` runs on every request, reads `Authorization: Bearer <token>`, calls `JwtUtil.parseClaims()`, then loads `UserDetails` and sets `SecurityContextHolder`. Tokens expire after 24 h. CORS allows all origins with credentials.

**Authorization logic in services:** Role checks are done in service methods (not just at the route level). `deleteVehicle` and `toggleInUse` both call `currentUser()` — which reads the authenticated username from `SecurityContextHolder` and looks it up in the DB — and then compare against the resource's owner. Only the owner or `ROLE_ADMIN` can delete a vehicle; only the current `inUseBy` user (or any user if the vehicle is free) can toggle it.

**Vehicle dual-user model:** `Vehicle` has two user FKs — `user` (permanent creator/owner) and `inUseBy` (who currently has it checked out). `inUse` boolean tracks state; `inUseBy` is set on toggle-on and nulled on toggle-off.

**`VehicleResponse` username field:** The `username` field returns `inUseBy.username` when the vehicle is in use, or falls back to `createdBy` when free. Both fields are sent so the client can always display the creator separately.

**Seeded users (created on startup if absent):**
- `demo` / `password` — `ROLE_USER`
- `admin` / `admin` — `ROLE_ADMIN`

**Schema management:** `hibernate.ddl-auto=update` — Hibernate auto-migrates the schema on every startup. No separate migration tool is used.

**Deployment:** Fly.io (`fly.toml`), Frankfurt region, 1 GB RAM. Multi-stage Dockerfile: Maven build stage → OpenJDK 17 slim runtime.

## Known Issues (code review 2026-06-02)

Findings from a full review of the backend, ordered by severity. Unresolved unless noted.

### High
1. ~~**Secrets committed to the repo.**~~ **FIXED 2026-06-02.** Removed the `jwt.secret` / `DB_PASSWORD` fallback defaults from `application.properties` (now fails fast if unset); moved compose secrets to a gitignored `.env` (`.env.example` documents the keys) with `${VAR:?...}` required-var syntax; rotated the JWT secret; added `.gitignore`. **Note:** the old secrets remain in git history — they're now useless for prod as long as prod uses the new secret, but purge history with `git filter-repo` if the repo is/was public.
2. **`admin / admin` seeded in every environment** (`DataSeeder.java:38`). A `ROLE_ADMIN` backdoor on a public deployment. Gate seeding behind a dev profile (`@Profile("dev")`) or require the admin password via env var.

### Medium
3. **`updateKilometers` has no authorization check** (`VehicleService.java:51-56`). Unlike `deleteVehicle`/`toggleInUse`, any authenticated user can change the odometer on any vehicle. Apply the same owner/`inUseBy` check or confirm it's intentional.
4. **Race condition on `toggleInUse`** (`VehicleService.java:58-74`). Read-modify-write with no locking; concurrent check-outs can both succeed and overwrite `inUseBy`. Add `@Version` optimistic locking to `Vehicle` or a pessimistic find lock.
5. **Over-broad CORS** (`SecurityConfig.java:46-49`). `allowedOriginPatterns("*")` + `allowCredentials(true)` lets any site make credentialed calls (Spring reflects the origin). Restrict to the real frontend origin(s), e.g. `https://vehiclebackend.duckdns.org`.

### Low / polish
6. **N+1 queries on `GET /api/vehicles`** (`VehicleService.java:32` + `Vehicle.java:34,38`). Two EAGER `@ManyToOne` relations issue extra queries per vehicle. Use a `join fetch` query if the list grows.
7. **Dead code:** `VehicleRepository.findAllByUser` (`:10`) is unused — the list endpoint returns all vehicles to every user (looks intentional for a shared fleet).
8. **No password strength validation** on change/create (`UserController.java:25`, `AdminController.java:22`) — only `@NotBlank`. Consider `@Size(min=8)`.
9. **`kennzeichen` not unique / kilometers can decrease** — no unique constraint on the plate (`Vehicle.java:13`); `updateKilometers` accepts any value ≥0, so the odometer can go down. Confirm business rules.
10. **`secret.getBytes()` uses the platform default charset** (`JwtUtil.java:23`). Pin it: `secret.getBytes(StandardCharsets.UTF_8)`.

### Done well
Constructor injection throughout; login returns generic "Invalid credentials" (no user enumeration); passwords BCrypt-hashed and never leaked in DTOs; JWT secret length validated at startup; stateless sessions; clean `ResponseStatusException` error mapping.
