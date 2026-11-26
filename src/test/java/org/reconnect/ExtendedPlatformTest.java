package org.reconnect;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5432/reconnect_test",
      "reconnect.demo=true"
    })
@AutoConfigureMockMvc
class ExtendedPlatformTest {
  @Autowired Platform p;
  @Autowired DemoSeed seed;
  @Autowired ExpansionSeed expansion;
  @Autowired LearningService learning;
  @Autowired DevelopmentService plans;
  @Autowired AdministrationService admin;
  @Autowired ResourceService resources;
  @Autowired PartnershipService partners;
  @Autowired EvaluationService evaluation;
  @Autowired MockMvc mvc;
  Platform.Actor officer, participant, instructor, administrator, ngo;

  @BeforeEach
  void fixture() {
    assertEquals(
        "reconnect_test", p.db().queryForObject("select current_database()", String.class));
    p.db()
        .execute(
            "truncate"
                + " accounts,participants,programs,enrollments,sessions,attendance,assessments,certificates,projects,project_members,feedback,notifications,audit_events,resources"
                + " restart identity cascade");
    seed.run();
    expansion.run();
    officer = p.actor("officer@reconnect.local");
    participant = p.actor("participant@reconnect.local");
    instructor = p.actor("instructor@reconnect.local");
    administrator = p.actor("admin@reconnect.local");
    ngo = p.actor("partner@reconnect.local");
  }

  long id(String sql, Object... args) {
    return p.db().queryForObject(sql, Long.class, args);
  }

  @Test
  void expandedScreensRenderForEachRole() throws Exception {
    for (String path :
        List.of(
            "/learning",
            "/learning/1",
            "/lessons/1",
            "/plans",
            "/manage/programs",
            "/manage/programs/1",
            "/resources",
            "/partners",
            "/evaluation",
            "/admin"))
      mvc.perform(get(path).with(user("admin@reconnect.local"))).andExpect(status().isOk());
    for (String path :
        List.of("/learning", "/learning/1", "/lessons/1", "/plans", "/kiosk", "/recommendations"))
      mvc.perform(get(path).with(user("participant@reconnect.local"))).andExpect(status().isOk());
    mvc.perform(get("/partners").with(user("partner@reconnect.local"))).andExpect(status().isOk());
    for (String path :
        List.of(
            "/participants",
            "/learning",
            "/reports",
            "/resources",
            "/admin",
            "/plans",
            "/evaluation"))
      mvc.perform(get(path).with(user("partner@reconnect.local")))
          .andExpect(status().isForbidden());
    mvc.perform(get("/kiosk").with(user("officer@reconnect.local")))
        .andExpect(status().isForbidden());
  }

  @Test
  void multiQuestionQuizRequiresEveryAnswerAndPreservesHistory() {
    long lesson = id("select id from lessons where program_id=1 and position=2");
    assertThrows(ResponseStatusException.class, () -> learning.quiz(participant, lesson, Map.of()));
    var q = p.rows("select id from quiz_questions where lesson_id=? order by id", lesson);
    assertEquals(
        50,
        learning.quiz(
            participant,
            lesson,
            Map.of("q_" + q.get(0).get("id"), "A", "q_" + q.get(1).get("id"), "B")));
    assertEquals(
        100,
        learning.quiz(
            participant,
            lesson,
            Map.of("q_" + q.get(0).get("id"), "A", "q_" + q.get(1).get("id"), "A")));
    assertThrows(
        ResponseStatusException.class,
        () -> learning.addQuestion(officer, lesson, "New?", "A", "B", "C", "A"));
    assertEquals(2, id("select count(*) from quiz_attempts where lesson_id=?", lesson));
  }

