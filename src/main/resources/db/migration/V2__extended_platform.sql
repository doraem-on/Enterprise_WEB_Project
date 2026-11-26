ALTER TABLE accounts DROP CONSTRAINT accounts_role_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_role_check CHECK(role IN ('ADMIN','OFFICER','INSTRUCTOR','PARTICIPANT','NGO'));
CREATE TABLE lessons (
 id BIGSERIAL PRIMARY KEY, program_id BIGINT NOT NULL REFERENCES programs(id), title VARCHAR(180) NOT NULL,
 body TEXT NOT NULL, position INTEGER NOT NULL CHECK(position>0), required BOOLEAN NOT NULL DEFAULT TRUE,
 UNIQUE(program_id,position)
);
CREATE TABLE lesson_progress (
 lesson_id BIGINT REFERENCES lessons(id), participant_id BIGINT REFERENCES participants(id),
 completed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(lesson_id,participant_id)
);
CREATE TABLE quiz_questions (
 id BIGSERIAL PRIMARY KEY, lesson_id BIGINT NOT NULL REFERENCES lessons(id), question VARCHAR(1000) NOT NULL,
 option_a VARCHAR(500) NOT NULL, option_b VARCHAR(500) NOT NULL, option_c VARCHAR(500) NOT NULL,
 answer CHAR(1) NOT NULL CHECK(answer IN ('A','B','C'))
);
CREATE TABLE quiz_attempts (
 id BIGSERIAL PRIMARY KEY, lesson_id BIGINT NOT NULL REFERENCES lessons(id), participant_id BIGINT NOT NULL REFERENCES participants(id),
 score INTEGER NOT NULL CHECK(score BETWEEN 0 AND 100), submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE assignments (
 id BIGSERIAL PRIMARY KEY, program_id BIGINT NOT NULL REFERENCES programs(id), title VARCHAR(180) NOT NULL,
 instructions TEXT NOT NULL, kind VARCHAR(20) NOT NULL CHECK(kind IN ('WRITTEN','PRACTICAL')), rubric TEXT NOT NULL,
 required BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE submissions (
 id BIGSERIAL PRIMARY KEY, assignment_id BIGINT NOT NULL REFERENCES assignments(id), participant_id BIGINT NOT NULL REFERENCES participants(id),
 response TEXT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED' CHECK(status IN ('SUBMITTED','REVISION','PASSED')),
 score INTEGER CHECK(score BETWEEN 0 AND 100), feedback TEXT, evidence TEXT, reviewed_by BIGINT REFERENCES accounts(id),
 submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_at TIMESTAMP, UNIQUE(assignment_id,participant_id)
);
CREATE TABLE submission_reviews (
 id BIGSERIAL PRIMARY KEY, submission_id BIGINT NOT NULL REFERENCES submissions(id), reviewer_id BIGINT NOT NULL REFERENCES accounts(id),
 response TEXT NOT NULL, score INTEGER NOT NULL, feedback TEXT NOT NULL, evidence TEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE goals (
 id BIGSERIAL PRIMARY KEY, participant_id BIGINT NOT NULL REFERENCES participants(id), title VARCHAR(180) NOT NULL, description TEXT NOT NULL,
 review_date DATE NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PROPOSED' CHECK(status IN ('PROPOSED','AGREED','COMPLETED','DECLINED')),
 created_by BIGINT NOT NULL REFERENCES accounts(id), agreed_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE milestones (
 id BIGSERIAL PRIMARY KEY, goal_id BIGINT NOT NULL REFERENCES goals(id), title VARCHAR(180) NOT NULL,
 due_date DATE NOT NULL, completed_at TIMESTAMP, completed_by BIGINT REFERENCES accounts(id)
);
CREATE TABLE goal_events (
 id BIGSERIAL PRIMARY KEY, goal_id BIGINT NOT NULL REFERENCES goals(id), actor_id BIGINT NOT NULL REFERENCES accounts(id),
 note VARCHAR(2000) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE organizations (
 id BIGSERIAL PRIMARY KEY, account_id BIGINT UNIQUE NOT NULL REFERENCES accounts(id), name VARCHAR(180) NOT NULL,
 mission TEXT NOT NULL, contact VARCHAR(180) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','SUSPENDED','REJECTED')),
 review_note VARCHAR(2000), reviewed_by BIGINT REFERENCES accounts(id), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE proposals (
 id BIGSERIAL PRIMARY KEY, organization_id BIGINT NOT NULL REFERENCES organizations(id), title VARCHAR(180) NOT NULL,
 description TEXT NOT NULL, kind VARCHAR(20) NOT NULL CHECK(kind IN ('WORKSHOP','PROJECT')), skills TEXT NOT NULL,
 capacity INTEGER NOT NULL CHECK(capacity BETWEEN 1 AND 100), status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED')),
 review_note VARCHAR(2000), reviewed_by BIGINT REFERENCES accounts(id), program_id BIGINT REFERENCES programs(id), project_id BIGINT REFERENCES projects(id),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE resources (
 id BIGSERIAL PRIMARY KEY, name VARCHAR(160) NOT NULL, kind VARCHAR(20) NOT NULL CHECK(kind IN ('MATERIAL','EQUIPMENT')),
 quantity INTEGER NOT NULL CHECK(quantity>=0), unit VARCHAR(40) NOT NULL, location VARCHAR(100) NOT NULL,
 reorder_level INTEGER NOT NULL DEFAULT 0 CHECK(reorder_level>=0)
);
CREATE TABLE resource_transactions (
 id BIGSERIAL PRIMARY KEY, resource_id BIGINT NOT NULL REFERENCES resources(id), project_id BIGINT REFERENCES projects(id),
 actor_id BIGINT NOT NULL REFERENCES accounts(id), change INTEGER NOT NULL CHECK(change<>0), note VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE equipment_reservations (
 id BIGSERIAL PRIMARY KEY, resource_id BIGINT NOT NULL REFERENCES resources(id), session_id BIGINT NOT NULL REFERENCES sessions(id),
 quantity INTEGER NOT NULL CHECK(quantity>0), created_by BIGINT REFERENCES accounts(id), returned_at TIMESTAMP,
 UNIQUE(resource_id,session_id)
);
CREATE TABLE recommendation_feedback (
 id BIGSERIAL PRIMARY KEY, participant_id BIGINT NOT NULL REFERENCES participants(id), program_id BIGINT NOT NULL REFERENCES programs(id),
 rating INTEGER NOT NULL CHECK(rating BETWEEN 1 AND 5), note VARCHAR(1000) NOT NULL DEFAULT '', model VARCHAR(160) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(participant_id,program_id)
);
CREATE TABLE evaluation_cases (
 id BIGSERIAL PRIMARY KEY, query VARCHAR(2000) NOT NULL, expected_program_id BIGINT NOT NULL REFERENCES programs(id),
 rationale VARCHAR(2000) NOT NULL, author_id BIGINT NOT NULL REFERENCES accounts(id), reviewer_id BIGINT REFERENCES accounts(id),
 reviewed_at TIMESTAMP, CHECK(reviewer_id IS NULL OR reviewer_id<>author_id)
);
CREATE TABLE evaluation_runs (
 id BIGSERIAL PRIMARY KEY, actor_id BIGINT NOT NULL REFERENCES accounts(id), model VARCHAR(160) NOT NULL,
 case_count INTEGER NOT NULL, top1 INTEGER NOT NULL, hit5 INTEGER NOT NULL, baseline_top1 INTEGER NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE evaluation_results (
 run_id BIGINT REFERENCES evaluation_runs(id), case_id BIGINT REFERENCES evaluation_cases(id),
 predicted_program_id BIGINT REFERENCES programs(id), expected_program_id BIGINT REFERENCES programs(id),
 query_snapshot TEXT NOT NULL, top5_hit BOOLEAN NOT NULL, PRIMARY KEY(run_id,case_id)
);
CREATE INDEX quiz_progress_lookup ON quiz_attempts(participant_id,lesson_id);
CREATE INDEX goals_participant ON goals(participant_id,status);
CREATE INDEX resource_ledger_lookup ON resource_transactions(resource_id,created_at);
-- Existing lessons and achievements are preserved, not reset.
INSERT INTO lessons(program_id,title,body,position) SELECT id,'Getting started',lesson,1 FROM programs WHERE lesson<>'';
INSERT INTO quiz_questions(lesson_id,question,option_a,option_b,option_c,answer)
 SELECT l.id,p.question,p.option_a,p.option_b,p.option_c,p.answer FROM lessons l JOIN programs p ON p.id=l.program_id WHERE p.question<>'';
INSERT INTO lesson_progress(lesson_id,participant_id) SELECT l.id,e.participant_id FROM lessons l JOIN enrollments e ON e.program_id=l.program_id WHERE e.status='COMPLETED';
INSERT INTO quiz_attempts(lesson_id,participant_id,score) SELECT l.id,a.participant_id,max(a.score) FROM lessons l JOIN assessments a ON a.program_id=l.program_id GROUP BY l.id,a.participant_id;
CREATE FUNCTION seed_program_lesson() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lesson_id BIGINT;
BEGIN
 IF NEW.lesson<>'' THEN
  INSERT INTO lessons(program_id,title,body,position) VALUES(NEW.id,'Getting started',NEW.lesson,1) RETURNING id INTO lesson_id;
  IF NEW.question<>'' THEN INSERT INTO quiz_questions(lesson_id,question,option_a,option_b,option_c,answer) VALUES(lesson_id,NEW.question,NEW.option_a,NEW.option_b,NEW.option_c,NEW.answer); END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER program_initial_lesson AFTER INSERT ON programs FOR EACH ROW EXECUTE FUNCTION seed_program_lesson();
