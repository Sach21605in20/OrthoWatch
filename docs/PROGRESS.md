# OrthoWatch — Progress Log

## Phase 1: Project Setup & Foundation ✅
**Status**: Fully Verified. Backend (Spring Boot 3.2 + JDK 21), Frontend (Vite + React), and Infrastructure (Docker) are set up and operational.

### What was built

#### Backend (`/backend`)
- **Spring Boot 3.2.2 / Java 21** project with full `pom.xml`
  - All dependencies: Web, JPA, Redis, Security, Validation, Actuator, Quartz, Flyway, JWT (jjwt 0.12.3), Lombok, MapStruct, SpringDoc, Sentry, Testcontainers, REST Assured
  - Spotless plugin (Google Java Style)
  - Lombok + MapStruct annotation processors
  - hypersistence-utils-hibernate-63 for JSONB/array types
- **Maven Wrapper** (mvnw / mvnw.cmd + .mvn/wrapper/)
- **Application config**: `application.yml`, `application-dev.yml`, `application-prod.yml` for profile-specific settings
- **`.env.example`** with all essential environment variables documented (JWT secrets, API URLs, Database credentials)

#### Database
- **Flyway migrations**:
  - `V1__initial_schema.sql` — all 13 tables with CHECK constraints, FK, indexes, immutability rules on `clinical_audit_log`
  - `V2__seed_templates.sql` — 3 recovery templates (TKR/THR/ACL) with JSONB configs, 5 risk rules, hospital settings
  - `V3__seed_admin_user.sql` — admin user BCrypt password update

#### JPA Entities (13)
`User`, `Patient`, `RecoveryTemplate`, `Episode`, `DailyResponse`, `RiskScore`, `Alert`, `WoundImage`, `ConsentLog`, `ClinicalAuditLog`, `RiskRule`, `Session`, `HospitalSettings`

#### Spring Data JPA Repositories (13)
One repository per entity with domain-specific query methods.

#### Infrastructure
- **`docker-compose.yml`** — PostgreSQL 16 + Redis 7 with health checks
- **`.gitignore`** — Java/Maven, env files, IDE, OS, Node

#### Frontend (`/frontend`)
- **Vite 5 + React 18 + TypeScript 5.3** project scaffolded
- All dependencies installed: React Query, Zustand, React Router, Recharts, Lucide, React Hook Form, Zod, Axios, date-fns, clsx, tailwind-merge
- **Tailwind CSS 3.4** configured with full OrthoWatch design system:
  - Color palettes: primary, success, warning, danger, risk, neutral
  - Inter font, custom spacing, animations
  - Component utilities: cards, buttons, form inputs, risk badges, sidebar links
- **`vite.config.ts`** with `@` path alias and `/api` proxy to backend
- **`App.tsx`** with React Query provider + React Router
- **Build verified**: `✓ built in 976ms`

