# Studease Backend — Architecture Review & Refactor Plan

_Reviewed: 2026-08-28 · Spring Boot 3.3.5 · Java 21 · PostgreSQL_

---

## 1. Architecture overview

Layered, package-by-feature monolith:

```
api/          REST controllers + request/response DTOs        (tech.studease.studease.api.*)
application/  services, service impls, MapStruct mappers      (…application.*)
domain/       JPA entities, Spring Data repositories, exceptions
common/       config, security filter, JWT util, validation, scheduling, websocket events
```

| Concern            | Implementation |
|--------------------|----------------|
| Auth               | Stateless JWT (HS256), custom `OncePerRequestFilter`, `@EnableMethodSecurity` |
| Persistence        | Spring Data JPA + Hibernate, `ddl-auto=update`, HikariCP |
| Real-time          | STOMP over WebSocket, in-memory simple broker, `@Scheduled` 1 s timer tick |
| AI                 | Spring AI `ChatClient` → OpenAI `gpt-4o`, prompt template `get-questions-for-test.st` |
| Two user types     | **Admins** (`/api/v1/admin/**`, JWT) author tests; **students** (`/api/v1/tests/**`, no auth) take them, identified by `{studentGroup, studentName}` in the body |
| Delivery           | Dockerfile → GitHub Actions → Docker Hub → SSH deploy to a single VDS on every push to `master` |

The domain is small and coherent. The main problems are in **security posture**, **build/deploy setup**, **persistence configuration**, and the **in-memory real-time timer**.

---

## 2. Critical security vulnerabilities

### 2.1 Deny-by-default is inverted
`SecurityConfig.securityFilterChain`:
```java
auth.requestMatchers("/api/v1/admin/**").authenticated().anyRequest().permitAll();
```
Everything except `/api/v1/admin/**` is fully open. Route protection depends entirely on remembering to add `@PreAuthorize("isAuthenticated()")` to every admin method. A new controller under a different path, or a forgotten annotation, is silently public.
**Fix:** `anyRequest().authenticated()` + explicit `permitAll` for `/api/v1/auth/**`, `/api/v1/tests/**`, `/ws/**`, `OPTIONS`. Keep `@PreAuthorize` as a second layer.

### 2.2 CORS wildcard with credentials
```java
configuration.setAllowedOriginPatterns(List.of("*"));
configuration.setAllowCredentials(true);
```
Any origin can drive authenticated cross-site requests.
**Fix:** explicit origin allowlist from config (`app.cors.allowed-origins`).

### 2.3 `unauthorizedHandler` is a no-op
```java
return (request, response, authException) -> {};
```
Unauthenticated hits on protected routes return **`200 OK` with an empty body** instead of `401`. Clients cannot detect an expired session.
**Fix:** entry point that writes `401` + JSON error body.

### 2.4 Ancient JJWT (0.9.1, 2018)
- Known CVEs; drags in legacy Jackson + `jaxb-api`.
- Deprecated `signWith(SignatureAlgorithm.HS256, String)` — the secret is used as raw bytes with **no enforced key length**, so a short `JWT_SECRET` yields a brute-forceable HS256 key.
- Token is parsed twice per request (`validateToken` then `extractClaims`).
**Fix:** upgrade to `io.jsonwebtoken:jjwt-api/impl/jackson` 0.12.x, `Keys.hmacShaKeyFor(...)`, fail startup if the secret is < 32 bytes, parse once.

### 2.5 Student test-taking has no authentication or integrity
`TestPassingController` (`/api/v1/tests/**`) is fully public and a student is identified only by `{studentGroup, studentName}` JSON in each POST body. Anyone who knows a `testId` can:
- start / answer / finish a test **as any student**,
- read any in-progress session and its current question,
- replay `finish` / enumerate groups.

No per-attempt token, no rate limiting, no proctoring binding.
**Fix:** issue an opaque attempt token at `POST /{testId}/start`, require it as a header on `current-question` / `next-question` / `current-session` / `finish`; stop trusting body credentials after start. Add rate limiting.

