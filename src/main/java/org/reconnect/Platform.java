package org.reconnect;

import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Platform {
  final JdbcTemplate db;

  public Platform(JdbcTemplate db) {
    this.db = db;
  }

  public JdbcTemplate db() {
    return db;
  }

  public record Actor(long id, String name, String role, String email) {
    public boolean staff() {
      return role.equals("ADMIN") || role.equals("OFFICER");
    }
  }

  public Actor actor(String email) {
    return db.queryForObject(
        "select * from accounts where email=? and active=true",
        (r, n) ->
            new Actor(
                r.getLong("id"), r.getString("name"), r.getString("role"), r.getString("email")),
        email);
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return db.queryForList(sql, args);
  }

  public Map<String, Object> one(String sql, Object... args) {
    var list = rows(sql, args);
    if (list.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found");
    return list.getFirst();
  }

  public static long num(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  public void require(boolean ok, String message) {
    if (!ok) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  public void role(Actor a, String... roles) {
    if (!Arrays.asList(roles).contains(a.role()))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "You do not have access to this action");
  }

  public long ownParticipant(Actor a) {
    return num(one("select id from participants where account_id=?", a.id()), "id");
  }

  public void participantAccess(Actor a, long id) {
    if (a.staff()) return;
    if (a.role().equals("PARTICIPANT") && ownParticipant(a) == id) return;
    if (a.role().equals("INSTRUCTOR")
        && db.queryForObject(
                "select count(*) from enrollments e join programs p on e.program_id=p.id where"
                    + " e.participant_id=? and p.instructor_id=? and e.status in"
                    + " ('ENROLLED','COMPLETED')",
                Long.class,
                id,
                a.id())
            > 0) return;
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This profile is not assigned to you");
  }

  public Map<String, Object> program(long id) {
    return one(
        "select p.*,a.name instructor from programs p left join accounts a on a.id=p.instructor_id"
            + " where p.id=?",
        id);
  }

  public void manageProgram(Actor a, long id) {
    if (a.staff()) return;
    role(a, "INSTRUCTOR");
    require(
        Objects.equals(program(id).get("instructor_id"), a.id()),
        "This program is assigned to another instructor");
  }

  public List<Map<String, Object>> programs(String query, String category) {
    return rows(
        "select p.*,a.name instructor,(select count(*) from enrollments e where e.program_id=p.id"
            + " and e.status='ENROLLED') occupied from programs p left join accounts a on"
            + " a.id=p.instructor_id where p.status='ACTIVE' and (lower(p.title||'"
            + " '||p.description||' '||p.skills) like ?) and (?='' or category=?) order by p.id",
        "%" + query.toLowerCase(Locale.ROOT) + "%",
        category,
        category);
  }

  public List<Map<String, Object>> participants(Actor a) {
    if (a.staff()) return rows("select * from participants order by name");
    if (a.role().equals("PARTICIPANT"))
      return rows("select * from participants where account_id=?", a.id());
    return rows(
        "select distinct t.* from participants t join enrollments e on e.participant_id=t.id join"
            + " programs p on p.id=e.program_id where p.instructor_id=? and e.status in"
            + " ('ENROLLED','COMPLETED') order by t.name",
        a.id());
  }

  public List<Map<String, Object>> enrollments(Actor a) {
    String where =
        a.staff()
            ? "true"
            : a.role().equals("INSTRUCTOR")
                ? "p.instructor_id=" + a.id()
                : "t.account_id=" + a.id();
    return rows(
        "select e.*,p.title,p.category,p.duration,t.name"
            + " participant,t.reference,t.account_id,(select count(*) from attendance at join"
            + " sessions s on at.session_id=s.id where s.program_id=e.program_id and"
            + " at.participant_id=e.participant_id and at.present) attended,coalesce((select"
            + " round(avg(coalesce((select max(q.score) from quiz_attempts q where q.lesson_id=l.id"
            + " and q.participant_id=e.participant_id),0))) from lessons l where"
            + " l.program_id=e.program_id and l.required and exists(select 1 from quiz_questions qq"
            + " where qq.lesson_id=l.id)),(select max(score) from assessments x where"
            + " x.program_id=e.program_id and x.participant_id=e.participant_id)) score,c.id"
            + " certificate_id from enrollments e join programs p on e.program_id=p.id join"
            + " participants t on e.participant_id=t.id left join certificates c on"
            + " c.enrollment_id=e.id where "
            + where
            + " order by e.created_at desc");
  }

  public List<Map<String, Object>> sessions(Actor a) {
    String where =
        a.staff()
            ? "true"
            : a.role().equals("INSTRUCTOR")
                ? "p.instructor_id=" + a.id()
                : "exists(select 1 from enrollments e join participants t on t.id=e.participant_id"
                    + " where e.program_id=p.id and e.status='ENROLLED' and t.account_id="
                    + a.id()
                    + ")";
    return rows(
        "select s.*,p.title,p.category,a.name instructor from sessions s join programs p on"
            + " p.id=s.program_id left join accounts a on a.id=p.instructor_id where "
            + where
            + " order by starts_at");
  }

  public void audit(Actor a, String action, String detail) {
    db.update(
        "insert into audit_events(actor_id,action,detail) values(?,?,?)", a.id(), action, detail);
  }

  public void notifyParticipant(long pid, String message) {
    db.update(
        "insert into notifications(account_id,message) select account_id,? from participants where"
            + " id=? and account_id is not null",
        message,
        pid);
  }

  public static String text(String value, int max) {
    String s = value == null ? "" : value.trim();
    if (s.length() > max)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Text exceeds maximum length of " + max);
    return s;
  }

  public static String required(String value, int max) {
    String s = text(value, max);
    if (s.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Please complete all required fields");
    return s;
  }

  @Transactional
  public void saveProfile(
      Actor a,
      long id,
      String name,
      String education,
      String skills,
      String interests,
      String goals,
      String language) {
    if (id == 0) {
      role(a, "ADMIN", "OFFICER");
      db.update(
          "insert into participants(name,reference,education,skills,interests,goals,language)"
              + " values(?,?,?,?,?,?,?)",
          required(name, 120),
          "RC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
          text(education, 120),
          text(skills, 2000),
          text(interests, 2000),
          text(goals, 2000),
          required(language, 60));
    } else {
      role(a, "ADMIN", "OFFICER", "PARTICIPANT");
      participantAccess(a, id);
      db.update(
          "update participants set name=?,education=?,skills=?,interests=?,goals=?,language=? where"
              + " id=?",
          required(name, 120),
          text(education, 120),
          text(skills, 2000),
          text(interests, 2000),
          text(goals, 2000),
          required(language, 60),
          id);
    }
    audit(a, "Profile updated", "Participant " + id);
  }

  @Transactional
  public void saveProgram(
      Actor a,
      String title,
      String category,
      String description,
      String skills,
      String prerequisites,
      long instructor,
      int capacity,
      int duration,
      String location,
      String lesson,
      String question,
      String oa,
      String ob,
      String oc,
      String answer) {
    role(a, "ADMIN", "OFFICER");
    require(
        Set.of("Vocational", "Education", "Creative", "Wellbeing", "Physical").contains(category),
        "Invalid category");
    require(
        capacity > 0 && capacity <= 100 && duration > 0 && duration <= 100,
        "Capacity and session count must be between 1 and 100");
    one("select id from accounts where id=? and role='INSTRUCTOR' and active=true", instructor);
    require(Set.of("A", "B", "C").contains(answer), "Invalid answer");
    db.update(
        "insert into"
            + " programs(title,category,description,skills,prerequisites,instructor_id,capacity,duration,location,lesson,question,option_a,option_b,option_c,answer)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        required(title, 160),
        category,
        required(description, 3000),
        required(skills, 2000),
        text(prerequisites, 1000),
        instructor,
        capacity,
        duration,
        required(location, 100),
        required(lesson, 8000),
        required(question, 1000),
        required(oa, 500),
        required(ob, 500),
        required(oc, 500),
        answer);
    audit(a, "Program created", title);
  }

  @Transactional
  public String enroll(Actor a, long pid, long programId) {
    db.queryForObject("select pg_advisory_xact_lock(846210)", Object.class);
    role(a, "ADMIN", "OFFICER", "PARTICIPANT");
    participantAccess(a, pid);
    var p = one("select * from programs where id=? for update", programId);
    require(p.get("status").equals("ACTIVE"), "This program is archived");
    var existing =
        rows("select * from enrollments where participant_id=? and program_id=?", pid, programId);
    require(
        existing.isEmpty() || existing.getFirst().get("status").equals("CANCELLED"),
        "An enrollment already exists");
    String status = "REQUESTED";
    if (a.staff()) {
      status = hasSeat(programId, num(p, "capacity")) ? "ENROLLED" : "WAITLISTED";
      if (status.equals("ENROLLED")) checkEnrollmentConflict(pid, programId);
    }
    db.update(
        "insert into enrollments(participant_id,program_id,status) values(?,?,?) on"
            + " conflict(participant_id,program_id) do update set"
            + " status=excluded.status,created_at=current_timestamp",
        pid,
        programId,
        status);
    notifyParticipant(pid, "Enrollment for " + p.get("title") + ": " + status.toLowerCase());
    audit(a, "Enrollment " + status, "Participant " + pid + ", program " + programId);
    return status;
  }

  private boolean hasSeat(long programId, long capacity) {
    return db.queryForObject(
            "select count(*) from enrollments where program_id=? and status='ENROLLED'",
            Long.class,
            programId)
        < capacity;
  }

  private void checkEnrollmentConflict(long pid, long programId) {
    require(
        db.queryForObject(
                "select count(*) from sessions a join sessions b on a.starts_at<b.ends_at and"
                    + " a.ends_at>b.starts_at join enrollments e on e.program_id=b.program_id where"
                    + " a.program_id=? and e.participant_id=? and e.status='ENROLLED' and"
                    + " b.program_id<>?",
                Long.class,
                programId,
                pid,
                programId)
            == 0,
        "Participant has a conflicting session; adjust the schedule first");
  }

  @Transactional
  public void enrollmentAction(Actor a, long id, String action) {
    db.queryForObject("select pg_advisory_xact_lock(846210)", Object.class);
    var initial = one("select * from enrollments where id=?", id);
    long programId = num(initial, "program_id"), pid = num(initial, "participant_id");
    var p = one("select * from programs where id=? for update", programId);
    var e = one("select * from enrollments where id=? for update", id);
    String status = (String) e.get("status");
    if (action.equals("cancel")) {
      role(a, "ADMIN", "OFFICER", "PARTICIPANT");
      participantAccess(a, pid);
      require(
          !Set.of("COMPLETED", "CANCELLED").contains(status),
          "This enrollment cannot be cancelled");
      db.update("update enrollments set status='CANCELLED' where id=?", id);
    } else if (action.equals("approve")) {
      role(a, "ADMIN", "OFFICER");
      require(
          Set.of("REQUESTED", "WAITLISTED").contains(status), "This request was already handled");
      require(p.get("status").equals("ACTIVE"), "Restore the program before approving enrollment");
      require(
          hasSeat(programId, num(p, "capacity")),
          "Program is full; keep this participant on the waiting list");
      checkEnrollmentConflict(pid, programId);
      db.update("update enrollments set status='ENROLLED' where id=?", id);
    } else if (action.equals("waitlist")) {
      role(a, "ADMIN", "OFFICER");
      require(status.equals("REQUESTED"), "Only pending requests can be waitlisted");
      db.update("update enrollments set status='WAITLISTED' where id=?", id);
    } else if (action.equals("complete")) {
      manageProgram(a, programId);
      require(status.equals("ENROLLED"), "Only active enrollments can be completed");
      long attended =
          db.queryForObject(
              "select count(*) from attendance at join sessions s on s.id=at.session_id where"
                  + " at.participant_id=? and s.program_id=? and at.present=true",
              Long.class,
              pid,
              programId);
      Integer score =
          db.queryForObject(
              "select coalesce(max(score),0) from assessments where participant_id=? and"
                  + " program_id=?",
              Integer.class,
              pid,
              programId);
      long curriculumQuizzes =
          db.queryForObject(
              "select count(*) from quiz_questions q join lessons l on l.id=q.lesson_id where"
                  + " l.program_id=?",
              Long.class,
              programId);
      require(
          attended >= num(p, "duration") && (curriculumQuizzes > 0 || score >= 70),
          "Completion requires all planned sessions attended and an assessment score of at least"
              + " 70%");
      long unread =
          db.queryForObject(
              "select count(*) from lessons l where l.program_id=? and l.required and not"
                  + " exists(select 1 from lesson_progress lp where lp.lesson_id=l.id and"
                  + " lp.participant_id=?)",
              Long.class,
              programId,
              pid);
      long failedQuizzes =
          db.queryForObject(
              "select count(*) from lessons l where l.program_id=? and l.required and exists(select"
                  + " 1 from quiz_questions q where q.lesson_id=l.id) and not exists(select 1 from"
                  + " quiz_attempts q where q.lesson_id=l.id and q.participant_id=? and"
                  + " q.score>=70)",
              Long.class,
              programId,
              pid);
      long pendingWork =
          db.queryForObject(
              "select count(*) from assignments a where a.program_id=? and a.required and not"
                  + " exists(select 1 from submissions s where s.assignment_id=a.id and"
                  + " s.participant_id=? and s.status='PASSED')",
              Long.class,
              programId,
              pid);
      require(
          unread == 0 && failedQuizzes == 0 && pendingWork == 0,
          "Complete all required lessons, pass their quizzes, and obtain instructor approval for"
              + " required assignments");
      db.update("update enrollments set status='COMPLETED' where id=?", id);
      db.update(
          "insert into certificates(id,enrollment_id,issued_by) values(?,?,?)",
          UUID.randomUUID(),
          id,
          a.id());
    } else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown action");
    notifyParticipant(pid, "Your enrollment in " + p.get("title") + " was updated: " + action);
    audit(a, "Enrollment " + action, "Enrollment " + id);
  }

  @Transactional
  public void schedule(
      Actor a, long programId, LocalDateTime start, LocalDateTime end, String room) {
    role(a, "ADMIN", "OFFICER", "INSTRUCTOR");
    manageProgram(a, programId);
    db.queryForObject("select pg_advisory_xact_lock(846210)", Object.class);
    var p = program(programId);
    require(p.get("status").equals("ACTIVE"), "Program is archived");
    require(
        end.isAfter(start) && Duration.between(start, end).toHours() <= 8,
        "Session duration must be positive and no longer than eight hours");
    room = required(room, 100);
    long conflicts =
        db.queryForObject(
            "select count(*) from sessions s join programs p on p.id=s.program_id where"
                + " s.starts_at<? and s.ends_at>? and (lower(s.room)=lower(?) or p.instructor_id=?"
                + " or p.id=? or exists(select 1 from enrollments e1 join enrollments e2 on"
                + " e1.participant_id=e2.participant_id where e1.program_id=? and"
                + " e2.program_id=s.program_id and e1.status='ENROLLED' and e2.status='ENROLLED'))",
            Long.class,
            end,
            start,
            room,
            p.get("instructor_id"),
            programId,
            programId);
    require(conflicts == 0, "Room, instructor, or participant has a conflicting session");
    db.update(
        "insert into sessions(program_id,starts_at,ends_at,room) values(?,?,?,?)",
        programId,
        start,
        end,
        room);
    audit(a, "Session scheduled", p.get("title") + " · " + start);
  }

  @Transactional
  public void attendance(Actor a, long sid, long pid, boolean present, String feedback) {
    var session = one("select * from sessions where id=?", sid);
    manageProgram(a, num(session, "program_id"));
    require(
        ((java.sql.Timestamp) session.get("starts_at"))
            .toLocalDateTime()
            .isBefore(LocalDateTime.now()),
        "Attendance can be recorded only after the session starts");
    one(
        "select id from enrollments where participant_id=? and program_id=? and status='ENROLLED'",
        pid,
        session.get("program_id"));
    db.update(
        "insert into attendance(session_id,participant_id,present,feedback,recorded_by)"
            + " values(?,?,?,?,?) on conflict(session_id,participant_id) do update set"
            + " present=excluded.present,feedback=excluded.feedback,recorded_by=excluded.recorded_by",
        sid,
        pid,
        present,
        text(feedback, 1000),
        a.id());
    audit(a, "Attendance recorded", "Session " + sid + ", participant " + pid);
  }

  @Transactional
  public int assess(Actor a, long programId, String answer) {
    role(a, "PARTICIPANT");
    long pid = ownParticipant(a);
    one(
        "select id from enrollments where participant_id=? and program_id=? and status='ENROLLED'",
        pid,
        programId);
    var p = program(programId);
    require(Set.of("A", "B", "C").contains(answer), "Choose an answer");
    int score = answer.equals(p.get("answer")) ? 100 : 0;
    db.update(
        "insert into assessments(participant_id,program_id,score) values(?,?,?)",
        pid,
        programId,
        score);
    audit(a, "Assessment submitted", "Program " + programId);
    return score;
  }

  @Transactional
  public void createProject(
      Actor a,
      String title,
      String description,
      String beneficiary,
      int target,
      String unit,
      String skills) {
    role(a, "ADMIN", "OFFICER");
    require(target > 0 && target <= 100000, "Target must be between 1 and 100000");
    db.update(
        "insert into projects(title,description,beneficiary,target,unit,skills)"
            + " values(?,?,?,?,?,?)",
        required(title, 160),
        required(description, 3000),
        required(beneficiary, 160),
        target,
        required(unit, 40),
        text(skills, 2000));
    audit(a, "Project created", title);
  }

  @Transactional
  public void projectProgress(Actor a, long id, int delta) {
    role(a, "ADMIN", "OFFICER");
    var p = one("select * from projects where id=? for update", id);
    require(
        delta > 0 && delta <= num(p, "target") - num(p, "completed"),
        "Progress must be positive and within the remaining target");
    db.update(
        "update projects set completed=completed+?, status=case when completed+?=target then"
            + " 'COMPLETED' else 'ACTIVE' end where id=?",
        delta,
        delta,
        id);
    audit(a, "Project progress", "Project " + id + ", added " + delta);
  }

  @Transactional
  public void joinProject(Actor a, long id) {
    role(a, "PARTICIPANT");
    one("select * from projects where id=? and status='ACTIVE'", id);
    db.update(
        "insert into project_members(project_id,participant_id) values(?,?) on conflict do nothing",
        id,
        ownParticipant(a));
    audit(a, "Project joined", "Project " + id);
  }

  @Transactional
  public void sendFeedback(Actor a, String kind, String message) {
    require(Set.of("FEEDBACK", "SUPPORT").contains(kind), "Invalid request type");
    db.update(
        "insert into feedback(author_id,kind,message) values(?,?,?)",
        a.id(),
        kind,
        required(message, 2000));
    audit(a, "Feedback submitted", kind);
  }

  @Transactional
  public void resolve(Actor a, long id, String resolution) {
    role(a, "ADMIN", "OFFICER");
    var f = one("select * from feedback where id=? for update", id);
    require(f.get("status").equals("OPEN"), "Already resolved");
    db.update(
        "update feedback set status='RESOLVED',resolution=? where id=?",
        required(resolution, 2000),
        id);
    db.update(
        "insert into notifications(account_id,message) values(?,?)",
        f.get("author_id"),
        "Your " + f.get("kind").toString().toLowerCase() + " request has a response.");
    audit(a, "Feedback resolved", "Request " + id);
  }
}
