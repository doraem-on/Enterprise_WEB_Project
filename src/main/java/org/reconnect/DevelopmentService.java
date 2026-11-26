package org.reconnect;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DevelopmentService {
  private final Platform p;

  public DevelopmentService(Platform p) {
    this.p = p;
  }

  public void access(Platform.Actor a, long pid) {
    p.role(a, "ADMIN", "OFFICER", "PARTICIPANT");
    p.participantAccess(a, pid);
  }

  @Transactional
  public long create(
      Platform.Actor a, long pid, String title, String description, LocalDate review) {
    access(a, pid);
    p.require(!review.isBefore(LocalDate.now()), "Review date must be today or later");
    long id =
        p.db()
            .queryForObject(
                "insert into goals(participant_id,title,description,review_date,created_by)"
                    + " values(?,?,?,?,?) returning id",
                Long.class,
                pid,
                Platform.required(title, 180),
                Platform.required(description, 3000),
                review,
                a.id());
    event(a, id, "Goal proposed");
    p.notifyParticipant(pid, "A development goal is ready for your agreement: " + title);
    return id;
  }

  private void event(Platform.Actor a, long goal, String note) {
    p.db()
        .update("insert into goal_events(goal_id,actor_id,note) values(?,?,?)", goal, a.id(), note);
    p.audit(a, "Development plan updated", "Goal " + goal);
  }

  @Transactional
  public void action(Platform.Actor a, long id, String action, String note) {
    var g = p.one("select * from goals where id=? for update", id);
    long pid = Platform.num(g, "participant_id");
    access(a, pid);
    String state = (String) g.get("status");
    if (action.equals("agree")) {
      p.require(state.equals("PROPOSED"), "Only proposed goals can be agreed");
      if (a.staff()) Platform.required(note, 2000);
      p.db().update("update goals set status='AGREED',agreed_at=current_timestamp where id=?", id);
      event(
          a,
          id,
          a.staff()
              ? "Participant agreement recorded by staff: " + note
              : "Participant agreed to the goal");
    } else if (action.equals("decline")) {
      p.require(state.equals("PROPOSED"), "Only a proposed goal can be declined");
      p.db().update("update goals set status='DECLINED' where id=?", id);
      event(a, id, "Goal declined: " + Platform.required(note, 2000));
    } else if (action.equals("complete")) {
      p.role(a, "ADMIN", "OFFICER");
      p.require(state.equals("AGREED"), "Agree on the goal before completing it");
      long total =
          p.db().queryForObject("select count(*) from milestones where goal_id=?", Long.class, id);
      long remaining =
          p.db()
              .queryForObject(
                  "select count(*) from milestones where goal_id=? and completed_at is null",
                  Long.class,
                  id);
      p.require(total > 0 && remaining == 0, "Complete every milestone before the goal review");
      p.db().update("update goals set status='COMPLETED' where id=?", id);
      event(a, id, "Goal completed after review: " + Platform.required(note, 2000));
    } else if (action.equals("note")) {
      event(a, id, Platform.required(note, 2000));
    } else p.require(false, "Unknown plan action");
  }

  @Transactional
  public void milestone(Platform.Actor a, long goal, String title, LocalDate due) {
    var g = p.one("select * from goals where id=? for update", goal);
    access(a, Platform.num(g, "participant_id"));
    p.require(Set.of("PROPOSED", "AGREED").contains(g.get("status")), "This plan is closed");
    p.require(
        !due.isBefore(LocalDate.now())
            && !due.isAfter(((java.sql.Date) g.get("review_date")).toLocalDate()),
        "Milestone date must be before the goal review and not in the past");
    p.db()
        .update(
            "insert into milestones(goal_id,title,due_date) values(?,?,?)",
            goal,
            Platform.required(title, 180),
            due);
    event(a, goal, "Milestone added: " + title);
  }

  @Transactional
  public void finishMilestone(Platform.Actor a, long id) {
    var m =
        p.one(
            "select m.*,g.participant_id,g.status from milestones m join goals g on g.id=m.goal_id"
                + " where m.id=? for update of m,g",
            id);
    access(a, Platform.num(m, "participant_id"));
    p.require(m.get("status").equals("AGREED"), "Agree on the goal first");
    p.require(m.get("completed_at") == null, "Milestone already recorded");
    p.db()
        .update(
            "update milestones set completed_at=current_timestamp,completed_by=? where id=?",
            a.id(),
            id);
    event(a, Platform.num(m, "goal_id"), "Milestone completed: " + m.get("title"));
  }
}