### 2.6 DoS: connection timeout disabled
```properties
server.tomcat.connection-timeout=-1
```
Slowloris / connection-exhaustion. Combined with no request-size or keep-alive limits and a 15-connection Hikari pool.
**Fix:** remove the override (or set `20s`), add `server.tomcat.max-*` limits, `spring.servlet.multipart.max-request-size`.

### 2.7 Security headers disabled
`frameOptions().disable()` → clickjacking. No HSTS, no CSP. CSRF-disabled is acceptable **only** while auth is bearer-token-only (never cookie).
**Fix:** `frameOptions SAMEORIGIN`, add HSTS; document the no-cookie invariant.

### 2.8 AI endpoint — cost abuse & prompt injection
`GET /api/v1/admin/questions/generate`:
- `questionsCount` and `points` are unbounded `@RequestParam int` — `questionsCount=100000` is a large OpenAI bill; negative `points` corrupts the difficulty mapping.
- `theme` is interpolated straight into the LLM prompt (injection).
- No per-user quota, no call timeout/retry, `RuntimeException` on null result.
**Fix:** `@Min/@Max` bounds (e.g. count 1–20, points 1–5), delimit/escape user input in the template, per-user daily quota, `ChatClient` timeout + backoff, typed exception → `502`.

### 2.9 `ddl-auto=update` in production
Hibernate mutates the live schema on every boot with no migration history, no review, and no safe path for destructive changes (the recent FK-violation hotfixes in git history are a symptom).
**Fix:** Flyway; switch to `ddl-auto=validate`.

### 2.10 Dockerfile runs dev mode in production
```dockerfile
FROM eclipse-temurin:21-jdk
COPY . ./
CMD ["./mvnw", "spring-boot:run"]
```
- `spring-boot:run` keeps **spring-boot-devtools active in prod** (auto-restart, extra endpoints).
- Recompiles on every container start; no optimized/layered jar.
- Copies the whole tree including `.git` into the image; JDK (not JRE) base; runs as **root**; no `HEALTHCHECK`.
**Fix:** multi-stage build (see §5).

### 2.11 Error handling leaks / gaps
`GlobalExceptionHandler` echoes `exc.getMessage()` for `IllegalStateException`/`IllegalArgumentException` and has **no `@ExceptionHandler(Exception.class)`** — unexpected errors fall through to Spring's default. `path` is derived by `request.getDescription(false).substring(4)` (brittle magic offset).
**Fix:** catch-all returning a sanitized `500`; build `path` from `WebRequest`/`HttpServletRequest` properly; never surface raw messages for unhandled types.

### 2.12 No token lifecycle
24 h token, no logout, no revocation list, password change does not invalidate issued tokens.
**Fix:** short-lived access token + refresh token, or a token version/`iat` check against the user record.

### 2.13 WebSocket subscriptions are unauthenticated
`WebSocketConfig` registers `/ws` twice, `setAllowedOriginPatterns("*")`, **no `ChannelInterceptor`**. Any client can `SUBSCRIBE /queue/testSession/{id}` for any id and watch another student's timer and final results payload.
**Fix:** single endpoint, origin allowlist, STOMP `ChannelInterceptor` binding a subscription to a valid attempt token and its own session id.

### 2.14 CSV injection
`CsvGeneratorUtils` concatenates fields with no escaping. A `studentName` of `=cmd|'/c calc'!A1` executes on open in Excel; a name containing `,` / `"` / newline breaks the file.
**Fix:** real CSV writer (Apache Commons CSV / OpenCSV) + neutralize leading `= + - @ \t \r`.

---

## 3. Architecture & design problems

