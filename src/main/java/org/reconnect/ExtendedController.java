package org.reconnect;

import java.security.Principal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ExtendedController {
  private final Platform p;
  private final LearningService learning;
  private final DevelopmentService plans;
  private final AdministrationService admin;
  private final ResourceService resources;
  private final PartnershipService partners;
  private final EvaluationService evaluation;
  private final RecommendationEngine ml;

  public ExtendedController(
      Platform p,
      LearningService learning,
      DevelopmentService plans,
      AdministrationService admin,
      ResourceService resources,
      PartnershipService partners,
      EvaluationService evaluation,
      RecommendationEngine ml) {
    this.p = p;
    this.learning = learning;
    this.plans = plans;
    this.admin = admin;
    this.resources = resources;
    this.partners = partners;
    this.evaluation = evaluation;
    this.ml = ml;
  }

  private Platform.Actor who(Principal x) {
    return p.actor(x.getName());
  }

  private String page(Model m, String page, String title, String subtitle) {
    m.addAttribute("page", page);
    m.addAttribute("title", title);
    m.addAttribute("subtitle", subtitle);
    return "app";
  }

  private String done(RedirectAttributes r, String message, String url) {
    r.addFlashAttribute("success", message);
    return "redirect:" + url;
  }

  @GetMapping("/learning")
  String learning(Principal x, Model m) {
    var a = who(x);
    p.role(a, "ADMIN", "OFFICER", "INSTRUCTOR", "PARTICIPANT");
    List<Map<String, Object>> courses;
    if (a.staff()) courses = p.rows("select * from programs order by id");
    else if (a.role().equals("INSTRUCTOR"))
      courses = p.rows("select * from programs where instructor_id=? order by id", a.id());
    else
      courses =
          p.rows(
              "select pr.* from programs pr join enrollments e on e.program_id=pr.id where"
                  + " e.participant_id=? and e.status in ('ENROLLED','COMPLETED') order by pr.id",
              p.ownParticipant(a));
    m.addAttribute("courses", courses);
    return page(
        m,
        "x-learning",
        "Learning centre",
        "Lessons, knowledge checks, and practical skills for your next chapter.");
  }

  @GetMapping("/learning/{id}")
  String course(@PathVariable long id, Principal x, Model m) {
    var a = who(x);
    if (a.role().equals("PARTICIPANT"))
      p.one(
          "select id from enrollments where participant_id=? and program_id=? and status in"
              + " ('ENROLLED','COMPLETED')",
          p.ownParticipant(a),
          id);
    else p.manageProgram(a, id);
    m.addAttribute("course", p.program(id));
    m.addAttribute("lessons", learning.lessons(a, id));
    m.addAttribute("assignments", learning.assignments(a, id));
    m.addAttribute("reviews", a.role().equals("PARTICIPANT") ? List.of() : learning.reviews(a, id));
    m.addAttribute(
        "canSubmit",
        a.role().equals("PARTICIPANT")
            && p.db()
                    .queryForObject(
                        "select count(*) from enrollments where participant_id=? and program_id=?"
                            + " and status='ENROLLED'",
                        Long.class,
                        p.ownParticipant(a),
                        id)
                > 0);
    return page(m, "x-course", "Course workspace", p.program(id).get("title").toString());
  }

  @PostMapping("/learning/{id}/lessons")
  String addLesson(
      @PathVariable long id,
      Principal x,
      @RequestParam String title,
      @RequestParam String body,
      @RequestParam(defaultValue = "false") boolean required,
      RedirectAttributes r) {
    long lesson = learning.addLesson(who(x), id, title, body, required);
    return done(r, "Lesson added. You can now add quiz questions.", "/lessons/" + lesson);
  }

  @GetMapping("/lessons/{id}")
  String lesson(@PathVariable long id, Principal x, Model m) {
    var a = who(x);
    var l = learning.lesson(a, id);
    m.addAttribute("lesson", l);
    m.addAttribute(
        "questions",
        p.rows(
            "select id,question,option_a,option_b,option_c from quiz_questions where lesson_id=?"
                + " order by id",
            id));
    long pid = a.role().equals("PARTICIPANT") ? p.ownParticipant(a) : 0;
    m.addAttribute(
        "attempts",
        p.rows(
            "select score,submitted_at from quiz_attempts where lesson_id=? and participant_id=?"
                + " order by id desc limit 10",
            id,
            pid));
    m.addAttribute(
        "read",
        p.db()
                .queryForObject(
                    "select count(*) from lesson_progress where lesson_id=? and participant_id=?",
                    Long.class,
                    id,
                    pid)
            > 0);
    m.addAttribute(
        "canSubmit",
        pid > 0
            && p.db()
                    .queryForObject(
                        "select count(*) from enrollments where program_id=? and participant_id=?"
                            + " and status='ENROLLED'",
                        Long.class,
                        l.get("program_id"),
                        pid)
                > 0);
    return page(m, "x-lesson", l.get("title").toString(), l.get("program_title").toString());
  }

  @PostMapping("/lessons/{id}/read")
  String read(@PathVariable long id, Principal x, RedirectAttributes r) {
    learning.read(who(x), id);
    return done(r, "Lesson marked complete.", "/lessons/" + id);
  }

  @PostMapping("/lessons/{id}/quiz")
  String quiz(
      @PathVariable long id,
      Principal x,
      @RequestParam Map<String, String> answers,
      RedirectAttributes r) {
    int score = learning.quiz(who(x), id, answers);
    return done(
        r,
        "Quiz score: "
            + score
            + "%. "
            + (score >= 70 ? "Passed." : "Review the lesson and try again."),
        "/lessons/" + id);
  }

  @PostMapping("/lessons/{id}/questions")
  String question(
      @PathVariable long id,
      Principal x,
      @RequestParam String question,
      @RequestParam String optionA,
      @RequestParam String optionB,
      @RequestParam String optionC,
      @RequestParam String answer,
      RedirectAttributes r) {
    learning.addQuestion(who(x), id, question, optionA, optionB, optionC, answer);
    return done(r, "Question added.", "/lessons/" + id);
  }

  @PostMapping("/learning/{id}/assignments")
  String assignment(
      @PathVariable long id,
      Principal x,
      @RequestParam String title,
      @RequestParam String kind,
      @RequestParam String instructions,
      @RequestParam String rubric,
      @RequestParam(defaultValue = "false") boolean required,
      RedirectAttributes r) {
    learning.assignment(who(x), id, title, kind, instructions, rubric, required);
    return done(r, "Assessment added.", "/learning/" + id);
  }

  @PostMapping("/assignments/{id}/submit")
  String submit(
      @PathVariable long id, Principal x, @RequestParam String response, RedirectAttributes r) {
    learning.submit(who(x), id, response);
    long program =
        Platform.num(p.one("select program_id from assignments where id=?", id), "program_id");
    return done(r, "Work submitted for instructor review.", "/learning/" + program);
  }

  @PostMapping("/submissions/{id}/review")
  String review(
      @PathVariable long id,
      Principal x,
      @RequestParam int score,
      @RequestParam String feedback,
      @RequestParam String evidence,
      RedirectAttributes r) {
    learning.review(who(x), id, score, feedback, evidence);
    long program =
        Platform.num(
            p.one(
                "select a.program_id from submissions s join assignments a on a.id=s.assignment_id"
                    + " where s.id=?",
                id),
            "program_id");
    return done(r, "Assessment review saved.", "/learning/" + program);
  }

  @GetMapping("/plans")
  String plans(Principal x, Model m, @RequestParam(required = false) Long participantId) {
    var a = who(x);
    p.role(a, "ADMIN", "OFFICER", "PARTICIPANT");
    var people = p.participants(a);
    m.addAttribute("people", people);
    if (!people.isEmpty()) {
      long pid = participantId == null ? Platform.num(people.getFirst(), "id") : participantId;
      plans.access(a, pid);
      m.addAttribute("person", p.one("select * from participants where id=?", pid));
      var goals =
          p.rows(
              "select g.*,(select count(*) from milestones m where m.goal_id=g.id) total,(select"
                  + " count(*) from milestones m where m.goal_id=g.id and m.completed_at is not"
                  + " null) done from goals g where participant_id=? order by created_at desc",
              pid);
      for (var g : goals) {
        g.put(
            "milestones",
            p.rows("select * from milestones where goal_id=? order by due_date,id", g.get("id")));
        g.put(
            "events",
            p.rows(
                "select e.*,a.name actor from goal_events e join accounts a on a.id=e.actor_id"
                    + " where e.goal_id=? order by e.id desc",
                g.get("id")));
      }
      m.addAttribute("goals", goals);
    }
    return page(
        m,
        "x-plans",
        "Personal development plan",
        "Agree on goals, take manageable steps, and review progress together.");
  }

  @PostMapping("/plans")
  String createGoal(
      Principal x,
      @RequestParam long participantId,
      @RequestParam String title,
      @RequestParam String description,
      @RequestParam LocalDate reviewDate,
      RedirectAttributes r) {
    plans.create(who(x), participantId, title, description, reviewDate);
    return done(r, "Goal proposed for agreement.", "/plans?participantId=" + participantId);
  }

  @PostMapping("/plans/{id}/{action}")
  String planAction(
      Principal x,
      @PathVariable long id,
      @PathVariable String action,
      @RequestParam(defaultValue = "") String note,
      RedirectAttributes r) {
    plans.action(who(x), id, action, note);
    return done(
        r,
        "Development plan updated.",
        "/plans?participantId="
            + p.one("select participant_id from goals where id=?", id).get("participant_id"));
  }

  @PostMapping("/plans/{id}/milestones/add")
  String milestone(
      Principal x,
      @PathVariable long id,
      @RequestParam String title,
      @RequestParam LocalDate dueDate,
      RedirectAttributes r) {
    plans.milestone(who(x), id, title, dueDate);
    return done(
        r,
        "Milestone added.",
        "/plans?participantId="
            + p.one("select participant_id from goals where id=?", id).get("participant_id"));
  }

  @PostMapping("/milestones/{id}/complete")
  String milestoneDone(Principal x, @PathVariable long id, RedirectAttributes r) {
    plans.finishMilestone(who(x), id);
    return done(
        r,
        "Milestone recorded.",
        "/plans?participantId="
            + p.one(
                    "select g.participant_id from milestones m join goals g on g.id=m.goal_id where"
                        + " m.id=?",
                    id)
                .get("participant_id"));
  }

  @GetMapping("/manage/programs")
  String managePrograms(Principal x, Model m) {
    p.role(who(x), "ADMIN", "OFFICER");
    m.addAttribute(
        "courses",
        p.rows(
            "select p.*,(select count(*) from enrollments e where e.program_id=p.id and"
                + " e.status='WAITLISTED') waitlisted from programs p order by id"));
    return page(
        m,
        "x-manage-programs",
        "Program administration",
        "Edit offerings, manage waiting lists, and preserve archived records.");
  }

  @GetMapping("/manage/programs/{id}")
  String editProgram(Principal x, @PathVariable long id, Model m) {
    p.role(who(x), "ADMIN", "OFFICER");
    m.addAttribute("course", p.program(id));
    m.addAttribute(
        "instructors", p.rows("select id,name from accounts where role='INSTRUCTOR' and active"));
    return page(
        m, "x-edit-program", "Edit program", "Changes preserve enrollment and learning history.");
  }

  @PostMapping("/manage/programs/{id}")
  String saveProgram(
      Principal x,
      @PathVariable long id,
      @RequestParam String title,
      @RequestParam String description,
      @RequestParam String skills,
      @RequestParam String prerequisites,
      @RequestParam long instructor,
      @RequestParam int capacity,
      @RequestParam int duration,
      @RequestParam String location,
      RedirectAttributes r) {
    admin.program(
        who(x),
        id,
        title,
        description,
        skills,
        prerequisites,
        instructor,
        capacity,
        duration,
        location);
    return done(r, "Program updated.", "/manage/programs");
  }

  @PostMapping("/manage/programs/{id}/archive")
  String archive(
      Principal x, @PathVariable long id, @RequestParam boolean archive, RedirectAttributes r) {
    admin.archive(who(x), id, archive);
    return done(
        r,
        archive ? "Program archived. Existing records remain accessible." : "Program restored.",
        "/manage/programs");
  }

  @PostMapping("/manage/programs/{id}/promote")
  String promote(Principal x, @PathVariable long id, RedirectAttributes r) {
    admin.promote(who(x), id);
    return done(r, "The earliest waitlisted participant was enrolled.", "/manage/programs");
  }

  @GetMapping("/sessions/{id}/edit")
  String editSession(Principal x, @PathVariable long id, Model m) {
    var s =
        p.one(
            "select s.*,p.title from sessions s join programs p on p.id=s.program_id where s.id=?",
            id);
    p.manageProgram(who(x), Platform.num(s, "program_id"));
    m.addAttribute("session", s);
    return page(
        m,
        "x-session",
        "Reschedule session",
        "Room, instructor, participant and equipment bookings are checked together.");
  }

  @PostMapping("/sessions/{id}/edit")
  String reschedule(
      Principal x,
      @PathVariable long id,
      @RequestParam LocalDateTime start,
      @RequestParam LocalDateTime end,
      @RequestParam String room,
      RedirectAttributes r) {
    admin.reschedule(who(x), id, start, end, room);
    return done(r, "Session rescheduled and participants notified.", "/schedule");
  }

  @PostMapping("/admin/accounts/{id}")
  String account(
      Principal x,
      @PathVariable long id,
      @RequestParam String name,
      @RequestParam String email,
      @RequestParam(defaultValue = "false") boolean active,
      RedirectAttributes r) {
    admin.account(who(x), id, name, email, active);
    return done(r, "Account updated. Disabled sessions lose access immediately.", "/admin");
  }

  @GetMapping("/partners")
  String partners(Principal x, Model m) {
    var a = who(x);
    p.role(a, "ADMIN", "OFFICER", "NGO");
    m.addAttribute(
        "organizations",
        a.staff()
            ? p.rows("select * from organizations order by id")
            : p.rows("select * from organizations where account_id=?", a.id()));
    m.addAttribute(
        "proposals",
        a.staff()
            ? p.rows(
                "select pr.*,o.name organization from proposals pr join organizations o on"
                    + " o.id=pr.organization_id order by pr.id desc")
            : p.rows(
                "select pr.*,o.name organization from proposals pr join organizations o on"
                    + " o.id=pr.organization_id where o.account_id=? order by pr.id desc",
                a.id()));
    m.addAttribute(
        "instructors",
        a.staff()
            ? p.rows("select id,name from accounts where role='INSTRUCTOR' and active")
            : List.of());
    return page(
        m,
        "x-partners",
        "Community partnerships",
        "Approved organizations and staff build purposeful opportunities together.");
  }

  @PostMapping("/partners/apply")
  String apply(
      Principal x,
      @RequestParam String name,
      @RequestParam String mission,
      @RequestParam String contact,
      RedirectAttributes r) {
    partners.apply(who(x), name, mission, contact);
    return done(r, "Organization application submitted for staff review.", "/partners");
  }

  @PostMapping("/partners/{id}/review")
  String organizationReview(
      Principal x,
      @PathVariable long id,
      @RequestParam String decision,
      @RequestParam String reason,
      RedirectAttributes r) {
    partners.reviewOrganization(who(x), id, decision, reason);
    return done(r, "Organization review saved.", "/partners");
  }

  @PostMapping("/partners/proposals")
  String proposal(
      Principal x,
      @RequestParam String title,
      @RequestParam String kind,
      @RequestParam String description,
      @RequestParam String skills,
      @RequestParam int capacity,
      RedirectAttributes r) {
    partners.propose(who(x), title, kind, description, skills, capacity);
    return done(r, "Proposal submitted for staff review.", "/partners");
  }

  @PostMapping("/partners/proposals/{id}/review")
  String proposalReview(
      Principal x,
      @PathVariable long id,
      @RequestParam boolean approve,
      @RequestParam String reason,
      @RequestParam(required = false) Long instructor,
      @RequestParam(defaultValue = "") String location,
      RedirectAttributes r) {
    partners.reviewProposal(who(x), id, approve, reason, instructor, location);
    return done(
        r,
        "Review saved. Approved workshops become archived drafts for staff to prepare and publish;"
            + " approved projects are created directly.",
        "/partners");
  }

  @GetMapping("/resources")
  String resources(Principal x, Model m) {
    var a = who(x);
    p.role(a, "ADMIN", "OFFICER", "INSTRUCTOR");
    m.addAttribute("resources", p.rows("select * from resources order by kind,name"));
    m.addAttribute("projects", p.rows("select id,title from projects where status='ACTIVE'"));
    m.addAttribute(
        "sessions",
        p.sessions(a).stream()
            .filter(
                s ->
                    ((java.sql.Timestamp) s.get("ends_at"))
                        .toLocalDateTime()
                        .isAfter(LocalDateTime.now()))
            .toList());
    String filter = a.staff() ? "true" : "pr.instructor_id=" + a.id();
    m.addAttribute(
        "reservations",
        p.rows(
            "select er.*,r.name,s.starts_at,pr.title from equipment_reservations er join resources"
                + " r on r.id=er.resource_id join sessions s on s.id=er.session_id join programs pr"
                + " on pr.id=s.program_id where "
                + filter
                + " order by er.id desc"));
    m.addAttribute(
        "ledger",
        p.rows(
            "select t.*,r.name,a.name actor,pr.title project from resource_transactions t join"
                + " resources r on r.id=t.resource_id join accounts a on a.id=t.actor_id left join"
                + " projects pr on pr.id=t.project_id order by t.id desc limit 40"));
    return page(
        m,
        "x-resources",
        "Workshop resources",
        "Know what is available, reserve equipment, and account for project materials.");
  }

  @PostMapping("/resources")
  String createResource(
      Principal x,
      @RequestParam String name,
      @RequestParam String kind,
      @RequestParam int quantity,
      @RequestParam String unit,
      @RequestParam String location,
      @RequestParam int reorderLevel,
      RedirectAttributes r) {
    resources.create(who(x), name, kind, quantity, unit, location, reorderLevel);
    return done(r, "Resource added to inventory.", "/resources");
  }

  @PostMapping("/resources/{id}/stock")
  String stock(
      Principal x,
      @PathVariable long id,
      @RequestParam int change,
      @RequestParam(required = false) Long project,
      @RequestParam String note,
      RedirectAttributes r) {
    resources.stock(who(x), id, change, project, note);
    return done(r, "Inventory transaction recorded.", "/resources");
  }

  @PostMapping("/resources/{id}/reserve")
  String reserve(
      Principal x,
      @PathVariable long id,
      @RequestParam long session,
      @RequestParam int quantity,
      RedirectAttributes r) {
    resources.reserve(who(x), id, session, quantity);
    return done(r, "Equipment reservation saved.", "/resources");
  }

  @PostMapping("/reservations/{id}/release")
  String release(Principal x, @PathVariable long id, RedirectAttributes r) {
    resources.release(who(x), id);
    return done(r, "Equipment reservation released.", "/resources");
  }

  @PostMapping("/recommendations/{id}/feedback")
  String rate(
      Principal x,
      @PathVariable long id,
      @RequestParam int rating,
      @RequestParam(defaultValue = "") String note,
      @RequestParam(defaultValue = "false") boolean consent,
      RedirectAttributes r) {
    evaluation.rate(who(x), id, rating, note, consent);
    return done(r, "Thank you. Your optional relevance feedback was saved.", "/recommendations");
  }

  @PostMapping("/recommendations/{id}/withdraw")
  String withdraw(Principal x, @PathVariable long id, RedirectAttributes r) {
    evaluation.withdraw(who(x), id);
    return done(r, "Your relevance feedback was removed.", "/recommendations");
  }

  @GetMapping("/evaluation")
  String evaluation(Principal x, Model m) {
    p.role(who(x), "ADMIN", "OFFICER", "INSTRUCTOR");
    m.addAttribute(
        "cases",
        p.rows(
            "select e.*,p.title expected,a.name author,b.name reviewer from evaluation_cases e join"
                + " programs p on p.id=e.expected_program_id join accounts a on a.id=e.author_id"
                + " left join accounts b on b.id=e.reviewer_id order by e.id desc"));
    m.addAttribute("runs", p.rows("select * from evaluation_runs order by id desc limit 10"));
    m.addAttribute(
        "evaluationResults",
        p.rows(
            "select r.*,ep.title expected,pp.title predicted from evaluation_results r join"
                + " programs ep on ep.id=r.expected_program_id join programs pp on"
                + " pp.id=r.predicted_program_id order by r.run_id desc,r.case_id limit 100"));
    m.addAttribute(
        "ratings",
        p.rows(
            "select p.title,count(*) ratings,round(avg(f.rating),2) average from"
                + " recommendation_feedback f join programs p on p.id=f.program_id group by p.id"
                + " order by p.id"));
    m.addAttribute("courses", p.programs("", ""));
    return page(
        m,
        "x-evaluation",
        "Recommendation evaluation",
        "Separate optional user feedback, reviewed relevance labels, and measured retrieval"
            + " results.");
  }

  @PostMapping("/evaluation/cases")
  String addCase(
      Principal x,
      @RequestParam String query,
      @RequestParam long expected,
      @RequestParam String rationale,
      RedirectAttributes r) {
    evaluation.addCase(who(x), query, expected, rationale);
    return done(r, "Case saved. A different reviewer must validate the label.", "/evaluation");
  }

  @PostMapping("/evaluation/cases/{id}/review")
  String reviewCase(Principal x, @PathVariable long id, RedirectAttributes r) {
    evaluation.reviewCase(who(x), id);
    return done(r, "Label reviewed.", "/evaluation");
  }

  @PostMapping("/evaluation/run")
  String runEvaluation(Principal x, RedirectAttributes r) {
    long id = evaluation.run(who(x));
    return done(r, "Evaluation run " + id + " saved with case-level results.", "/evaluation");
  }

  @GetMapping("/kiosk")
  String kiosk(Principal x, Model m) {
    var a = who(x);
    p.role(a, "PARTICIPANT");
    m.addAttribute("enrollments", p.enrollments(a));
    m.addAttribute(
        "sessions",
        p.sessions(a).stream()
            .filter(
                s ->
                    ((java.sql.Timestamp) s.get("ends_at"))
                        .toLocalDateTime()
                        .isAfter(LocalDateTime.now()))
            .toList());
    return "kiosk";
  }

  @ExceptionHandler({
    org.springframework.web.server.ResponseStatusException.class,
    org.springframework.dao.DataIntegrityViolationException.class
  })
  String handled(Exception e, Model m, jakarta.servlet.http.HttpServletResponse response) {
    int status =
        e instanceof org.springframework.web.server.ResponseStatusException r
            ? r.getStatusCode().value()
            : 409;
    response.setStatus(status);
    m.addAttribute(
        "message",
        e instanceof org.springframework.web.server.ResponseStatusException r
            ? r.getReason()
            : "This action conflicts with an existing record. Review your input.");
    return "problem";
  }
}