  @Test
  void requiredLearningAndPracticalReviewGateCertificate() {
    long enrollment = id("select id from enrollments where program_id=1 and participant_id=1");
    p.db().update("update programs set duration=1 where id=1");
    long session = id("select id from sessions where program_id=1 order by starts_at limit 1");
    p.db()
        .update(
            "insert into attendance(session_id,participant_id,present,recorded_by)"
                + " values(?,1,true,?) on conflict(session_id,participant_id) do update set"
                + " present=true",
            session,
            officer.id());
    p.db().update("insert into assessments(participant_id,program_id,score) values(1,1,100)");
    assertThrows(
        ResponseStatusException.class, () -> p.enrollmentAction(officer, enrollment, "complete"));
    for (var l : learning.lessons(participant, 1)) {
      long lid = Platform.num(l, "id");
      learning.read(participant, lid);
      Map<String, String> answers = new HashMap<>();
      for (var q : p.rows("select id,answer from quiz_questions where lesson_id=?", lid))
        answers.put("q_" + q.get("id"), q.get("answer").toString());
      learning.quiz(participant, lid, answers);
    }
    assertThrows(
        ResponseStatusException.class, () -> p.enrollmentAction(officer, enrollment, "complete"));
    for (var a : learning.assignments(participant, 1)) {
      long aid = Platform.num(a, "id");
      learning.submit(participant, aid, "I prepared and demonstrated the agreed workshop task.");
      long sub = id("select id from submissions where assignment_id=? and participant_id=1", aid);
      learning.review(
          instructor,
          sub,
          85,
          "Good technique and reflection",
          "Observed safe measuring, preparation and cleanup in the workshop.");
    }
    p.enrollmentAction(officer, enrollment, "complete");
    assertEquals(1, id("select count(*) from certificates where enrollment_id=?", enrollment));
  }

