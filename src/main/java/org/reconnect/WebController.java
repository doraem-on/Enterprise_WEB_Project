package org.reconnect;

import java.security.Principal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WebController {
  final Platform p;
  final RecommendationEngine ml;
  final Scheduling scheduler;
  final PasswordEncoder encoder;
  final boolean demo;

  public WebController(
      Platform p,
      RecommendationEngine ml,
      Scheduling scheduler,
      PasswordEncoder encoder,
      @Value("${reconnect.demo}") boolean demo) {
    this.p = p;
    this.ml = ml;
    this.scheduler = scheduler;
    this.encoder = encoder;
    this.demo = demo;
  }

  Platform.Actor who(Principal principal) {
    return p.actor(principal.getName());
  }

  @ModelAttribute
  void shared(Model m, Principal principal) {
    m.addAttribute("demo", demo);
    if (principal != null) {
      var a = who(principal);
      m.addAttribute("actor", a);
      m.addAttribute("isStaff", a.staff());
      m.addAttribute("isParticipant", a.role().equals("PARTICIPANT"));
      m.addAttribute(
          "unread",
          p.db()
              .queryForObject(
                  "select count(*) from notifications where account_id=? and not seen",
                  Long.class,
                  a.id()));
    }
  }

  String view(Model m, String page, String title, String subtitle) {
    m.addAttribute("page", page);
    m.addAttribute("title", title);
    m.addAttribute("subtitle", subtitle);
    return "app";
  }

  String done(RedirectAttributes r, String message, String url) {
    r.addFlashAttribute("success", message);
    return "redirect:" + url;
  }

  @GetMapping("/")
  String home() {
    return "landing";
  }

  @GetMapping("/login")
  String login() {
    return "login";
  }

  @GetMapping("/health")
  @ResponseBody
  Map<String, String> health() {
    p.db().queryForObject("select 1", Integer.class);
    return Map.of("status", "ok", "recommendations", ml.status());
  }

  @GetMapping("/dashboard")
  String dashboard(Model m, Principal principal) {
    var a = who(principal);
    var es = p.enrollments(a);
    m.addAttribute("people", p.participants(a));
    m.addAttribute("programs", p.programs("", ""));
    m.addAttribute("enrollments", es);
    m.addAttribute(
        "sessions",
        p.sessions(a).stream()
            .filter(
                s ->
                    ((java.sql.Timestamp) s.get("ends_at"))
                        .toLocalDateTime()
                        .isAfter(LocalDateTime.now()))
            .limit(4)
            .toList());
    m.addAttribute(
        "activeCount", es.stream().filter(e -> e.get("status").equals("ENROLLED")).count());
    m.addAttribute(
        "completedCount", es.stream().filter(e -> e.get("status").equals("COMPLETED")).count());
    m.addAttribute(
        "pendingCount",
        es.stream()
            .filter(e -> Set.of("REQUESTED", "WAITLISTED").contains(e.get("status")))
            .count());
    m.addAttribute("projects", p.rows("select * from projects order by id limit 3"));
    return view(
        m,
        "dashboard",
        "A little progress. A new possibility.",
        "Your rehabilitation community, connected.");
  }

  @GetMapping("/programs")
  String programs(
      Model m,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String category) {
    m.addAttribute("programs", p.programs(q, category));
    m.addAttribute("q", q);
    m.addAttribute("category", category);
    return view(
        m,
        "programs",
        "Find the next chapter",
        "Practical skills, creative expression, and everyday wellbeing.");
  }

  @GetMapping("/programs/new")
  String newProgram(Model m, Principal principal) {
    p.role(who(principal), "ADMIN", "OFFICER");
    m.addAttribute(
        "instructors",
        p.rows("select id,name from accounts where role='INSTRUCTOR' and active=true"));
    return view(
        m,
        "program-new",
        "Create a program",
        "Give a new learning opportunity a clear purpose and plan.");
  }

  @PostMapping("/programs")
  String createProgram(
      Principal principal,
      @RequestParam String title,
      @RequestParam String category,
      @RequestParam String description,
      @RequestParam String skills,
      @RequestParam(defaultValue = "") String prerequisites,
      @RequestParam long instructor,
      @RequestParam int capacity,
      @RequestParam int duration,
      @RequestParam String location,
      @RequestParam String lesson,
      @RequestParam String question,
      @RequestParam String optionA,
      @RequestParam String optionB,
      @RequestParam String optionC,
      @RequestParam String answer,
      RedirectAttributes r) {
    p.saveProgram(
        who(principal),
        title,
        category,
        description,
        skills,
        prerequisites,
        instructor,
        capacity,
        duration,
        location,
        lesson,
        question,
        optionA,
        optionB,
        optionC,
        answer);
    return done(r, "Program published.", "/programs");
  }

  @GetMapping("/programs/{id}")
  String program(@PathVariable long id, Model m, Principal principal) {
    var a = who(principal);
    var program = p.program(id);
    m.addAttribute("program", program);
    m.addAttribute("people", p.participants(a));
    m.addAttribute(
        "canManage",
        a.staff()
            || (a.role().equals("INSTRUCTOR")
                && Objects.equals(program.get("instructor_id"), a.id())));
    m.addAttribute(
        "enrollments",
        p.enrollments(a).stream().filter(e -> Platform.num(e, "program_id") == id).toList());
    m.addAttribute(
        "sessions", p.rows("select * from sessions where program_id=? order by starts_at", id));
    m.addAttribute(
        "activeEnrollment",
        a.role().equals("PARTICIPANT")
            && p.enrollments(a).stream()
                .anyMatch(
                    e ->
                        Platform.num(e, "program_id") == id && e.get("status").equals("ENROLLED")));
    m.addAttribute(
        "hasCurrentEnrollment",
        a.role().equals("PARTICIPANT")
            && p.enrollments(a).stream()
                .anyMatch(
                    e ->
                        Platform.num(e, "program_id") == id
                            && !e.get("status").equals("CANCELLED")));
    return view(
        m, "program", program.get("title").toString(), program.get("description").toString());
  }

  @GetMapping("/participants")
  String participants(Model m, Principal principal) {
    m.addAttribute("people", p.participants(who(principal)));
    return view(
        m,
        "participants",
        "People, not just profiles",
        "Skills, interests, and aspirations at the centre of every plan.");
  }

  @GetMapping("/participants/{id}")
  String profile(@PathVariable long id, Model m, Principal principal) {
    var a = who(principal);
    p.participantAccess(a, id);
    m.addAttribute("person", p.one("select * from participants where id=?", id));
    m.addAttribute(
        "enrollments",
        p.enrollments(a).stream().filter(e -> Platform.num(e, "participant_id") == id).toList());
    return view(
        m, "profile", "A personal path forward", "Keep interests and learning goals up to date.");
  }

  @PostMapping("/participants")
  String profileSave(
      Principal principal,
      @RequestParam(defaultValue = "0") long id,
      @RequestParam String name,
      @RequestParam String education,
      @RequestParam String skills,
      @RequestParam String interests,
      @RequestParam String goals,
      @RequestParam String language,
      RedirectAttributes r) {
    p.saveProfile(who(principal), id, name, education, skills, interests, goals, language);
    return done(r, "Profile saved.", id == 0 ? "/participants" : "/participants/" + id);
  }

  @GetMapping("/enrollments")
  String enrollments(Model m, Principal principal) {
    m.addAttribute("enrollments", p.enrollments(who(principal)));
    return view(
        m,
        "enrollments",
        "Learning in motion",
        "Requests, active learning, and milestones worth recognising.");
  }

  @PostMapping("/enrollments")
  String enroll(
      Principal principal,
      @RequestParam long participantId,
      @RequestParam long programId,
      RedirectAttributes r) {
    String s = p.enroll(who(principal), participantId, programId);
    return done(r, "Enrollment " + s.toLowerCase() + ".", "/enrollments");
  }

  @PostMapping("/enrollments/{id}/{action}")
  String enrollmentAction(
      Principal principal,
      @PathVariable long id,
      @PathVariable String action,
      RedirectAttributes r) {
    p.enrollmentAction(who(principal), id, action);
    return done(r, "Enrollment updated.", "/enrollments");
  }

  @GetMapping("/schedule")
  String schedule(Model m, Principal principal) {
    var a = who(principal);
    m.addAttribute("sessions", p.sessions(a));
    m.addAttribute(
        "programs",
        p.programs("", "").stream()
            .filter(x -> a.staff() || Objects.equals(x.get("instructor_id"), a.id()))
            .toList());
    return view(
        m,
        "schedule",
        "Make room for growth",
        "A shared calendar with time for learning, creativity, and movement.");
  }

  @PostMapping("/schedule")
  String createSession(
      Principal principal,
      @RequestParam long programId,
      @RequestParam LocalDateTime start,
      @RequestParam LocalDateTime end,
      @RequestParam String room,
      RedirectAttributes r) {
    p.schedule(who(principal), programId, start, end, room);
    return done(r, "Session scheduled.", "/schedule");
  }

  @PostMapping("/schedule/auto")
  String autoSchedule(Principal principal, RedirectAttributes r) {
    int n = scheduler.generate(who(principal));
    return done(
        r,
        n == 0
            ? "Next week's programs already have sessions."
            : n + " conflict-free sessions scheduled for next week.",
        "/schedule");
  }

  @GetMapping("/sessions/{id}")
  String attendance(@PathVariable long id, Principal principal, Model m) {
    var s =
        p.one(
            "select s.*,p.title from sessions s join programs p on p.id=s.program_id where s.id=?",
            id);
    p.manageProgram(who(principal), Platform.num(s, "program_id"));
    m.addAttribute("session", s);
    m.addAttribute(
        "roster",
        p.rows(
            "select t.*,at.present,at.feedback from participants t join enrollments e on"
                + " e.participant_id=t.id left join attendance at on at.participant_id=t.id and"
                + " at.session_id=? where e.program_id=? and e.status='ENROLLED' order by t.name",
            id,
            s.get("program_id")));
    return view(m, "attendance", "Every session matters", s.get("title").toString());
  }

  @PostMapping("/sessions/{id}/attendance")
  String recordAttendance(
      @PathVariable long id,
      Principal principal,
      @RequestParam long participantId,
      @RequestParam boolean present,
      @RequestParam String feedback,
      RedirectAttributes r) {
    p.attendance(who(principal), id, participantId, present, feedback);
    return done(r, "Attendance saved.", "/sessions/" + id);
  }

  @PostMapping("/programs/{id}/assessment")
  String assess(
      @PathVariable long id,
      Principal principal,
      @RequestParam String answer,
      RedirectAttributes r) {
    int score = p.assess(who(principal), id, answer);
    return done(
        r,
        score == 100
            ? "Knowledge check passed. Your progress has been saved."
            : "Review the lesson and try again. Your attempt has been saved.",
        "/programs/" + id);
  }

  @GetMapping("/recommendations")
  String recommendations(
      Model m, Principal principal, @RequestParam(required = false) Long participantId) {
    var a = who(principal);
    var people = p.participants(a);
    m.addAttribute("people", people);
    if (!people.isEmpty()) {
      long pid = participantId == null ? Platform.num(people.getFirst(), "id") : participantId;
      p.participantAccess(a, pid);
      var person = p.one("select * from participants where id=?", pid);
      m.addAttribute("person", person);
      var available =
          p.programs("", "").stream()
              .filter(
                  x ->
                      p.db()
                              .queryForObject(
                                  "select count(*) from enrollments where participant_id=? and"
                                      + " program_id=? and status in"
                                      + " ('ENROLLED','COMPLETED','REQUESTED','WAITLISTED')",
                                  Long.class,
                                  pid,
                                  x.get("id"))
                          == 0)
              .toList();
      m.addAttribute("matches", ml.rank(person, available));
      m.addAttribute("careers", ml.rank(person, CareerData.items()));
    }
    m.addAttribute("modelStatus", ml.status());
    return view(
        m,
        "recommendations",
        "Possibilities that feel personal",
        "Explore relevant programs and career pathways. You choose the direction.");
  }

  @GetMapping("/projects")
  String projects(Model m, Principal principal) {
    var a = who(principal);
    m.addAttribute(
        "projects",
        p.rows(
            "select pr.*,(select count(*) from project_members pm where pm.project_id=pr.id)"
                + " members from projects pr order by id"));
    m.addAttribute(
        "joined",
        a.role().equals("PARTICIPANT")
            ? p
                .rows(
                    "select project_id from project_members where participant_id=?",
                    p.ownParticipant(a))
                .stream()
                .map(x -> Platform.num(x, "project_id"))
                .toList()
            : List.of());
    return view(
        m,
        "projects",
        "Small acts. Shared impact.",
        "Voluntary projects that build skills and give something back.");
  }

  @PostMapping("/projects")
  String createProject(
      Principal principal,
      @RequestParam String title,
      @RequestParam String description,
      @RequestParam String beneficiary,
      @RequestParam int target,
      @RequestParam String unit,
      @RequestParam String skills,
      RedirectAttributes r) {
    p.createProject(who(principal), title, description, beneficiary, target, unit, skills);
    return done(r, "Community project created.", "/projects");
  }

  @PostMapping("/projects/{id}/progress")
  String progress(
      Principal principal, @PathVariable long id, @RequestParam int amount, RedirectAttributes r) {
    p.projectProgress(who(principal), id, amount);
    return done(r, "Project milestone recorded.", "/projects");
  }

  @PostMapping("/projects/{id}/join")
  String join(Principal principal, @PathVariable long id, RedirectAttributes r) {
    p.joinProject(who(principal), id);
    return done(r, "You have joined this voluntary project.", "/projects");
  }

  @GetMapping("/progress")
  String progress(Model m, Principal principal) {
    m.addAttribute("enrollments", p.enrollments(who(principal)));
    return view(
        m,
        "progress",
        "Progress you can build on",
        "Attendance, knowledge checks, and completed learning.");
  }

  @GetMapping("/portfolio/{id}")
  String portfolio(@PathVariable long id, Principal principal, Model m) {
    var a = who(principal);
    p.participantAccess(a, id);
    m.addAttribute("person", p.one("select * from participants where id=?", id));
    m.addAttribute(
        "enrollments",
        p.enrollments(a).stream().filter(e -> Platform.num(e, "participant_id") == id).toList());
    return "portfolio";
  }

  @GetMapping("/certificates/{id}")
  String certificate(@PathVariable UUID id, Principal principal, Model m) {
    var c = certificateData(id);
    p.participantAccess(who(principal), Platform.num(c, "participant_id"));
    m.addAttribute("certificate", c);
    return "certificate";
  }

  Map<String, Object> certificateData(UUID id) {
    return p.one(
        "select c.*,t.name,t.reference,t.id participant_id,pr.title,pr.skills from certificates c"
            + " join enrollments e on e.id=c.enrollment_id join participants t on"
            + " t.id=e.participant_id join programs pr on pr.id=e.program_id where c.id=?",
        id);
  }

  @GetMapping("/verify/{id}")
  String verify(@PathVariable UUID id, Model m) {
    var c = certificateData(id);
    m.addAttribute(
        "verification",
        Map.of("id", c.get("id"), "title", c.get("title"), "issued_at", c.get("issued_at")));
    return "verify";
  }

  @GetMapping("/feedback")
  String feedback(Model m, Principal principal) {
    var a = who(principal);
    m.addAttribute(
        "feedback",
        a.staff()
            ? p.rows(
                "select f.*,a.name author from feedback f join accounts a on a.id=f.author_id order"
                    + " by created_at desc")
            : p.rows(
                "select f.*,a.name author from feedback f join accounts a on a.id=f.author_id where"
                    + " author_id=? order by created_at desc",
                a.id()));
    return view(
        m,
        "feedback",
        "A voice in your journey",
        "Share an idea, tell us what helps, or request staff support.");
  }

  @PostMapping("/feedback")
  String feedbackSend(
      Principal principal,
      @RequestParam String kind,
      @RequestParam String message,
      RedirectAttributes r) {
    p.sendFeedback(who(principal), kind, message);
    return done(r, "Your message has been sent to the rehabilitation team.", "/feedback");
  }

  @PostMapping("/feedback/{id}/resolve")
  String resolve(
      Principal principal,
      @PathVariable long id,
      @RequestParam String resolution,
      RedirectAttributes r) {
    p.resolve(who(principal), id, resolution);
    return done(r, "Response saved.", "/feedback");
  }

  @GetMapping("/notifications")
  String notifications(Model m, Principal principal) {
    m.addAttribute(
        "notifications",
        p.rows(
            "select * from notifications where account_id=? order by created_at desc",
            who(principal).id()));
    return view(
        m,
        "notifications",
        "Your updates",
        "Enrollment decisions, milestones, and responses from your team.");
  }

  @PostMapping("/notifications/read")
  String read(Principal principal, RedirectAttributes r) {
    p.db().update("update notifications set seen=true where account_id=?", who(principal).id());
    return done(r, "Notifications marked as read.", "/notifications");
  }

  @GetMapping("/admin")
  String admin(Model m, Principal principal) {
    p.role(who(principal), "ADMIN");
    m.addAttribute(
        "accounts", p.rows("select id,name,email,role,active from accounts order by id"));
    m.addAttribute(
        "audit",
        p.rows(
            "select e.*,a.name actor from audit_events e join accounts a on a.id=e.actor_id order"
                + " by e.id desc limit 40"));
    m.addAttribute("modelStatus", ml.status());
    return view(
        m,
        "admin",
        "A well-supported community",
        "Manage access and review the activity behind your platform.");
  }

  @org.springframework.transaction.annotation.Transactional
  @PostMapping("/admin/accounts")
  String account(
      Principal principal,
      @RequestParam String name,
      @RequestParam String email,
      @RequestParam String role,
      @RequestParam String password,
      RedirectAttributes r) {
    var a = who(principal);
    p.role(a, "ADMIN");
    p.require(
        Set.of("ADMIN", "OFFICER", "INSTRUCTOR", "PARTICIPANT").contains(role), "Invalid role");
    p.require(
        password.length() >= 12 && password.length() <= 72,
        "Password must contain 12–72 characters");
    p.require(email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"), "Enter a valid email");
    createAccount(a, name, email, role, password);
    return done(r, "Account created.", "/admin");
  }

  @org.springframework.transaction.annotation.Transactional
  public void createAccount(
      Platform.Actor a, String name, String email, String role, String password) {
    Long id =
        p.db()
            .queryForObject(
                "insert into accounts(name,email,role,password) values(?,?,?,?) returning id",
                Long.class,
                Platform.required(name, 120),
                Platform.required(email.toLowerCase(Locale.ROOT), 180),
                role,
                encoder.encode(password));
    if (role.equals("PARTICIPANT"))
      p.db()
          .update(
              "insert into participants(account_id,name,reference) values(?,?,?)",
              id,
              name,
              "RC-" + UUID.randomUUID().toString().substring(0, 8));
    p.audit(a, "Account created", email);
  }

  @GetMapping("/reports")
  String reports(Model m, Principal principal) {
    p.role(who(principal), "ADMIN", "OFFICER");
    m.addAttribute(
        "stats",
        p.rows(
            "select p.title,p.category,count(e.id) total,count(e.id) filter(where"
                + " e.status='ENROLLED') active,count(e.id) filter(where e.status='COMPLETED')"
                + " completed,count(e.id) filter(where e.status in ('REQUESTED','WAITLISTED'))"
                + " pending from programs p left join enrollments e on e.program_id=p.id group by"
                + " p.id order by p.id"));
    return view(
        m,
        "reports",
        "Understand what is working",
        "Participation and completion describe program activity, not a person's worth.");
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
            : "This action conflicts with an existing record. Please review your input.");
    return "problem";
  }
}
