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

First launch downloads the ~86 MB ONNX model and tokenizer and resolves Java dependencies. Data persists in PostgreSQL across restarts. Flyway applies versioned schema migrations. Core demo data is seeded when the accounts table is empty and `DEMO_MODE=true`. The idempotent expansion seed adds demonstration curriculum, an NGO account, inventory, and a development plan when missing. Keep demo mode disabled outside demonstration environments.

### Demo accounts

All use **ReConnect!2026** by default. Login-page buttons fill credentials, but do not submit automatically.

| Role | Email |
| --- | --- |
| Officer | officer@reconnect.local |
| Participant | participant@reconnect.local |
| Instructor | instructor@reconnect.local |
| Second instructor | coach@reconnect.local |
| Administrator | admin@reconnect.local |
| NGO partner | partner@reconnect.local |

The fictional participant account has an active woodcraft enrollment and a completed reading-circle program, providing an immediately viewable portfolio and certificate. Admin account creation can add further participant login profiles. Staff-created standalone profiles support assisted use without an account.

## Implemented workflows

- Role-specific dashboards, session login/logout, CSRF protection, password hashing, server-side authorization.
- Participant profiles, searchable programs, multi-lesson courses, multi-question quizzes, written assignments, supervised practical assessment, instructor feedback and revision history.
- Personal development plans: participant agreement, dated milestones, review notes, visual timelines, and staff completion review.
- Program editing/archive/restore, FIFO waitlist promotion, conflict-checked rescheduling, account editing/deactivation with live session revocation.
- Approved NGO organizations propose workshops or community projects; staff review proposals. Workshop approvals create archived drafts until curriculum is ready.
- Material stock ledgers tied to community projects; equipment reservations enforce overlapping-session capacity and support returns.
- Optional recommendation ratings with withdrawal, independently reviewed label workflow, top-1/hit@5 metrics, keyword baseline, and case-level results.
- English, Hindi, and Punjabi navigation/core controls, larger text, high contrast, keyboard focus/skip links, and a simplified participant kiosk.
- Enrollment requests, staff approval, waitlists, cancellation, seat limits, progress and completion rules.
- Manual scheduling and Timefold-based automatic planning; room/instructor/participant conflict checks.
- Instructor attendance and feedback, completion certificates, privacy-preserving public verification, printable portfolios.
- Local MiniLM semantic recommendations and an attributed O*NET career-exploration subset, with a labelled keyword fallback.
- Voluntary community projects, membership, progress targets, private feedback/support requests and staff responses.
- Notifications, administrative account creation, audit history, operational reports.
- Responsive layouts, mobile navigation, empty/error states, print views, duplicate-submit protection.

## Stack and design

Java 21, Spring Boot 3.4, Spring MVC, Thymeleaf, Spring Security, **Spring JDBC**, PostgreSQL, Flyway, ONNX Runtime, DJL tokenizer, and Timefold Solver. Plain CSS and a small JavaScript file keep browser code minimal. Explicit Spring JDBC transactions make locking and authorization queries reviewable; this implementation uses JDBC rather than the initially proposed JPA layer. Python is used only for reproducible model downloads and public dataset preparation; the application and inference run in Java.

Layering: `WebController` / `ExtendedController` → focused learning, development, administration, partnership, resource, evaluation and `Platform` transactional services → PostgreSQL. `RecommendationEngine` performs local embeddings; `Scheduling` wraps Timefold and delegates final writes to the transaction service. DB migrations define uniqueness and integrity constraints.

## Correctness and security

Enrollment locks the program row before checking seat capacity. A transaction-scoped advisory lock coordinates enrollment changes with schedule creation so concurrent requests cannot introduce participant timetable conflicts. Only the affected record workflows use this scheduling lock; this pilot favors simple correctness over high write throughput. Enrollment/certificate uniqueness prevents duplicates. Public certificate verification never reveals participant names. Instructor access is limited to their programs and associated enrolled/completed profiles. Participant messages and notifications are scoped by authenticated identity. Resource row locks prevent negative stock; equipment reservations and rescheduling share the timetable coordinator lock. Reviews retain submitted work snapshots. NGO accounts cannot access participant profiles or learning records. Deactivated accounts lose existing authenticated sessions on their next request.

## Tests

Create the dedicated `reconnect_test` database, then:

```sh
export DATABASE_USER="$(id -un)"
python3 scripts/download_models.py
./mvnw verify
```

Tests use real PostgreSQL and refuse to reset any database not named `reconnect_test`. They clear only that database's application tables. Coverage includes an eight-thread last-seat race, cross-role access, CSRF, escaping, capacity release, timetable conflicts, duplicate automatic planning, attendance/completion prerequisites, public credential privacy, page rendering, and real model inference. GitHub Actions runs the same suite using PostgreSQL 16. The expanded suite also covers assignment revision history, completion gates, development-plan agreement, partner approval, stock races, equipment overlap, reschedule rollback, account revocation, reviewed ML cases, multilingual rendering, and populated workflow screens. Browser verification is documented separately in `docs/VALIDATION.md`.

## Docker alternative

```sh
python3 scripts/download_models.py
docker compose up --build
```

The bundled Compose configuration is a localhost-only demonstration with seeded accounts. This path requires Docker; it is not a substitute for a production configuration. Do not expose demo credentials publicly. Use TLS, disable demonstration seeding, configure secure session cookies, backups, credential rotation, and institutional access review before a real deployment. No claim of production readiness or clinical validation is made.

## Data and limitations

See [data and model documentation](docs/DATA_AND_MODELS.md). No offence-based ranking, recidivism prediction, diagnosis, or automatic work assignment is performed. Career skill priorities are reference information rather than an assessment of a person's ability. This version has authored multi-lesson courses and configurable quizzes, text-based assignment submissions, staff-recorded practical evidence, in-app notifications (no email/SMS), and internal completion certificates. Interface translations cover navigation and common participant controls; authored courses and some administrative guidance remain in English. The English embedding model is unchanged by interface language. A second account must review benchmark labels, but real independent human labels still need to be collected; no independently validated accuracy is claimed. It does not integrate with a real correctional institution or employment provider.

Nothing is pushed or published automatically by the local setup scripts.

## Expanded feature tour

| Screen | Who uses it | Main action |
| --- | --- | --- |
| `/learning` | Participants, instructors, staff | Read lessons, take quizzes, submit/review work |
| `/plans` | Participants and staff | Agree goals and record milestone progress |
| `/manage/programs` | Staff | Edit/archive programs and promote waiting participants |
| `/sessions/{id}/edit` | Assigned instructor or staff | Reschedule with conflict checks |
| `/admin` | Administrator | Create/edit/deactivate accounts |
| `/partners` | NGO and staff | Apply, propose, and review partnerships |
| `/resources` | Instructors and staff | Reserve equipment; staff maintain material stock |
| `/evaluation` | Instructors and staff | Review relevance cases; staff run evaluations |
| `/kiosk` | Participant | Six large activity shortcuts and idle sign-out |

The kiosk page signs out after five minutes of inactivity while it is open. Other pages use the configured server session timeout. Historical certificates remain valid when curriculum expands; new certificates require current required learning and reviewed assessments.