| # | Problem | Notes |
|---|---------|-------|
| 3.1 | **Dual build systems** | `pom.xml` (Maven — used by Docker & CI) **and** `build.gradle.kts` + `settings.gradle.kts` + `gradlew` + `gradle.properties` (auto-generated by `gradle init`). The Gradle build targets **Java 8**, has **no Spring Boot plugin**, uses `api()` for every dependency and `maven-publish` — it cannot build a runnable app and will mislead IDEs/contributors. |
| 3.2 | **Static mutable session state** | `GlobalTestSessionScheduler.testSessions` is a `static ConcurrentHashMap` with `static addTimer/removeTimer`. Every deploy (which happens on every push to `master`) **drops all running test timers**. Not horizontally scalable. Timers leak if `finish` is never called. |
| 3.3 | **1 s scheduler doing DB work in a loop** | `tick()` iterates all sessions every second and calls `forceEndTestSession` (DB read + write) synchronously on the scheduler thread; one slow call stalls every timer. Emits one WebSocket message per session per second. |
| 3.4 | **Static `SecurityContextHolder` access in the service layer** | `JwtUtils.getUserFromAuthentication()` is called statically from ~6 service methods. Hidden dependency, hard to test, NPEs if `authentication == null`, and throws the misleadingly-named `TokenExpiredException` for any non-`User` principal. |
| 3.5 | **Ownership check copy-pasted** | `!x.getAuthor().getEmail().equals(getUserFromAuthentication().getEmail())` repeated in `TestServiceImpl`, `QuestionServiceImpl`, collection/sample services. Load-then-check instead of query-scoped. |
| 3.6 | **Eager fetch everywhere** | `Test.questions` **and** `Test.samples` both `EAGER @OneToMany` (cartesian/N+1); `Question.answers` EAGER; `ResponseEntry` all associations EAGER; `TestSession.responses` EAGER; `User.authorities` EAGER. `open-in-view=false` is correct, but it forces the scattered `Hibernate.initialize(...)` calls — a smell that the fetch strategy is wrong. |
| 3.7 | **Unused starter** | `spring-boot-starter-data-jdbc` is on the classpath; only JPA is used. |
| 3.8 | **Controllers bind to `*ServiceImpl`** | `QuestionController` and `SampleController` inject the concrete class, defeating the interface. |
| 3.9 | **`createAll` ignores `collectionId`** | `QuestionServiceImpl.createAll(Long collectionId, …)` never uses the parameter — `POST /questions/by-collection/{id}` saves questions with **no collection association**. Likely a bug. |
| 3.10 | **Two sources of truth for quiz position** | `nextResponseEntry()` = "first response with empty answers"; `currentQuestionIndex` is tracked and persisted but never used for navigation. `saveAnswers(String)` inserts a **new `Essay` row on every call**, including on revisit, with `isCorrect=true` hard-coded. |
| 3.11 | **`calculateMark` divide-by-zero** | `(double) correctCount / correctAnswerIds.size()` → `NaN → (int) 0` when a non-essay question has no correct answer. Silent wrong grade. |
| 3.12 | **`addSampleQuestions` unguarded** | `selectedQuestions.remove(random.nextInt(selectedQuestions.size()))` throws `IllegalArgumentException`/`IndexOutOfBounds` if `sample.getQuestionsCount()` exceeds available questions; `new Random()` per call. |
| 3.13 | **Manual `Validator` bean** | `SecurityConfig.validator()` rebuilds the validator factory and calls `Locale.setDefault(Locale.ENGLISH)` — a global JVM side effect from a bean method; also loses Spring's `MessageSource` integration. Boot already supplies a `Validator`. |
| 3.14 | **No pagination** | `findByTestId`, `findAll`, session lists and CSV export all return unbounded collections. |
| 3.15 | **`DataLoader` on `ContextRefreshedEvent`** | Fires on every context refresh (and in tests). Should be `ApplicationReadyEvent` once, or a Flyway seed. |
| 3.16 | **Empty stub** | `WebSocketEventListener.handleWebSocketDisconnectListener` does nothing. |
| 3.17 | **`@Deprecated` endpoint still live** | `PUT /api/v1/admin/tests/{id}`. |
| 3.18 | **DTO style** | `@Data`/`@Builder` mutable classes; project already uses `record` for `Credentials` — DTOs should follow. |
| 3.19 | **Package name** | `tech.studease.studease` double segment; `common.util.TestUtils` name collides conceptually with JUnit. |

---

## 4. Performance / optimization opportunities

