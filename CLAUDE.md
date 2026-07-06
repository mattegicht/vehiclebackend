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

**Auth:** Stateless JWT. `JwtFilter` runs on every request, reads `Authorization: Bearer <token>`, calls `JwtUtil.parseClaims()`, then loads `UserDetails` and sets `SecurityContextHolder`. Tokens expire after 24 h. CORS is restricted to the origins in `CORS_ALLOWED_ORIGINS` (comma-separated, default `https://vehiclebackend.duckdns.org`); native apps send no Origin header and are unaffected.

**Authorization logic in services:** Role checks are done in service methods (not just at the route level) via shared helpers (`isCreator` / `isCurrentDriver` / `isAdmin`) that compare `currentUser()` — the authenticated username from `SecurityContextHolder`, looked up in the DB — against the resource. Only the owner or `ROLE_ADMIN` can delete a vehicle; only the current `inUseBy` user (or any user if the vehicle is free) can toggle it; only the creator, current driver, or an admin can update kilometers. `toggleInUse` runs in a transaction with a pessimistic row lock (`findWithLockById`) so concurrent check-outs serialize.

**Vehicle dual-user model:** `Vehicle` has two user FKs — `user` (permanent creator/owner) and `inUseBy` (who currently has it checked out). `inUse` boolean tracks state; `inUseBy` is set on toggle-on and nulled on toggle-off.

**`VehicleResponse` username field:** The `username` field returns `inUseBy.username` when the vehicle is in use, or falls back to `createdBy` when free. Both fields are sent so the client can always display the creator separately.

**Seeded users:** No credentials are hardcoded. An account is created on startup **only if its password env var is set** (and the user doesn't already exist):
- `SEED_ADMIN_PASSWORD` set → admin account (`SEED_ADMIN_USERNAME`, default `admin`) — `ROLE_ADMIN`
- `SEED_DEMO_PASSWORD` set → demo account (`SEED_DEMO_USERNAME`, default `demo`) — `ROLE_USER`

If neither is set the app ships with no seeded accounts (no default backdoor). A legacy `demo` user with a malformed role is still normalized to `ROLE_USER` on startup.

**Schema management:** `hibernate.ddl-auto=update` — Hibernate auto-migrates the schema on every startup. No separate migration tool is used.

**Deployment:** Fly.io (`fly.toml`), Frankfurt region, 1 GB RAM. Multi-stage Dockerfile: Maven build stage → OpenJDK 17 slim runtime.

## Known Issues (code review 2026-06-02)

Findings from a full review of the backend, ordered by severity. Unresolved unless noted.

### High
1. ~~**Secrets committed to the repo.**~~ **FIXED 2026-06-02.** Removed the `jwt.secret` / `DB_PASSWORD` fallback defaults from `application.properties` (now fails fast if unset); moved compose secrets to a gitignored `.env` (`.env.example` documents the keys) with `${VAR:?...}` required-var syntax; rotated the JWT secret; added `.gitignore`. **Note:** the old secrets remain in git history — they're now useless for prod as long as prod uses the new secret, but purge history with `git filter-repo` if the repo is/was public.
2. ~~**`admin / admin` seeded in every environment**~~ **FIXED 2026-06-02.** `DataSeeder` no longer hardcodes credentials — admin/demo accounts are seeded only when `SEED_ADMIN_PASSWORD` / `SEED_DEMO_PASSWORD` are set (via `seed.*` properties). Omitting them ships with no seeded accounts. Bootstrap admin password lives in the gitignored `.env`. **Action:** the previously-deployed `admin/admin` account, if it exists in the live DB, must be deleted or have its password changed — this fix only affects fresh seeding, not rows already persisted.

### Medium
3. ~~**`updateKilometers` has no authorization check**~~ **FIXED 2026-07-06.** Now requires the vehicle's creator, its current driver (`inUseBy`), or an admin.
4. ~~**Race condition on `toggleInUse`**~~ **FIXED 2026-07-06.** `toggleInUse` is `@Transactional` and reads the row via `VehicleRepository.findWithLockById` (`PESSIMISTIC_WRITE`), serializing concurrent check-outs.
5. ~~**Over-broad CORS**~~ **FIXED 2026-07-06.** Allowed origins come from `cors.allowed-origins` (`CORS_ALLOWED_ORIGINS`, comma-separated), default `https://vehiclebackend.duckdns.org`. **Action:** if a Flutter *web* build is served from another origin, add it to `CORS_ALLOWED_ORIGINS` in `.env`.

### Low / polish
6. ~~**N+1 queries on `GET /api/vehicles`**~~ **FIXED 2026-07-06.** List endpoint uses `findAllWithUsers()` with `join fetch` on both user relations (single query).
7. ~~**Dead code: `VehicleRepository.findAllByUser`**~~ **FIXED 2026-07-06.** Removed; replaced by `existsByUser` / `findAllByInUseBy`, which `deleteUser` now uses (see #11).
8. ~~**No password strength validation**~~ **FIXED 2026-07-06.** `@Size(min = 8)` on `newPassword` (change-password) and `password` (admin create-user).
9. **Partially fixed 2026-07-06:** `kennzeichen` now has a unique constraint (`Vehicle.java`). **Note:** `ddl-auto=update` can only add the DB constraint if no duplicate plates already exist — check the live DB. Kilometers can still decrease (any value ≥ 0 accepted) — deliberate for now, allows corrections; confirm business rule.
10. ~~**`secret.getBytes()` uses the platform default charset**~~ **FIXED 2026-07-06.** Pinned to UTF-8; `JwtParser` is also now built once and reused.

### Fixed on discovery (review 2026-07-06)
11. **`deleteUser` FK violation → 500** — deleting a user who created vehicles hit the `vehicles.user_id` NOT NULL FK. Now: the user's check-outs are released (`inUseBy` cleared), and if they still own vehicles the API returns **409** with a clear message instead of 500.
12. **Login with missing/null password → 500** — `LoginRequest` had no validation; `BCryptPasswordEncoder.matches(null, …)` threw. Now `@NotBlank` on both fields + `@Valid` → 400.
13. **`JwtFilter` swallowed all exceptions** — a DB outage during user lookup was masked as 401. Now only `JwtException | IllegalArgumentException | UsernameNotFoundException` are treated as unauthenticated; infrastructure errors propagate as 500.
14. **`createUser` check-then-act race → 500** — concurrent duplicate usernames hit the unique constraint. Now `DataIntegrityViolationException` is mapped to **409**.

### Done well
Constructor injection throughout; login returns generic "Invalid credentials" (no user enumeration); passwords BCrypt-hashed and never leaked in DTOs; JWT secret length validated at startup; stateless sessions; clean `ResponseStatusException` error mapping.
