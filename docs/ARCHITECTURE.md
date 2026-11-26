# Architecture and workflows

## Scope

The platform supports voluntary rehabilitation programs and community projects. It does not manage convictions, discipline, parole, medical records, payments, or compulsory labour. Personal interests guide opportunities; staff make suitability decisions with participants.

## Components

```mermaid
flowchart LR
 Browser[Browser: Thymeleaf HTML, CSS, small JS] --> Security[Spring Security: sessions and CSRF]
 Security --> Web[Spring MVC controllers]
 Web --> Platform[Transactional platform services]
 Platform --> DB[(PostgreSQL)]
 Web --> ML[Java MiniLM inference]
 ML --> Model[Local ONNX weights and tokenizer]
 Web --> Careers[Attributed O*NET subset]
 Web --> Solver[Timefold scheduling]
 Solver --> Platform
```

## Core data relationships

```mermaid
erDiagram
 ACCOUNT ||--o| PARTICIPANT : owns
 ACCOUNT ||--o{ PROGRAM : teaches
 PARTICIPANT ||--o{ ENROLLMENT : requests
 PROGRAM ||--o{ ENROLLMENT : offers
 PROGRAM ||--o{ SESSION : schedules
 SESSION ||--o{ ATTENDANCE : records
 PARTICIPANT ||--o{ ATTENDANCE : attends
 PARTICIPANT ||--o{ ASSESSMENT : submits
 PROGRAM ||--o{ ASSESSMENT : assesses
 ENROLLMENT ||--o| CERTIFICATE : earns
 PROJECT ||--o{ PROJECT_MEMBER : includes
 PARTICIPANT ||--o{ PROJECT_MEMBER : volunteers
 ACCOUNT ||--o{ FEEDBACK : submits
 ACCOUNT ||--o{ NOTIFICATION : receives
 ACCOUNT ||--o{ AUDIT_EVENT : performs
```

## Enrollment states

- Participant requests create `REQUESTED` records.
- Staff-assisted enrollment creates `ENROLLED` when capacity and schedules allow, otherwise `WAITLISTED`.
- Officers/admins approve requested or waitlisted records after reviewing prerequisites.
- Active/requested/waitlisted records can be cancelled by their participant or staff.
- Only staff or the assigned instructor can complete an active enrollment. Attendance must meet the configured session count and the best knowledge check must be at least 70%.
- Completion creates a unique UUID credential in the same transaction. Repeated completion is rejected.
- Cancelled records can be requested again. Completion history remains immutable through the supported workflows.

## Concurrency

Seat decisions take a `FOR UPDATE` lock on the program row. A shared transaction-scoped PostgreSQL advisory lock serializes enrollment membership changes with timetable writes, protecting against a different-program participant conflict that row locking alone would miss. The lock is held only for transaction duration. The solver runs inside this coordinator transaction for at most two seconds and validates the result before committing. This favors correctness and simplicity at pilot scale; larger deployments should separate plan proposal from short, version-checked commit transactions.

## Authorization matrix

| Capability | Participant | Instructor | Officer | Admin |
| --- | --- | --- | --- | --- |
| Browse catalog | Yes | Yes | Yes | Yes |
| View personal profiles | Own | Assigned enrolled/completed | All | All |
| Request enrollment | Own | No | Assisted | Assisted |
| Approve enrollment | No | No | Yes | Yes |
| Schedule sessions | No | Assigned programs | All | All |
| Record attendance/complete | No | Assigned programs | All | All |
| Take quiz | Own active enrollment | No | No | No |
| Read feedback | Own | Own | All | All |
| Account administration | No | No | No | Yes |
| Public verification | Minimal non-identifying record | Same | Same | Same |

All state-changing forms require CSRF tokens. SQL parameters are bound rather than interpolating user input. Thymeleaf escapes displayed user text. Application errors use non-sensitive messages. Sensitive profile content is not sent to external ML services.

## What counts as a working release

A clean database migrates and seeds once; all four roles can sign in; profile and catalog data persist; requests/approval/cancellation enforce ownership and capacity; scheduling rejects conflicts; instructors record attendance; participants submit assessments; certificates follow verified completion; community progress cannot exceed targets; feedback and notifications respect ownership; ML inference is genuine and its fallback is labelled; automated checks pass and representative workflows are inspected in a browser.
