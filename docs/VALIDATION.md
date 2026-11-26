# Validation — 26 November 2026

## Automated

`JAVA_HOME=<Java 21 home> ./mvnw -q verify` completed successfully against the dedicated local `reconnect_test` PostgreSQL database.

**21 tests; 0 failures; 0 errors; 0 skipped.**

- Eight concurrent enrollment attempts competing for one seat: one enrolled, seven waitlisted, no overbooking.
- Request/approval separation, cancellation capacity release, duplicate rejection, and ownership checks.
- Room, instructor, and participant timetable conflicts; Timefold automatic planning and repeat safety.
- Future attendance rejection and attendance/assessment prerequisites before certificate creation.
- Role restrictions, CSRF enforcement, escaped user text, private feedback, non-identifying public verification.
- All main role pages render through Spring MVC and Thymeleaf.
- Native MiniLM inference loads and relevant text outranks unrelated text.

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

Browser findings led to explicit mobile-menu dismissal and hiding duplicate participant enrollment actions. Demo records created by these checks remain as useful review data (one gardening request and one passed woodcraft quiz).

## Boundaries

Local Java build, PostgreSQL integration, model inference, and browser UI were tested. The Docker recipe and GitHub Actions workflow are supplied but have not been executed in their respective environments. The site has not been pushed or deployed. No production/institutional or clinical validation is claimed.
