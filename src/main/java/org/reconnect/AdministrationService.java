package org.reconnect;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdministrationService {
  private final Platform p;
  private final ResourceService resources;

  public AdministrationService(Platform p, ResourceService resources) {
    this.p = p;
    this.resources = resources;
  }

  @Transactional
  public void program(
      Platform.Actor a,
      long id,
      String title,
      String description,
      String skills,
      String prerequisites,
      long instructor,
      int capacity,
      int duration,
      String location) {
    p.role(a, "ADMIN", "OFFICER");
    p.db().queryForObject("select pg_advisory_xact_lock(846210)", Object.class);
    p.one("select id from programs where id=? for update", id);
    p.one("select id from accounts where id=? and role='INSTRUCTOR' and active", instructor);
    p.require(
        capacity >= 1 && capacity <= 100 && duration >= 1 && duration <= 100,
        "Seats and sessions must be between 1 and 100");
    p.require(
        p.db()
                .queryForObject(
                    "select count(*) from enrollments where program_id=? and status='ENROLLED'",
                    Long.class,
                    id)
            <= capacity,
        "Capacity cannot fall below active enrollment");
    p.require(
        p.db()
                .queryForObject(
                    "select count(*) from sessions s join sessions other on other.id<>s.id and"
                        + " other.starts_at<s.ends_at and other.ends_at>s.starts_at join programs"
                        + " op on op.id=other.program_id where s.program_id=? and"
                        + " s.ends_at>current_timestamp and op.instructor_id=?",
                    Long.class,
                    id,
                    instructor)
            == 0,
        "New instructor has conflicting scheduled sessions");
    p.db()
        .update(
            "update programs set"
                + " title=?,description=?,skills=?,prerequisites=?,instructor_id=?,capacity=?,duration=?,location=?"
                + " where id=?",
            Platform.required(title, 160),
            Platform.required(description, 3000),
            Platform.required(skills, 2000),
            Platform.text(prerequisites, 1000),
            instructor,
            capacity,
            duration,
            Platform.required(location, 100),
            id);
    p.audit(a, "Program edited", "Program " + id);
  }

  @Transactional
  public void archive(Platform.Actor a, long id, boolean archive) {
    p.role(a, "ADMIN", "OFFICER");
    p.db().queryForObject("select pg_advisory_xact_lock(846210)", Object.class);
    p.one("select id from programs where id=? for update", id);
    if (!archive)
      p.require(
          p.db()
                  .queryForObject(
                      "select count(*) from lessons l where l.program_id=? and l.required and"
                          + " exists(select 1 from quiz_questions q where q.lesson_id=l.id)",
                      Long.class,
                      id)
              > 0,
          "Prepare a required lesson and quiz before restoring this program");
    p.db().update("update programs set status=? where id=?", archive ? "ARCHIVED" : "ACTIVE", id);
    p.audit(a, archive ? "Program archived" : "Program restored", "Program " + id);
  }

  @Transactional
  public void reschedule(
      Platform.Actor a, long id, LocalDateTime start, LocalDateTime end, String room) {
    p.role(a, "ADMIN", "OFFICER", "INSTRUCTOR");
    p.db().queryForObject("select pg_advisory_xact_lock(846210)", Object.class);
    var s =
        p.one(
            "select s.*,p.instructor_id from sessions s join programs p on p.id=s.program_id where"
                + " s.id=? for update of s",
            id);
    long program = Platform.num(s, "program_id");
    p.manageProgram(a, program);
    p.require(
        p.db().queryForObject("select count(*) from attendance where session_id=?", Long.class, id)
            == 0,
        "Attendance is recorded; preserve this session's history");
    p.require(
        start.isAfter(LocalDateTime.now())
            && end.isAfter(start)
            && Duration.between(start, end).toMinutes() <= 480,
        "Choose a future session lasting at most eight hours");
    room = Platform.required(room, 100);
    long conflicts =
        p.db()
            .queryForObject(
                "select count(*) from sessions s join programs p on p.id=s.program_id where s.id<>?"
                    + " and s.starts_at<? and s.ends_at>? and (lower(s.room)=lower(?) or"
                    + " p.instructor_id=? or p.id=? or exists(select 1 from enrollments e1 join"
                    + " enrollments e2 on e1.participant_id=e2.participant_id where e1.program_id=?"
                    + " and e2.program_id=s.program_id and e1.status='ENROLLED' and"
                    + " e2.status='ENROLLED'))",
                Long.class,
                id,
                end,
                start,
                room,
                s.get("instructor_id"),
                program,
                program);
    p.require(conflicts == 0, "Room, instructor or participant has a conflict");
    for (var r :
        p.rows(
            "select * from equipment_reservations where session_id=? and returned_at is null order"
                + " by resource_id",
            id))
      resources.capacity(
          Platform.num(r, "resource_id"), id, (int) Platform.num(r, "quantity"), start, end);
    p.db()
        .update(
            "update sessions set starts_at=?,ends_at=?,room=? where id=?", start, end, room, id);
    for (var e :
        p.rows(
            "select participant_id from enrollments where program_id=? and status='ENROLLED'",
            program))
      p.notifyParticipant(
          Platform.num(e, "participant_id"),
          "A session has been rescheduled. Please check your activity calendar.");
    p.audit(a, "Session rescheduled", "Session " + id);
  }

  @Transactional
  public void account(Platform.Actor a, long id, String name, String email, boolean active) {
    p.role(a, "ADMIN");
    p.db().queryForObject("select pg_advisory_xact_lock(846211)", Object.class);
    var target = p.one("select * from accounts where id=? for update", id);
    p.require(id != a.id() || active, "You cannot deactivate your own session");
    if (!active && target.get("role").equals("ADMIN"))
      p.require(
          p.db()
                  .queryForObject(
                      "select count(*) from accounts where role='ADMIN' and active and id<>?",
                      Long.class,
                      id)
              > 0,
          "Keep at least one active administrator");
    email = Platform.required(email, 180).toLowerCase(Locale.ROOT);
    p.require(email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"), "Enter a valid email address");
    p.db()
        .update(
            "update accounts set name=?,email=?,active=? where id=?",
            Platform.required(name, 120),
            email,
            active,
            id);
    p.audit(a, "Account access updated", "Account " + id + ", active=" + active);
  }

  @Transactional
  public void promote(Platform.Actor a, long program) {
    p.role(a, "ADMIN", "OFFICER");
    p.db().queryForObject("select pg_advisory_xact_lock(846210)", Object.class);
    var pr = p.one("select * from programs where id=? for update", program);
    p.require(
        pr.get("status").equals("ACTIVE"),
        "Restore this program before promoting the waiting list");
    var next =
        p.rows(
            "select id from enrollments where program_id=? and status='WAITLISTED' order by"
                + " created_at,id limit 1",
            program);
    p.require(!next.isEmpty(), "There are no waitlisted participants");
    p.enrollmentAction(a, Platform.num(next.getFirst(), "id"), "approve");
  }
}