1. **Kill the per-second broadcast.** Persist an `endsAt` timestamp on `TestSession`; let clients count down locally; server pushes only on state transitions (start / force-end). Run a single low-frequency (10–30 s) `@Scheduled` sweep that force-ends `finishedAt IS NULL AND now > startedAt + minutesToComplete`. Removes O(sessions) DB + socket work every second.
2. **Fetch strategy:** all associations `LAZY`; add purpose-built `@EntityGraph`/JPQL fetch-joins per use case; delete the `Hibernate.initialize` loops (N+1).
3. **Query-scoped ownership:** `findByIdAndAuthorEmail(...)` instead of load-then-compare.
4. **Indexes** (via Flyway): `test(author_id)`, `test_session(test_id, student_group, student_name)` unique, `question(test_id)`, `question(collection_id)`, `sample(test_id)`, `response_entry(test_session_id)`.
5. **Hibernate batching:** `spring.jpa.properties.hibernate.jdbc.batch_size=30`, `order_inserts=true`, `order_updates=true` — `saveAll` currently issues per-row inserts.
6. **Hikari:** keep a bounded pool, add `spring.datasource.hikari.connection-timeout` and a JDBC/statement timeout; do **not** pair the pool with `connection-timeout=-1` at the Tomcat layer.
7. **Auth per-request DB hit:** the filter calls `loadUserByUsername` (DB) on every authenticated request. Embed roles in the JWT or add a short-TTL cache.
8. **CSV export:** stream with `StreamingResponseBody` instead of building a `String` then `.getBytes()`.
9. **AI:** cheaper default model option, call timeout + retry/backoff, async generation for large batches.
10. **Docker image:** JRE runtime + layered jar → smaller image, faster cold start, container-aware JVM ergonomics.

---

## 5. Delivery / ops gaps

- **No CI gate:** `deploy.yml` goes push → docker build → prod with **no build or test step**. It also auto-tags on every push (`contents: write`) and runs host-wide `docker system prune -af` (nukes unused images/cache for every other service on the box).
- **Single `application.properties`**, no `dev`/`prod` profiles, no fail-fast validation that required env vars are present.
- **No observability:** actuator not exposed, no health/metrics, no structured logging, no request correlation id.
- **Only test:** `contextLoads()`. The most bug-prone code (`TestUtils` marking, JWT, validators) is untested; no `@WebMvcTest` asserting auth rules; no Testcontainers integration test.

---

## 6. Phased refactor plan

### Phase 0 — Build hygiene _(DONE — migrated to Gradle)_
- ✅ Commit to **Gradle**. Deleted `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`. Replaced the `gradle init` stub `build.gradle.kts` with a real Spring Boot build: `org.springframework.boot` + `io.spring.dependency-management` + `spotless` plugins, Java 21 toolchain, `spring-ai-bom` import, BOM-managed starter versions, ordered annotation processors (lombok → lombok-mapstruct-binding → mapstruct), `developmentOnly` devtools (kept out of `bootJar`), plain `jar` disabled.
- ✅ Removed `spring-boot-starter-data-jdbc` (unused — no `JdbcTemplate`/`@Query` in the codebase).
- ✅ Rewrote `gradle/libs.versions.toml` as a clean version catalog with a `[plugins]` section.
- ✅ `src/test/resources/application.properties` (H2, dummy secrets) so `contextLoads` runs with no external Postgres/OpenAI; added `com.h2database:h2` + `junit-platform-launcher` as `testRuntimeOnly` (Gradle 9 requirement). `./gradlew build` is green (compile + spotless + test + bootJar).
- ✅ Multi-stage `Dockerfile` (Gradle build stage → `21-jre` runtime, non-root `spring` user, cached dependency layer, `MaxRAMPercentage`). Full hardening (layered jar, `HEALTHCHECK`) deferred to Phase 4.
- ✅ `.dockerignore` added.
- ✅ CI: new `build` job (`actions/setup-java` 21 + `gradle/actions/setup-gradle` + `./gradlew build`) gates `deploy`; host-wide `docker system prune -af` narrowed to `docker image prune -f`.
- ⏭️ Deferred: build/push image only on tag `v*` (kept push-to-`master` trigger for now — revisit in Phase 4).
- Notes: Gradle wrapper is **9.7.1**; `google-java-format` pinned to **1.28.0** for JDK 25 daemon compatibility; `spotlessCheck` is bound to `check` (stricter than the old Maven setup, which never ran it).

