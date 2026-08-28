# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Spring Boot 3.3.5 / Java 21 backend for **Studease**, an online testing platform. Admins author
tests (questions, question banks, samples) and view results; students take tests. PostgreSQL in
production, OpenAI (via Spring AI) for AI question generation, STOMP/WebSocket for live test timers.

`docs/REFACTOR_PLAN.md` is the authoritative architecture review and phased roadmap — read it before
any non-trivial change. Phases 0–5 are done; deferred items (attempt token, Flyway, pagination,
per-subscription WS auth) are listed there.

## Build & test

Gradle (Kotlin DSL) with a version catalog at `gradle/libs.versions.toml`. Wrapper is Gradle 9.7.1.
On Windows use `./gradlew` from the Bash tool, or `gradlew.bat` from PowerShell.

| Task | Command |
|---|---|
| Full build (compile + spotlessCheck + test + bootJar) | `./gradlew build` |
| Run all tests | `./gradlew test` |
| Run one test class | `./gradlew test --tests "tech.studease.studease.common.util.JwtUtilsTest"` |
| Run one test method | `./gradlew test --tests "*JwtUtilsTest.roundTrips"` |
| Apply formatting (google-java-format 1.28.0) | `./gradlew spotlessApply` |
| Check formatting only | `./gradlew spotlessCheck` |
| Run the app locally | `SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun` |
| Build the runnable jar (`build/libs/app.jar`) | `./gradlew bootJar` |

`spotlessCheck` is bound to `check`/`build` and **will fail the build** on unformatted code — run
`spotlessApply` before committing.

### Test infrastructure

- Fast slices (`StudeaseApplicationTests` contextLoads, `SecurityRulesTest`, `ValidatorsTest`,
  `JwtUtilsTest`, `TestUtilsTest`) use in-memory **H2** — config in `src/test/resources/application.properties`
  (dummy secrets, `ddl-auto=create-drop`, rate limiting off). No profile is active for tests.
- Integration tests extend `support/PostgresIntegrationTest` — a shared **Testcontainers**
  `postgres:16-alpine`. These auto-skip when Docker is unavailable (`disabledWithoutDocker = true`),
  so `./gradlew build` still passes locally without Docker; CI always runs them.
- H2's `MODE=PostgreSQL` was deliberately removed: `Question.type` is an un-`@Enumerated` enum that
  Hibernate maps to `TINYINT`, which PG-mode H2 rejects. Real-schema fidelity is covered by the
  Testcontainers tests only.

## Runtime configuration

`spring.profiles.default=prod`. Local/IDE runs must set `SPRING_PROFILES_ACTIVE=dev`
(`application-dev.properties` turns on SQL logging, full actuator, error detail;
`application-prod.properties` locks all of that down).

Required environment variables (app fails fast on startup if missing — unresolved `${...}`
placeholders and `@Validated` `@ConfigurationProperties` records enforce this):

- `JWT_SECRET` (**≥ 32 chars**, enforced by `@Size` on `JwtProperties`), `JWT_EXPIRATION` (optional)
- `OPENAI_API_KEY`
- `ADMIN_EMAIL`, `ADMIN_PASSWORD` (seeded once on `ApplicationReadyEvent` by `DataLoader`)
- `DATASOURCE_URL`, `DATASOURCE_USERNAME`, `DATASOURCE_PASSWORD`
- `APP_CORS_ALLOWED_ORIGINS` (comma-separated; `@NotEmpty` — no `"*"` wildcard allowed)

App listens on `8080`; **actuator runs on a separate internal port `8081`** (`MANAGEMENT_PORT`),
exposing `health`, `info`, `prometheus` only.

## Architecture

Package-by-feature layered monolith under `tech.studease.studease` (note the doubled segment):

```
api/          @RestController + request/response DTOs (Lombok @Data/@Builder)
application/  service interfaces, impl/ classes, MapStruct mapper/ interfaces
domain/       JPA entities, Spring Data repositories, feature-scoped exception/ types
common/       config/ (security, websocket, rate limit, filters), security/, error/,
              validation/ (custom ConstraintValidators), util/, event/ (timer sweeper)
```

Each feature (`tests`, `questions`, `collections`, `samples`, `sessions`, `users`, `answers`)
appears as a sub-package in all four layers. Controllers depend on **service interfaces**, never
`*ServiceImpl`.

### Domain model

- `User` ↔ `Authority` (`ROLE_USER`, `ROLE_ADMIN`); `User.authorities` is the one deliberately
  `EAGER` association (needed for `UserDetails`).
- `Test` owns `Set<Question>`, `Set<Sample>`, `List<TestSession>` (all `LAZY`, cascade-all,
  orphan-removal), and has an `author` (`User`).
- `Collection` = a reusable question bank (`Set<Question>`). A `Sample` draws N random questions of a
  given point value from a `Collection` into a test.
- `TestSession` = one student's attempt: `studentGroup` + `studentName` identity, `startedAt`,
  persisted `endsAt` (= `startedAt + minutesToComplete`), `finishedAt`, `mark`, and a
  `List<ResponseEntry>` (one per assigned question, holding the chosen `Answer`s).
- `Answer` is a `SINGLE_TABLE` hierarchy: `Choice`, `Essay`, `MatchingPair`. Marking logic lives in
  `common/util/TestUtils`.

### Two kinds of caller

- **Admins** — JWT bearer token, routes under `/api/v1/admin/**`, guarded by deny-by-default plus
  `@PreAuthorize("isAuthenticated()")` on every method as a second layer.