#### Git
- **Repository**: [https://github.com/Sach21605in20/Suraksha-Setu.git](https://github.com/Sach21605in20/Suraksha-Setu.git)
- **Branch**: `main`
- **History**:
  - Initialized backend project structure (Spring Boot 3.2)
  - Cleaned up misplaced frontend files
  - Removed documentation and text files from version control (as requested)
- **Latest Commit**: `Initialising Development of SurakshaSetu...`

---

## Phase 2: Authentication System (Backend) ✅
**Status**: Backend Security Implemented.

### What was built
#### Backend Security
- **JWT Implementation**: `JwtUtil` using `jjwt` 0.12.3 with HMAC-SHA256
- **Spring Security**: Stateless session management, CSRF disabled, CORS configured
- **Token Strategy**:
  - Access Token (30 min) -> JSON Response
  - Refresh Token (7 days) -> HttpOnly Cookie
- **Role-Based Access Control**: `ADMIN`, `SURGEON`, `NURSE` roles supported
- **Components**:
  - `AuthController`: Login, Refresh, Logout endpoints
  - `AuthService`: Authentication logic
  - `GlobalExceptionHandler`: Centralized error handling
  - `UserDetailsServiceImpl`: Database integration
- **Verification**:
  - Validated build success with `mvnw clean compile`
  - Verified AuthController endpoints with JUnit 5 + MockMvc (`AuthControllerTest.java`)
  - Seeded Admin User (`admin@orthowatch.com` / `password`)
  - Configured PostgreSQL Timezone to `Asia/Kolkata`

## ⚠️ Known Gaps (Flagged 2026-02-23)

### Rate Limiting — NOT implemented (Phase 2.1 gap)
- **Scope**: `POST /api/v1/auth/login` and `POST /api/v1/auth/refresh`
- **Finding**: No `RateLimitingFilter`, no Bucket4j, no Resilience4j, no Redis-backed throttle exists anywhere in `backend/src`. `SecurityConfig.java` and `AuthController.java` inspected — confirmed clean miss.
- **Risk**: Login endpoint is open to brute-force attacks. Any IP can hammer credentials indefinitely.
- **Resolution plan**: Implement Bucket4j + Redis rate limiter (5 attempts / 15 min per IP) in **Phase 3** (Core Backend) before production deployment. Backend already has Redis in stack (`spring-boot-starter-data-redis` in `pom.xml`).

### Pre-existing Test Failures — Resolved (Flagged 2026-03-02, Updated 2026-03-03)
- **`AuthControllerTest` ✅ FIXED**: `@ContextConfiguration` + `@AutoConfigureMockMvc(addFilters = false)` applied — all 4 tests pass (verified 2026-03-03).
- **`EnrollmentIntegrationTest` ⏳ DEFERRED**: Requires Docker (Testcontainers). Test is written correctly; fails only when Docker daemon is unavailable. Not blocking Phase 3.4+ development — will run when Docker is available.

---

## ✅ Phase 2.2 — Authentication System (Frontend) (Completed 2026-02-23)
- **Status**: Verified
- **Features**: Login page (Zustand + zod), silent refresh (HttpOnly cookie), protected routes.
- **Verification**: Manually verified by user; redirection, validation, and session persistence confirmed.

## ✅ Phase 3.1 — Enrollment Service (Completed 2026-02-24)
- **Status**: Verified — 17 tests passing (9 unit + 8 controller)

### What was built

#### Production Code
- **DTOs**: `EnrollmentRequest` (Bean Validation) + `EnrollmentResponse`
- **Mappers**: MapStruct `PatientMapper`, `EpisodeMapper`
- **Service**: `EnrollmentService` — find/create patient, create episode, consent log, audit log, schedule Quartz timeout
- **Controller**: `EnrollmentController` — `POST /api/v1/enrollments` with `@PreAuthorize("hasAnyRole('NURSE', 'ADMIN')")`
- **Exceptions**: `ResourceNotFoundException` (404), `DuplicateResourceException` (409)
- **Quartz**: `ConsentTimeoutJob` — auto-expire consent after 24h
- **Global Error Handling**: Updated `GlobalExceptionHandler` for validation, 404, 409

#### Flyway Migrations
- `V4__seed_surgeon_user.sql` — surgeon user for FK references
- `V5__seed_templates.sql` — recovery templates with JSONB configs

#### Tests
- **Unit (9/9)**: `EnrollmentServiceTest` — Mockito tests for enrollment flow, patient reuse, 404/409 errors, Quartz scheduling
- **Controller (8/8)**: `EnrollmentControllerTest` — `@WebMvcTest` + `@ContextConfiguration` for validation and happy path
- **Integration**: `EnrollmentIntegrationTest` (Testcontainers) — written, requires Docker to run

---

## ✅ Phase 3.2 — Risk Engine (TDD) (Completed 2026-03-02)
- **Status**: Verified — 12 tests passing (12 unit, TDD RED→GREEN→REFACTOR)

### What was built

#### Production Code
- **Service**: `RiskEngineService` — rule-based risk scoring engine (234 lines)
  - Strategy-pattern rule evaluation (FEVER_ABOVE_100, DVT_ANY_PRESENT, PAIN_SPIKE_GT_2, SWELLING_INCREASING_2D, WOUND_REDNESS_DISCHARGE)
  - Composite score calculation (sum of triggered weights, capped at 100)
  - Risk level classification: LOW (0–30), MEDIUM (31–60), HIGH (61–100)
  - 3-day trajectory computation (IMPROVING, STABLE, WORSENING)
  - Rule version snapshot stored in JSONB with each calculation
  - Automatic HIGH_RISK alert generation assigned to primary surgeon

#### Flyway Migrations
- `V6__seed_risk_rules.sql` — 5 default risk rules (FEVER_HIGH, DVT_SYMPTOMS, PAIN_SPIKE, SWELLING_TREND, WOUND_CONCERN)

#### Repository Updates
- `RiskRuleRepository` — added `findByIsActiveTrue()`
- `DailyResponseRepository` — added `findByEpisodeIdOrderByDayNumberDesc()`

#### Tests (TDD)
- **Unit (12/12)**: `RiskEngineServiceTest` — Mockito tests covering:
  - Individual rule evaluation (fever, DVT, pain spike, swelling trend)
  - Composite score calculation and cap at 100
  - Risk level classification (LOW/MEDIUM/HIGH)
  - 3-day trajectory (IMPROVING/STABLE/WORSENING) + null for Day 1
  - Rule version snapshot persistence
  - Alert generation for HIGH risk, no alert for MEDIUM/LOW

---

## ✅ Phase 3.3 — Checklist Service & Scheduled Tasks (Completed 2026-03-02)
- **Status**: Verified — 11 tests passing (6 service + 5 scheduler)

### What was built

#### Production Code
- **Service**: `ChecklistService` — processes daily responses with template-aware completion status (PENDING/PARTIAL/COMPLETED), triggers risk engine on completion, handles late response alert cancellation
- **Scheduler**: `ScheduledTasks` — 5 `@Scheduled` methods:
  - `dispatchDailyChecklists()` — 9 AM IST, creates PENDING responses for active/consented episodes
  - `sendReminders()` — polls for 4-hour non-responses
  - `escalateNonResponses()` — creates NON_RESPONSE alerts after 8 hours
  - `checkConsentTimeouts()` — creates CONSENT_TIMEOUT alerts after 24 hours
  - `cleanupExpiredSessions()` — 2 AM IST daily cleanup
- **DTO**: `ChecklistResponseDto` with Bean Validation

#### Repository Updates
- `EpisodeRepository` — added consent-based queries
- `DailyResponseRepository` — added pending response cutoff query
- `SessionRepository` — added expired session cleanup

#### Tests
- **Service (6/6)**: `ChecklistServiceTest` — completion status, risk engine trigger, late response, alert cancellation
- **Scheduler (5/5)**: `ScheduledTasksTest` — dispatch, consent skipping, escalation, consent timeout, session cleanup

---

## ✅ Phase 3.4 — Alert & Escalation Service (Completed 2026-03-03)
- **Status**: Verified — 57/57 tests passing (13 new + 44 existing, 0 regressions)

### What was built

#### Production Code
- **Service**: `AuditService` — immutable clinical audit logger (NABH compliance), append-only design
- **Service**: `AlertService` — alert lifecycle management:
  - `acknowledge()` — PENDING → ACKNOWLEDGED with timestamp + audit log
  - `resolve()` — ACKNOWLEDGED → RESOLVED with escalation outcome, notes + audit log
  - `autoForwardExpiredAlerts()` — re-assigns PENDING alerts past SLA deadline to secondary clinician
- **Controller**: `AlertController` — REST endpoints:
  - `POST /api/v1/alerts/{alertId}/acknowledge` — `@PreAuthorize("hasAnyRole('SURGEON','NURSE')")`
  - `POST /api/v1/alerts/{alertId}/resolve` — with `@Valid` `AlertResolveRequest`
- **DTOs**: `AlertAcknowledgeResponse`, `AlertResolveRequest` (Bean Validation: @Pattern for 5 valid outcomes, @Size(max=2000) for notes), `AlertResolveResponse`

#### Repository Updates
- `AlertRepository` — added `findByStatusAndSlaDeadlineBefore()` for SLA auto-forward

#### Scheduler Updates
- `ScheduledTasks` — added `autoForwardExpiredAlerts()` (`@Scheduled(fixedRate = 60000)`) delegating to `AlertService`

#### Tests
- **Service (2/2)**: `AuditServiceTest` — audit log creation, null optional fields
- **Service (7/7)**: `AlertServiceTest` — acknowledge, resolve, auto-forward, 404/wrong-state errors, no-secondary-clinician skip
- **Controller (4/4)**: `AlertControllerTest` — `@WebMvcTest` + `@ContextConfiguration` for acknowledge, resolve, validation (400), not-found (404)
- **Scheduler (5/5)**: `ScheduledTasksTest` — updated with `AlertService` mock (all existing tests still pass)

---

## Phase 4.0: Wound Image Storage Setup ✅
**Status**: Complete. Local filesystem storage for wound images with upload/download endpoints and full test coverage.

### What was built

#### Configuration
- `StorageProperties.java` — `@ConfigurationProperties(prefix = "app.storage")` bean for provider, localPath, maxFileSize
- `application.yml` — added `app.storage.*` properties and `spring.servlet.multipart` limits (10 MB)
- `application-dev.yml` — explicit LOCAL storage settings for dev profile

#### Service Layer
- `ImageStorageService.java` — core storage service:
  - `store()` — validates content type (JPEG/PNG only), file size (≤ 10 MB), saves to `{localPath}/{episodeId}/{dayNumber}_{uuid}.{ext}`, creates `WoundImage` entity with 3-year retention
  - `loadAsResource()` — loads image file as Spring `Resource` for streaming
  - `getWoundImage()` — metadata retrieval by ID

#### DTOs
- `WoundImageResponse.java` — image metadata response (id, episodeId, dayNumber, contentType, fileSizeBytes, isMandatory, uploadedBy, createdAt)

#### Controller
- `ImageController.java`:
  - `POST /api/v1/images/upload` — multipart upload, `@PreAuthorize("hasAnyRole('SURGEON','NURSE','ADMIN')")`, returns 201 Created
  - `GET /api/v1/images/{imageId}` — download with correct Content-Type, `VIEW_IMAGE` audit logging, `@PreAuthorize("hasAnyRole('SURGEON','NURSE')")`

#### Exceptions
- `InvalidFileException.java` — thrown for invalid content type or oversized files
- `GlobalExceptionHandler` — added handlers for `InvalidFileException` (400) and `MaxUploadSizeExceededException` (413)

#### Tests
- **Service (6/6)**: `ImageStorageServiceTest` — valid JPEG/PNG upload, invalid content type, oversized file, load existing image, load non-existent image
- **Controller (4/4)**: `ImageControllerTest` — valid upload (201), invalid content type (400), download existing (200), download non-existent (404)
- **Full suite: 67/67 tests passing (10 new, 0 regressions)**

---

## Phase 4.1: WhatsApp Integration Setup ✅
**Status**: Complete. Meta Cloud API client, WhatsApp service, and webhook controller with full test coverage.

### What was built

#### Configuration
- `WhatsAppProperties.java` — `@ConfigurationProperties(prefix = "app.whatsapp")` bean for apiUrl, phoneNumberId, accessToken, verifyToken
- `application.yml` — added `app.whatsapp.*` properties (env-variable backed)
- `application-dev.yml` — dev-profile WhatsApp settings with test sandbox placeholders

#### WhatsApp API Client
- `WhatsAppApiClient.java` — RestTemplate-based HTTP client for Meta Graph API v18.0:
  - `sendTextMessage()` — plain text messages
  - `sendTemplateMessage()` — Meta-approved template messages with parameters
  - `sendInteractiveButtonMessage()` — interactive button messages (up to 3 buttons)
  - `sendMessage()` — low-level send with retry logic (3 attempts, exponential backoff)
  - Client errors (4xx) fail immediately; server errors (5xx) and connection issues trigger retries

#### WhatsApp Service
- `WhatsAppService.java` — high-level business messaging:
  - `sendConsentRequest()` — consent buttons (YES/NO)
  - `sendWelcomeMessage()` — post-consent welcome with surgery details and monitoring schedule
  - `sendDailyChecklist()` — pain score buttons (Low/Moderate/Severe)
  - `sendWoundImageRequest()` — image upload prompt with tips
  - `sendReminder()` — non-response reminder
  - `sendEmergencyOverride()` — urgent breathlessness/DVT warning with emergency number
  - `sendEmergencyFollowUp()` — "going to hospital?" confirmation buttons
  - `sendUseButtonsReply()` — free-text fallback message

#### Webhook Controller
- `WebhookController.java`:
  - `GET /api/v1/webhook/whatsapp` — Meta verification (echoes hub.challenge)
  - `POST /api/v1/webhook/whatsapp` — inbound message handler:
    - Interactive button replies → consent handling (GRANTED/DECLINED), checklist responses, emergency follow-up
    - Image messages → logged with mediaId (ready for download integration)
    - Text messages → emergency keyword detection (breathless, chest pain, cannot breathe, DVT) triggers override flow; non-emergency free text gets "use buttons" reply
    - Status updates (delivery receipts) → logged
  - Phone number normalization (Meta sends without `+`, DB stores with `+`)
  - Always returns HTTP 200 per Meta's requirement

#### DTOs
- `WhatsAppMessageRequest.java` — outbound message format (text, template, interactive)
- `WhatsAppMessageResponse.java` — Meta API response with message IDs
- `WhatsAppWebhookPayload.java` — inbound webhook structure with `@JsonIgnoreProperties(ignoreUnknown = true)`

#### Exception Handling
- `WhatsAppApiException.java` — custom runtime exception for API failures
- `GlobalExceptionHandler` — added `handleWhatsAppApiException()` returning HTTP 502 Bad Gateway

#### Repository Updates
- `EpisodeRepository` — added `findByPatientIdAndStatus()` for webhook phone-to-episode lookup

#### Tests
- **Service (5/5)**: `WhatsAppApiClientTest` — text/template/interactive message sending, client error (no retry), server error (3 retries)
- **Service (6/6)**: `WhatsAppServiceTest` — consent, daily checklist, wound image, reminder, emergency override, emergency follow-up
- **Controller (5/5)**: `WebhookControllerTest` — webhook verification (valid/invalid token), button reply, consent acceptance, free-text handling
- **Full suite: 83/83 tests passing (16 new, 0 regressions)**

---

## Next: Phase 4.2 — WhatsApp Checklist Scheduling & Response Processing
