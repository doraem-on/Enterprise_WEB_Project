# Validation — 26 November 2026

## Automated

`JAVA_HOME=<Java 21 home> ./mvnw -q verify` completed successfully against the dedicated local `reconnect_test` PostgreSQL database.

**35 tests; 0 failures; 0 errors; 0 skipped.**

- Eight concurrent enrollment attempts competing for one seat: one enrolled, seven waitlisted, no overbooking.
- Request/approval separation, cancellation capacity release, duplicate rejection, and ownership checks.
- Room, instructor, and participant timetable conflicts; Timefold automatic planning and repeat safety.
- Future attendance rejection and attendance/assessment prerequisites before certificate creation.
- Role restrictions, CSRF enforcement, escaped user text, private feedback, non-identifying public verification.
- All main role pages render through Spring MVC and Thymeleaf.
- Native MiniLM inference loads and relevant text outranks unrelated text.
- Required lessons, multi-question quizzes, written/practical reviews and revision history gate new certificates.
- Goal agreement, milestone ordering and staff-only final review; cross-profile access rejected.
- Program capacity editing, archive/restore and FIFO waitlist promotion.
- Account deactivation invalidates an existing authenticated session.
- NGO suspension/proposal approval, draft workshop creation, duplicate-decision rejection and participant-record isolation.
- Concurrent material consumption leaves non-negative stock and an atomic ledger.
- Equipment overlap rejection, adjacent reuse, release, and reschedule rollback.
- Optional rating consent/withdrawal, different-reviewer labels, stored evaluation case results.
- Hindi/Punjabi session preferences, kiosk role restrictions, and populated partnership/resource/evaluation screens.

The suite resets only the named test database, not the local demo database. Flyway successfully migrated the local app database independently.

## Small recommendation smoke evaluation

Eight manually authored examples covering the eight demo programs:

- MiniLM top-1 matches: **8/8**.
- Keyword baseline top-1 matches: **6/8**.
- Intended program in MiniLM top five: **8/8**.

The cases are committed in `scripts/evaluation_cases.json`; reports regenerate under `target/recommendation-evaluation.*`. These are development smoke cases, not an independently labelled benchmark, calibrated probabilities, or evidence of rehabilitation effectiveness.

## Browser verification

Inspected the running site using the in-app browser:

- Public landing page and login form.
- Officer login and dashboard; program and recommendation views with real model status and O*NET references.
- Participant login, program detail, successful gardening enrollment request, and persisted requested state.
- Successful woodcraft knowledge-check submission and 100% result displayed in progress.
- Completed reading-circle certificate and its privacy-preserving verification link.
- Narrow-screen certificate layout, mobile navigation, and no horizontal document overflow in the measured narrow viewport.
- Desktop dashboard layout inspected with a temporary viewport override; override reset afterwards.

Expanded release browser checks:

- Participant completed the second lesson and its two-question quiz; 100% result persisted.
- Participant submitted a written reflection; assigned instructor reviewed it, recording a score and evidence; the saved state became `PASSED`.
- Participant agreed to the seeded development plan; milestone controls and the activity timeline appeared.
- Hindi and Punjabi kiosk screens rendered correctly; larger text worked without horizontal document overflow in the measured narrow viewport.
- NGO signed into a restricted portal and submitted a fictional workshop proposal; its persisted state became `PENDING` for staff review.
- Desktop resource inventory, ledger, and equipment controls rendered; sidebar contrast was corrected after visual inspection.
- Temporary browser viewport overrides and text preferences were restored.

These fictional demo actions remain as useful review data alongside the previous gardening enrollment request. No practical observation or independent human ML benchmark was invented.

## Boundaries

Local Java build, PostgreSQL integration, model inference, and browser UI were tested. The initial-release GitHub Actions run passed on its PostgreSQL 16 service. The expanded suite passed locally; the new remote run is checked after push. The Docker recipe has not been executed. The initial release was pushed to `codex/reconnect-platform`; this expansion is prepared for the same branch. No public hosting deployment was performed. No production/institutional or clinical validation is claimed.