- **Students** — the test-taking flow `/api/v1/tests/**` is currently `permitAll`; a student is
  identified only by a `Credentials {studentGroup, studentName}` JSON body (`domain/users/Credentials`,
  a record). Opaque per-attempt token hardening is **deferred** (see REFACTOR_PLAN §2.5) — do not
  assume this endpoint is authenticated.

### Security (`common/config/SecurityConfig`)

Stateless JWT (HS256, jjwt 0.12.6, `Keys.hmacShaKeyFor`, single parse in `JwtUtils`). Flow:
`HttpAuthTokenFilter` → `AuthService.authenticate(header)` populates the `SecurityContext`; a bad or
missing token is silently ignored and the deny-by-default chain produces the 401.
`PUBLIC_ENDPOINTS` allowlist = `/api/v1/auth/register|login`, `/api/v1/tests/**`, `/ws/**`, `/error`,
plus all `OPTIONS`. A separate `@Order(1)` filter chain permits the actuator port. Real
`AuthenticationEntryPoint`/`AccessDeniedHandler` write JSON `ErrorResponse` (401/403).
`frameOptions SAMEORIGIN` + HSTS. CSRF disabled — valid only while auth stays bearer-token-only,
never cookie.

### Error handling

`common/error/GlobalExceptionHandler` (`@RestControllerAdvice`) maps domain exceptions to JSON
`ErrorResponse`, including a catch-all `Exception` → sanitized 500 (logs stack, hides it) and
`QuestionGenerationException` → 502. Security-filter-level errors are handled separately in
`SecurityConfig`.

### Persistence rules

- **All associations are `LAZY`** except `User.authorities`. `@BatchSize(100)` on collections +
  `hibernate.default_batch_fetch_size=50`, ordered batched inserts/updates.
- `open-in-view=false`. Any service method that maps entities → DTOs after the repo call **must be
  `@Transactional`** or it throws `LazyInitializationException`. Read methods use
  `@Transactional(readOnly = true)`.
- Fetch plans use `@EntityGraph`/JPQL fetch-joins. **Never put two bag (`List`) collections in one
  graph** — `MultipleBagFetchException`. Prefer query-scoped ownership checks
  (`findByIdAndAuthorEmail`, `existsByIdAndAuthorEmail`) over load-then-compare.
- `ddl-auto=update` (Flyway migration is deferred to its own PR — do not add ad-hoc schema code).
- Current user is obtained via the injected `common/security/CurrentUser` bean, **not** static
  `SecurityContextHolder` access.

### Real-time timers (`common/event/`)

No in-memory session state. `TestSessionExpirySweeper` runs one `@Scheduled` DB pass every
`app.test-session.sweep-interval-ms` (default 15s) over `findByFinishedAtIsNull()`: it rebroadcasts
remaining seconds (`TimerMessage` type `TICK`) and force-ends sessions past `endsAt` (`FORCE_END`).
Clients count down locally between ticks from `endsAt`. `TestSessionTimerBroadcaster` owns the STOMP
payload. `WebSocketConfig` uses a simple broker; `StompInboundGuard` (`ChannelInterceptor`) rejects
all client `SEND` frames and restricts `SUBSCRIBE` to `/(queue|topic)/testSession/{id}`. Restarts
never drop a live timer.

### AI question generation

`GET /api/v1/admin/questions/generate` → `OpenAIServiceImpl` → Spring AI `ChatClient` → OpenAI
`gpt-4o`. Prompt template: `src/main/resources/templates/get-questions-for-test.st` (hardened
against instruction injection). Params are bounded (`points` 1–5, `questionsCount` 1–20) and `theme`
is sanitized/truncated server-side. HTTP client has 10s connect / 60s read timeout
(`HttpClientConfig`). Failures → `QuestionGenerationException` → 502. Rate-limited to 30/hour per IP.

### Rate limiting & CSV

`common/config/RateLimitFilter` — in-memory bucket4j, per-client-IP fixed window, on `/auth/**`
(10/min), `/api/v1/tests/**` (60/min), AI generate (30/h); all configurable; returns `429` +
`Retry-After`. Shared/Redis store is deferred. CSV export (`common/util/CsvGeneratorUtils`) uses
Apache Commons CSV with leading `= + - @ \t \r` neutralization.

### MapStruct + Lombok

Annotation-processor order in `build.gradle.kts` matters: `lombok` → `lombok-mapstruct-binding` →
`mapstruct`. Mappers are interfaces in `application/*/mapper/`; they use builder-style mapping.

## Delivery

Multi-stage `Dockerfile`: Gradle build stage → layered-jar extraction
(`java -Djarmode=tools ... extract --layers`) → `eclipse-temurin:21-jre` runtime, non-root `spring`
user, `MaxRAMPercentage=75`, `HEALTHCHECK` on `:8081/actuator/health`. `bootJar` archive name is
pinned to `app.jar` so the Dockerfile is version-independent.

CI (`.github/workflows/deploy.yml`): the `build` job (`./gradlew build`) runs on every push to
`master`, PR, and `v*` tag. The `deploy` job runs **only** on a `v*` tag or `workflow_dispatch` —
it builds/pushes the image to Docker Hub and SSH-deploys to a single VDS via `docker compose`.

**Release flow:** merge to `master` (CI tests) → `git tag vX.Y.Z && git push origin vX.Y.Z`
(triggers image build + deploy).
