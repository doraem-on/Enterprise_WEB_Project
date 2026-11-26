package org.reconnect;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningService {
  private final Platform p;

  public LearningService(Platform p) {
    this.p = p;
  }

  public void active(Platform.Actor a, long program) {
    p.role(a, "PARTICIPANT");
    p.one(
        "select id from enrollments where participant_id=? and program_id=? and status='ENROLLED'",
        p.ownParticipant(a),
        program);
  }

  public List<Map<String, Object>> lessons(Platform.Actor a, long program) {
    p.program(program);
    long pid = a.role().equals("PARTICIPANT") ? p.ownParticipant(a) : 0;
    return p.rows(
        "select l.*,lp.completed_at,(select max(score) from quiz_attempts q where q.lesson_id=l.id"
            + " and q.participant_id=?) score,(select count(*) from quiz_questions q where"
            + " q.lesson_id=l.id) questions from lessons l left join lesson_progress lp on"
            + " lp.lesson_id=l.id and lp.participant_id=? where program_id=? order by position",
        pid,
        pid,
        program);
  }

  public Map<String, Object> lesson(Platform.Actor a, long id) {
    var l =
        p.one(
            "select l.*,pr.title program_title from lessons l join programs pr on"
                + " pr.id=l.program_id where l.id=?",
            id);
    long program = Platform.num(l, "program_id");
    if (a.role().equals("PARTICIPANT"))
      p.one(
          "select id from enrollments where participant_id=? and program_id=? and status in"
              + " ('ENROLLED','COMPLETED')",
          p.ownParticipant(a),
          program);
    else p.manageProgram(a, program);
    return l;
  }

  @Transactional
  public long addLesson(
      Platform.Actor a, long program, String title, String body, boolean required) {
    p.manageProgram(a, program);
    p.one("select id from programs where id=? for update", program);
    long id =
        p.db()
            .queryForObject(
                "insert into lessons(program_id,title,body,position,required) select"
                    + " ?,?,?,coalesce(max(position),0)+1,? from lessons where program_id=?"
                    + " returning id",
                Long.class,
                program,
                Platform.required(title, 180),
                Platform.required(body, 12000),
                required,
                program);
    p.audit(a, "Lesson created", "Lesson " + id);
    return id;
  }

  @Transactional
  public void addQuestion(
      Platform.Actor a,
      long lesson,
      String question,
      String oa,
      String ob,
      String oc,
      String answer) {
    var l = p.one("select * from lessons where id=? for update", lesson);
    p.manageProgram(a, Platform.num(l, "program_id"));
    p.require(Set.of("A", "B", "C").contains(answer), "Choose a valid correct answer");
    p.require(
        p.db()
                .queryForObject(
                    "select count(*) from quiz_attempts where lesson_id=?", Long.class, lesson)
            == 0,
        "This quiz already has attempts. Add a new lesson to preserve assessment history.");
    p.db()
        .update(
            "insert into quiz_questions(lesson_id,question,option_a,option_b,option_c,answer)"
                + " values(?,?,?,?,?,?)",
            lesson,
            Platform.required(question, 1000),
            Platform.required(oa, 500),
            Platform.required(ob, 500),
            Platform.required(oc, 500),
            answer);
    p.audit(a, "Quiz question added", "Lesson " + lesson);
  }

  @Transactional
  public void read(Platform.Actor a, long id) {
    var l = lesson(a, id);
    active(a, Platform.num(l, "program_id"));
    p.db()
        .update(
            "insert into lesson_progress(lesson_id,participant_id) values(?,?) on conflict do"
                + " nothing",
            id,
            p.ownParticipant(a));
  }

  @Transactional
  public int quiz(Platform.Actor a, long id, Map<String, String> answers) {
    var l = lesson(a, id);
    active(a, Platform.num(l, "program_id"));
    p.one("select id from lessons where id=? for update", id);
    var questions =
        p.rows("select id,answer from quiz_questions where lesson_id=? order by id", id);
    p.require(!questions.isEmpty(), "This lesson has no quiz yet");
    int correct = 0;
    for (var q : questions) {
      String answer = answers.get("q_" + q.get("id"));
      p.require(answer != null && Set.of("A", "B", "C").contains(answer), "Answer every question");
      if (answer.equals(q.get("answer"))) correct++;
    }
    int score = (int) Math.round(correct * 100.0 / questions.size());
    p.db()
        .update(
            "insert into quiz_attempts(lesson_id,participant_id,score) values(?,?,?)",
            id,
            p.ownParticipant(a),
            score);
    p.audit(a, "Lesson quiz submitted", "Lesson " + id + ", score " + score);
    return score;
  }

  @Transactional
  public void assignment(
      Platform.Actor a,
      long program,
      String title,
      String kind,
      String instructions,
      String rubric,
      boolean required) {
    p.manageProgram(a, program);
    p.one("select id from programs where id=? for update", program);
    p.require(
        Set.of("WRITTEN", "PRACTICAL").contains(kind), "Choose written or practical assessment");
    p.db()
        .update(
            "insert into assignments(program_id,title,kind,instructions,rubric,required)"
                + " values(?,?,?,?,?,?)",
            program,
            Platform.required(title, 180),
            kind,
            Platform.required(instructions, 8000),
            Platform.required(rubric, 3000),
            required);
    p.audit(a, "Assignment created", title);
  }

  @Transactional
  public void submit(Platform.Actor a, long assignment, String response) {
    var x = p.one("select * from assignments where id=?", assignment);
    active(a, Platform.num(x, "program_id"));
    p.one(
        "select id from enrollments where program_id=? and participant_id=? for update",
        x.get("program_id"),
        p.ownParticipant(a));
    var old =
        p.rows(
            "select * from submissions where assignment_id=? and participant_id=?",
            assignment,
            p.ownParticipant(a));
    p.require(
        old.isEmpty() || old.getFirst().get("status").equals("REVISION"),
        "Submitted or passed work cannot be overwritten. Wait for instructor feedback.");
    p.db()
        .update(
            "insert into submissions(assignment_id,participant_id,response) values(?,?,?) on"
                + " conflict(assignment_id,participant_id) do update set"
                + " response=excluded.response,status='SUBMITTED',score=null,feedback=null,evidence=null,reviewed_by=null,reviewed_at=null,submitted_at=current_timestamp",
            assignment,
            p.ownParticipant(a),
            Platform.required(response, 12000));
    p.audit(a, "Work submitted", "Assignment " + assignment);
  }

  @Transactional
  public void review(
      Platform.Actor a, long submission, int score, String feedback, String evidence) {
    var s =
        p.one(
            "select s.*,x.program_id,x.kind from submissions s join assignments x on"
                + " x.id=s.assignment_id where s.id=? for update of s",
            submission);
    p.manageProgram(a, Platform.num(s, "program_id"));
    p.require(s.get("status").equals("SUBMITTED"), "This submission has already been reviewed");
    p.require(score >= 0 && score <= 100, "Score must be between 0 and 100");
    feedback = Platform.required(feedback, 3000);
    evidence = Platform.required(evidence, 3000);
    p.db()
        .update(
            "update submissions set"
                + " score=?,feedback=?,evidence=?,reviewed_by=?,reviewed_at=current_timestamp,status=?"
                + " where id=?",
            score,
            feedback,
            evidence,
            a.id(),
            score >= 70 ? "PASSED" : "REVISION",
            submission);
    p.db()
        .update(
            "insert into"
                + " submission_reviews(submission_id,reviewer_id,response,score,feedback,evidence)"
                + " values(?,?,?,?,?,?)",
            submission,
            a.id(),
            s.get("response"),
            score,
            feedback,
            evidence);
    p.notifyParticipant(
        Platform.num(s, "participant_id"), "Your assignment has instructor feedback.");
    p.audit(a, "Assignment reviewed", "Submission " + submission);
  }

  public List<Map<String, Object>> assignments(Platform.Actor a, long program) {
    long pid = a.role().equals("PARTICIPANT") ? p.ownParticipant(a) : 0;
    return p.rows(
        "select x.*,s.id submission_id,s.status,s.score,s.feedback,s.evidence,s.response from"
            + " assignments x left join submissions s on s.assignment_id=x.id and"
            + " s.participant_id=? where x.program_id=? order by x.id",
        pid,
        program);
  }

  public List<Map<String, Object>> reviews(Platform.Actor a, long program) {
    p.manageProgram(a, program);
    return p.rows(
        "select s.*,x.title,x.kind,x.rubric,t.name participant from submissions s join assignments"
            + " x on x.id=s.assignment_id join participants t on t.id=s.participant_id where"
            + " x.program_id=? order by s.submitted_at desc",
        program);
  }
}
