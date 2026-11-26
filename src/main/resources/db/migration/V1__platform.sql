CREATE TABLE accounts (
 id BIGSERIAL PRIMARY KEY, email VARCHAR(180) UNIQUE NOT NULL, password VARCHAR(100) NOT NULL,
 name VARCHAR(120) NOT NULL, role VARCHAR(20) NOT NULL CHECK(role IN ('ADMIN','OFFICER','INSTRUCTOR','PARTICIPANT')), active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE participants (
 id BIGSERIAL PRIMARY KEY, account_id BIGINT UNIQUE REFERENCES accounts(id), name VARCHAR(120) NOT NULL,
 reference VARCHAR(40) UNIQUE NOT NULL, education VARCHAR(120) NOT NULL DEFAULT '', skills TEXT NOT NULL DEFAULT '',
 interests TEXT NOT NULL DEFAULT '', goals TEXT NOT NULL DEFAULT '', language VARCHAR(60) NOT NULL DEFAULT 'English',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE programs (
 id BIGSERIAL PRIMARY KEY, title VARCHAR(160) NOT NULL, category VARCHAR(40) NOT NULL CHECK(category IN ('Vocational','Education','Creative','Wellbeing','Physical')),
 description TEXT NOT NULL, skills TEXT NOT NULL, prerequisites TEXT NOT NULL DEFAULT '', instructor_id BIGINT REFERENCES accounts(id),
 capacity INTEGER NOT NULL CHECK(capacity BETWEEN 1 AND 100), duration INTEGER NOT NULL CHECK(duration BETWEEN 1 AND 100),
 location VARCHAR(100) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','ARCHIVED')),
 lesson TEXT NOT NULL DEFAULT '', question TEXT NOT NULL DEFAULT '', option_a TEXT NOT NULL DEFAULT '', option_b TEXT NOT NULL DEFAULT '', option_c TEXT NOT NULL DEFAULT '', answer VARCHAR(1) NOT NULL DEFAULT 'A'
);
CREATE TABLE enrollments (
 id BIGSERIAL PRIMARY KEY, participant_id BIGINT NOT NULL REFERENCES participants(id), program_id BIGINT NOT NULL REFERENCES programs(id),
 status VARCHAR(20) NOT NULL CHECK(status IN ('REQUESTED','WAITLISTED','ENROLLED','COMPLETED','CANCELLED')),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(participant_id,program_id)
);
CREATE TABLE sessions (
 id BIGSERIAL PRIMARY KEY, program_id BIGINT NOT NULL REFERENCES programs(id), starts_at TIMESTAMP NOT NULL,
 ends_at TIMESTAMP NOT NULL, room VARCHAR(100) NOT NULL, CHECK(ends_at>starts_at), UNIQUE(program_id,starts_at)
);
CREATE TABLE attendance (
 session_id BIGINT NOT NULL REFERENCES sessions(id), participant_id BIGINT NOT NULL REFERENCES participants(id),
 present BOOLEAN NOT NULL, feedback VARCHAR(1000) NOT NULL DEFAULT '', recorded_by BIGINT REFERENCES accounts(id), PRIMARY KEY(session_id,participant_id)
);
CREATE TABLE assessments (
 id BIGSERIAL PRIMARY KEY, participant_id BIGINT NOT NULL REFERENCES participants(id), program_id BIGINT NOT NULL REFERENCES programs(id),
 score INTEGER NOT NULL CHECK(score BETWEEN 0 AND 100), submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE certificates (
 id UUID PRIMARY KEY, enrollment_id BIGINT NOT NULL UNIQUE REFERENCES enrollments(id), issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 issued_by BIGINT REFERENCES accounts(id)
);
CREATE TABLE projects (
 id BIGSERIAL PRIMARY KEY, title VARCHAR(160) NOT NULL, description TEXT NOT NULL, beneficiary VARCHAR(160) NOT NULL,
 target INTEGER NOT NULL CHECK(target>0), completed INTEGER NOT NULL DEFAULT 0 CHECK(completed>=0 AND completed<=target),
 unit VARCHAR(40) NOT NULL, skills TEXT NOT NULL DEFAULT '', status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','COMPLETED'))
);
CREATE TABLE project_members (
 project_id BIGINT REFERENCES projects(id), participant_id BIGINT REFERENCES participants(id), PRIMARY KEY(project_id,participant_id)
);
CREATE TABLE feedback (
 id BIGSERIAL PRIMARY KEY, author_id BIGINT REFERENCES accounts(id), kind VARCHAR(20) NOT NULL CHECK(kind IN ('FEEDBACK','SUPPORT')),
 message VARCHAR(2000) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'OPEN', resolution VARCHAR(2000), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE notifications (
 id BIGSERIAL PRIMARY KEY, account_id BIGINT REFERENCES accounts(id), message VARCHAR(500) NOT NULL, seen BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE audit_events (
 id BIGSERIAL PRIMARY KEY, actor_id BIGINT REFERENCES accounts(id), action VARCHAR(120) NOT NULL, detail VARCHAR(500) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX enrollment_program_status ON enrollments(program_id,status);
CREATE INDEX session_start ON sessions(starts_at);
CREATE INDEX notifications_owner ON notifications(account_id,seen);