### Phase 1 — Security hardening _(high priority)_
- Invert to deny-by-default; explicit `permitAll` list.
- Real `AuthenticationEntryPoint` (401 JSON) + `@ExceptionHandler(Exception.class)` catch-all.
- CORS origin allowlist from config.
- Upgrade JJWT to 0.12.x; `Keys.hmacShaKeyFor`; startup check on secret length; parse token once.
- `@ConfigurationProperties` + `@Validated` for `app.jwt.*`, admin creds, datasource — fail fast if missing.
- Re-enable `frameOptions SAMEORIGIN` + HSTS; restore `connection-timeout`; add request-size/keep-alive limits.
- Student flow: opaque attempt token issued at `start`, required (header) on subsequent calls.
- Rate limiting (bucket4j) on `/auth/**`, `/tests/**`, `/questions/generate`.
- AI endpoint: `@Min/@Max` bounds, delimit user input, per-user quota, call timeout + typed exception.
- CSV: real writer + formula-char neutralization.

### Phase 2 — Persistence & domain
- Introduce **Flyway**, baseline current schema, switch to `ddl-auto=validate`.
- All associations `LAZY`; add `@EntityGraph`/fetch-join queries; remove `Hibernate.initialize` loops.
- `CurrentUser` component injected via `@AuthenticationPrincipal`; delete static `getUserFromAuthentication`; push ownership into repository queries.
- Fix bugs: `createAll` collection association; `calculateMark` divide-by-zero; `addSampleQuestions` insufficient-questions guard; single source of truth for quiz position; don't re-insert `Essay` on revisit.
- DTOs → `record`; controllers depend on interfaces (or drop single-impl interfaces entirely and keep only real seams).
- Add indexes + Hibernate batch settings (Flyway + properties).
- Pagination on all list/report endpoints.

### Phase 3 — Real-time & scheduling
- Persist `endsAt`; client-side countdown; server pushes only on state change.
- Replace the static timer map + 1 s tick with a 10–30 s DB sweep for expired sessions — restart-safe, so deploys no longer drop live tests.
- Single `/ws` endpoint, origin allowlist, STOMP `ChannelInterceptor` authorizing each subscription against the attempt token + session id.
- If/when scaling past one instance: external STOMP relay (RabbitMQ) or Redis pub/sub for broadcasts.

### Phase 4 — Delivery
- Multi-stage `Dockerfile`: build with Maven, run on `eclipse-temurin:21-jre` with a layered/extracted jar, non-root user, `HEALTHCHECK`, container-aware JVM flags. No devtools at runtime.
- `application-dev.properties` / `application-prod.properties`; expose actuator health + Prometheus on an internal port.
- Deploy on tag, not every `master` push.

### Phase 5 — Tests & observability
- Unit tests: `TestUtils` marking matrix (single/multiple choice, essay, zero-correct, partial), `JwtUtils`, custom validators.
- `@WebMvcTest` slices: every protected route → `401` without a token; student routes reachable without one.
- Testcontainers Postgres integration test for the full student flow; AI mocked.
- Structured JSON logging, request correlation id (`MDC`), Micrometer metrics, `/actuator/health` wired to the container `HEALTHCHECK`.

---

## 7. Quick wins (hours, not days)

| Change | File |
|--------|------|
| `anyRequest().authenticated()` + permit list | `SecurityConfig` |
| Real 401 entry point | `SecurityConfig` |
| CORS origin allowlist | `SecurityConfig` |
| Remove `connection-timeout=-1` | `application.properties` |
| `@Min/@Max` on AI params | `QuestionController` |
| Bound `questionsCount` server-side | `OpenAIServiceImpl` |
| CSV formula-char escaping | `CsvGeneratorUtils` |
| ✅ Migrate to Gradle, delete Maven files | repo root |
| ✅ Remove `data-jdbc` starter | `build.gradle.kts` |
| Controllers → service interfaces | `QuestionController`, `SampleController` |
| `@ExceptionHandler(Exception.class)` catch-all | `GlobalExceptionHandler` |
| ✅ Add CI build/test gate | `.github/workflows/deploy.yml` |
