# ReConnect

A Java-based rehabilitation and community-contribution platform. Participants explore voluntary learning opportunities; officers manage enrollment and scheduling; instructors record attendance and progress; administrators manage access and audit history.

## Run locally

Requirements: Java 21+, PostgreSQL, Python 3 (model download only), curl and tar. Maven is bootstrapped with a checksum by `./mvnw`.

```sh
createdb -h localhost reconnect
createdb -h localhost reconnect_test
./scripts/run-local.sh
```

Open **http://localhost:8080**. PostgreSQL must already be running. `run-local.sh` defaults the database username to the current OS user. For password-protected installations export `DATABASE_USER`, `DATABASE_PASSWORD`, and optionally `DATABASE_URL` (a JDBC URL). `.env.example` documents settings; the Java process reads environment variables, not `.env` automatically.

First launch downloads the ~86 MB ONNX model and tokenizer and resolves Java dependencies. Data persists in PostgreSQL across restarts. Flyway applies versioned schema migrations. Demo data is seeded only when the accounts table is empty and `DEMO_MODE=true`.

### Demo accounts

All use **ReConnect!2026** by default. Login-page buttons fill credentials, but do not submit automatically.

| Role | Email |
| --- | --- |
| Officer | officer@reconnect.local |
| Participant | participant@reconnect.local |
| Instructor | instructor@reconnect.local |
| Second instructor | coach@reconnect.local |
| Administrator | admin@reconnect.local |

The fictional participant account has an active woodcraft enrollment and a completed reading-circle program, providing an immediately viewable portfolio and certificate. Admin account creation can add further participant login profiles. Staff-created standalone profiles support assisted use without an account.

## Implemented workflows

- Role-specific dashboards, session login/logout, CSRF protection, password hashing, server-side authorization.
- Participant profiles and personal goals, searchable program catalog, program creation, learning material and knowledge checks.
- Enrollment requests, staff approval, waitlists, cancellation, seat limits, progress and completion rules.
- Manual scheduling and Timefold-based automatic planning; room/instructor/participant conflict checks.
- Instructor attendance and feedback, completion certificates, privacy-preserving public verification, printable portfolios.
- Local MiniLM semantic recommendations and an attributed O*NET career-exploration subset, with a labelled keyword fallback.
- Voluntary community projects, membership, progress targets, private feedback/support requests and staff responses.
- Notifications, administrative account creation, audit history, operational reports.
- Responsive layouts, mobile navigation, empty/error states, print views, duplicate-submit protection.

## Stack and design

Java 21, Spring Boot 3.4, Spring MVC, Thymeleaf, Spring Security, **Spring JDBC**, PostgreSQL, Flyway, ONNX Runtime, DJL tokenizer, and Timefold Solver. Plain CSS and a small JavaScript file keep browser code minimal. Explicit Spring JDBC transactions make locking and authorization queries reviewable; this implementation uses JDBC rather than the initially proposed JPA layer. Python is used only for reproducible model downloads and public dataset preparation; the application and inference run in Java.

Layering: `WebController` → `Platform` transactional services → PostgreSQL. `RecommendationEngine` performs local embeddings; `Scheduling` wraps Timefold and delegates final writes to the transaction service. DB migrations define uniqueness and integrity constraints.

## Correctness and security

Enrollment locks the program row before checking seat capacity. A transaction-scoped advisory lock coordinates enrollment changes with schedule creation so concurrent requests cannot introduce participant timetable conflicts. Only the affected record workflows use this scheduling lock; this pilot favors simple correctness over high write throughput. Enrollment/certificate uniqueness prevents duplicates. Public certificate verification never reveals participant names. Instructor access is limited to their programs and associated enrolled/completed profiles. Participant messages and notifications are scoped by authenticated identity.

## Tests

Create the dedicated `reconnect_test` database, then:

```sh
export DATABASE_USER="$(id -un)"
python3 scripts/download_models.py
./mvnw verify
```

Tests use real PostgreSQL and refuse to reset any database not named `reconnect_test`. They clear only that database's application tables. Coverage includes an eight-thread last-seat race, cross-role access, CSRF, escaping, capacity release, timetable conflicts, duplicate automatic planning, attendance/completion prerequisites, public credential privacy, page rendering, and real model inference. GitHub Actions runs the same suite using PostgreSQL 16. Browser verification is documented separately in `docs/VALIDATION.md`.

## Docker alternative

```sh
python3 scripts/download_models.py
docker compose up --build
```

The bundled Compose configuration is a localhost-only demonstration with seeded accounts. This path requires Docker; it is not a substitute for a production configuration. Do not expose demo credentials publicly. Use TLS, disable demonstration seeding, configure secure session cookies, backups, credential rotation, and institutional access review before a real deployment. No claim of production readiness or clinical validation is made.

## Data and limitations

See [data and model documentation](docs/DATA_AND_MODELS.md). No offence-based ranking, recidivism prediction, diagnosis, or automatic work assignment is performed. Career skill priorities are reference information rather than an assessment of a person's ability. This version has authored lessons and single-question checks, in-app notifications (no email/SMS), and internal completion certificates. It does not integrate with a real correctional institution or employment provider.

Nothing is pushed or published automatically by the local setup scripts.