  @Test
  void assignmentRevisionRetainsReviewEvidenceAndPermissions() {
    long a = id("select id from assignments where program_id=1 and kind='PRACTICAL'");
    learning.submit(participant, a, "First demonstration");
    long sub = id("select id from submissions where assignment_id=?", a);
    assertThrows(ResponseStatusException.class, () -> learning.submit(participant, a, "Overwrite"));
    assertThrows(
        ResponseStatusException.class,
        () -> learning.review(participant, sub, 100, "Self approval", "None"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            learning.review(
                p.actor("coach@reconnect.local"), sub, 100, "Wrong instructor", "None"));
    learning.review(
        instructor, sub, 60, "Practise measuring again", "Observed inconsistent measurements");
    learning.submit(participant, a, "Second supervised demonstration");
    learning.review(
        instructor, sub, 90, "Accurate measurements", "Observed successful task completion");
    assertEquals(2, id("select count(*) from submission_reviews where submission_id=?", sub));
    assertEquals(
        "First demonstration",
        p.one(
                "select response from submission_reviews where submission_id=? order by id limit 1",
                sub)
            .get("response"));
  }

  @Test
  void developmentGoalsRequireAgreementMilestonesAndStaffReview() {
    long g =
        plans.create(
            participant,
            1,
            "Practice carpentry",
            "Build confidence through supported practice",
            LocalDate.now().plusDays(20));
    plans.milestone(participant, g, "Demonstrate measuring", LocalDate.now().plusDays(10));
    long m = id("select id from milestones where goal_id=?", g);
    assertThrows(ResponseStatusException.class, () -> plans.finishMilestone(participant, m));
    assertThrows(ResponseStatusException.class, () -> plans.action(officer, g, "agree", ""));
    plans.action(participant, g, "agree", "");
    assertThrows(
        ResponseStatusException.class, () -> plans.action(officer, g, "complete", "Reviewed"));
    plans.finishMilestone(participant, m);
    assertThrows(
        ResponseStatusException.class,
        () -> plans.action(participant, g, "complete", "Self complete"));
    plans.action(officer, g, "complete", "Reviewed practical evidence together");
    assertEquals("COMPLETED", p.one("select status from goals where id=?", g).get("status"));
    assertThrows(
        ResponseStatusException.class,
        () -> plans.create(participant, 2, "Wrong profile", "No", LocalDate.now().plusDays(1)));
  }

  @Test
  void programAdministrationPreservesCapacityAndPromotesFifo() {
    assertThrows(
        ResponseStatusException.class,
        () ->
            admin.program(
                officer,
                1,
                "Woodcraft",
                "Woodcraft learning",
                "wood",
                "",
                instructor.id(),
                1,
                4,
                "A"));
    p.db().update("delete from enrollments where program_id=1 and participant_id<>1");
    p.db().update("update programs set capacity=1 where id=1");
    assertEquals("WAITLISTED", p.enroll(officer, 2, 1));
    assertEquals("WAITLISTED", p.enroll(officer, 3, 1));
    admin.archive(officer, 1, true);
    assertThrows(ResponseStatusException.class, () -> admin.promote(officer, 1));
    admin.archive(officer, 1, false);
    p.enrollmentAction(
        officer,
        id("select id from enrollments where program_id=1 and participant_id=1"),
        "cancel");
    admin.promote(officer, 1);
    assertEquals(
        "ENROLLED",
        p.one("select status from enrollments where program_id=1 and participant_id=2")
            .get("status"));
    assertEquals(
        "WAITLISTED",
        p.one("select status from enrollments where program_id=1 and participant_id=3")
            .get("status"));
  }

  @Test
  void deactivationInvalidatesAnExistingAuthenticatedSession() throws Exception {
    admin.account(
        administrator, participant.id(), participant.name(), "participant@reconnect.local", false);
    mvc.perform(get("/dashboard").with(user("participant@reconnect.local")))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login?disabled"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            admin.account(
                administrator,
                administrator.id(),
                administrator.name(),
                "admin@reconnect.local",
                false));
  }

  @Test
  void partnerApprovalCreatesDraftAndRejectsDuplicateDecisions() {
    long org = id("select id from organizations where account_id=?", ngo.id());
    partners.reviewOrganization(officer, org, "SUSPENDED", "Pending document review");
    assertThrows(
        ResponseStatusException.class,
        () -> partners.propose(ngo, "Workshop", "WORKSHOP", "Making books", "binding", 12));
    partners.reviewOrganization(officer, org, "APPROVED", "Staff checked proposal suitability");
    long proposal =
        partners.propose(
            ngo,
            "Bookbinding workshop",
            "WORKSHOP",
            "Repair and bind learning books",
            "bookbinding",
            12);
    partners.reviewProposal(
        officer,
        proposal,
        true,
        "Approved with internal supervision",
        instructor.id(),
        "Learning room");
    long program = id("select program_id from proposals where id=?", proposal);
    assertEquals("ARCHIVED", p.program(program).get("status"));
    assertThrows(ResponseStatusException.class, () -> admin.archive(officer, program, false));
    long lesson =
        learning.addLesson(
            officer, program, "Introduction", "Tools and supervised techniques", true);
    learning.addQuestion(
        officer, lesson, "Who helps?", "Instructor", "Nobody", "Ignore support", "A");
    admin.archive(officer, program, false);
    assertThrows(
        ResponseStatusException.class,
        () -> partners.reviewProposal(officer, proposal, true, "Again", instructor.id(), "Room"));
    long project =
        partners.propose(
            ngo, "Books for community", "PROJECT", "Repair community books", "binding", 20);
    partners.reviewProposal(officer, project, true, "Approved", null, "");
    assertNotNull(p.one("select project_id from proposals where id=?", project).get("project_id"));
  }

  @Test
  void concurrentMaterialConsumptionCannotMakeStockNegative() throws Exception {
    long resource = resources.create(officer, "Boards", "MATERIAL", 1, "board", "Store", 0);
    try (var pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch start = new CountDownLatch(1);
      var tasks = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++)
        tasks.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    resources.stock(officer, resource, -1, 1L, "Used for desk");
                    return true;
                  } catch (ResponseStatusException e) {
                    return false;
                  }
                }));
      start.countDown();
      int wins = 0;
      for (var task : tasks) if (task.get(20, TimeUnit.SECONDS)) wins++;
      assertEquals(1, wins);
    }
    assertEquals(0, id("select quantity from resources where id=?", resource));
    assertEquals(2, id("select count(*) from resource_transactions where resource_id=?", resource));
    assertThrows(
        ResponseStatusException.class,
        () -> resources.stock(participant, resource, 5, null, "Unauthorized"));
  }

  long session(long program, LocalDateTime start, String room) {
    return p.db()
        .queryForObject(
            "insert into sessions(program_id,starts_at,ends_at,room) values(?,?,?,?) returning id",
            Long.class,
            program,
            start,
            start.plusHours(1),
            room);
  }

  @Test
  void equipmentRejectsOverlapAndAllowsReuseAfterRelease() {
    long r = resources.create(officer, "Tablet", "EQUIPMENT", 1, "tablet", "Room", 0);
    var start = LocalDateTime.now().plusDays(30);
    long a = session(1, start, "A"),
        b = session(2, start, "B"),
        c = session(2, start.plusHours(1), "B");
    resources.reserve(officer, r, a, 1);
    assertThrows(ResponseStatusException.class, () -> resources.reserve(officer, r, b, 1));
    resources.reserve(officer, r, c, 1);
    resources.release(
        officer,
        id("select id from equipment_reservations where resource_id=? and session_id=?", r, a));
    resources.reserve(officer, r, b, 1);
    assertThrows(
        ResponseStatusException.class,
        () -> resources.stock(officer, r, -1, 1L, "Consume equipment"));
  }

  @Test
  void rescheduleRechecksEquipmentAndRollsBack() {
    p.db().update("delete from enrollments where program_id in (1,2)");
    p.db()
        .update(
            "update programs set instructor_id=(select id from accounts where"
                + " email='coach@reconnect.local') where id=2");
    long r = resources.create(officer, "Shared tool", "EQUIPMENT", 1, "kit", "Store", 0);
    var start = LocalDateTime.now().plusDays(40);
    long a = session(1, start, "A"), b = session(2, start.plusHours(2), "B");
    resources.reserve(officer, r, a, 1);
    resources.reserve(officer, r, b, 1);
    assertThrows(
        ResponseStatusException.class,
        () -> admin.reschedule(officer, b, start, start.plusHours(1), "B"));
    assertEquals(
        start.plusHours(2).withNano(0),
        ((java.sql.Timestamp)
                p.one("select starts_at from sessions where id=?", b).get("starts_at"))
            .toLocalDateTime()
            .withNano(0));
    admin.reschedule(officer, b, start.plusHours(1), start.plusHours(2), "B");
  }

  @Test
  void voluntaryFeedbackCanBeWithdrawnAndLabelsRequireOtherReviewer() {
    assertThrows(
        ResponseStatusException.class, () -> evaluation.rate(participant, 2, 5, "Useful", false));
    evaluation.rate(participant, 2, 5, "Useful", true);
    assertEquals(1, id("select count(*) from recommendation_feedback"));
    evaluation.withdraw(participant, 2);
    assertEquals(0, id("select count(*) from recommendation_feedback"));
    assertThrows(ResponseStatusException.class, () -> evaluation.run(officer));
    long c =
        evaluation.addCase(
            officer,
            "wood furniture carpentry measuring",
            1,
            "The query asks for woodcraft skills");
    assertThrows(ResponseStatusException.class, () -> evaluation.reviewCase(officer, c));
    evaluation.reviewCase(instructor, c);
    long run = evaluation.run(officer);
    assertEquals(1, id("select case_count from evaluation_runs where id=?", run));
    assertEquals(1, id("select count(*) from evaluation_results where run_id=?", run));
  }

  @Test
  void languageChoicePersistsAndAccessibleKioskRenders() throws Exception {
    var session = new MockHttpSession();
    mvc.perform(get("/kiosk?lang=hi").session(session).with(user("participant@reconnect.local")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("lang=\"hi\"")));
    assertEquals("hi", session.getAttribute("uiLanguage"));
    mvc.perform(get("/learning").session(session).with(user("participant@reconnect.local")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("lang=\"hi\"")));
    mvc.perform(get("/kiosk?lang=pa").session(session).with(user("participant@reconnect.local")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ਪੰਜਾਬੀ")));
  }

  @Test
  void populatedWorkflowScreensRender() throws Exception {
    long proposal =
        partners.propose(ngo, "Demo workshop", "WORKSHOP", "Learning together", "woodcraft", 10);
    mvc.perform(get("/partners").with(user("officer@reconnect.local"))).andExpect(status().isOk());
    partners.reviewProposal(officer, proposal, true, "Reviewed", instructor.id(), "Workshop A");
    mvc.perform(get("/partners").with(user("officer@reconnect.local"))).andExpect(status().isOk());
    long resource = id("select id from resources where kind='EQUIPMENT' order by id limit 1");
    long session = session(1, LocalDateTime.now().plusDays(50), "Test room");
    resources.reserve(officer, resource, session, 1);
    mvc.perform(get("/resources").with(user("instructor@reconnect.local")))
        .andExpect(status().isOk());
    mvc.perform(get("/sessions/" + session + "/edit").with(user("instructor@reconnect.local")))
        .andExpect(status().isOk());
    evaluation.rate(participant, 2, 4, "Useful", true);
    long c = evaluation.addCase(officer, "woodwork", 1, "Woodcraft match");
    evaluation.reviewCase(instructor, c);
    evaluation.run(officer);
    mvc.perform(get("/evaluation").with(user("officer@reconnect.local")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.containsString("Case-level evaluation results")));
    mvc.perform(get("/recommendations").with(user("participant@reconnect.local")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Withdraw feedback")));
  }
}
