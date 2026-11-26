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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5432/reconnect_test",
      "reconnect.demo=true"
    })
@AutoConfigureMockMvc
class PlatformTest {
  @Autowired Platform p;
  @Autowired DemoSeed seed;
  @Autowired MockMvc mvc;
  @Autowired Scheduling scheduler;
  @Autowired RecommendationEngine ml;
  Platform.Actor officer, participant, instructor;

  @BeforeEach
  void fixture() {
    String database = p.db().queryForObject("select current_database()", String.class);
    assertEquals("reconnect_test", database, "Never reset a non-test database");
    p.db()
        .execute(
            "truncate"
                + " accounts,participants,programs,enrollments,sessions,attendance,assessments,certificates,projects,project_members,feedback,notifications,audit_events"
                + " restart identity cascade");
    seed.run();
    officer = p.actor("officer@reconnect.local");
    participant = p.actor("participant@reconnect.local");
    instructor = p.actor("instructor@reconnect.local");
  }

  @Test
  void concurrentLastSeatCannotOverbook() throws Exception {
    p.db().update("delete from enrollments where program_id=1");
    p.db().update("update programs set capacity=1 where id=1");
    try (var pool = Executors.newFixedThreadPool(8)) {
      CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
      List<Future<String>> futures = new ArrayList<>();
      for (long id = 2; id <= 9; id++) {
        final long pid = id;
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return p.enroll(officer, pid, 1);
                }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      List<String> results = new ArrayList<>();
      for (var f : futures) results.add(f.get(30, TimeUnit.SECONDS));
      assertEquals(1, results.stream().filter("ENROLLED"::equals).count());
      assertEquals(7, results.stream().filter("WAITLISTED"::equals).count());
    }
    assertEquals(
        1,
        p.db()
            .queryForObject(
                "select count(*) from enrollments where program_id=1 and status='ENROLLED'",
                Integer.class));
  }

  @Test
  void participantCanRequestButCannotSelfApprove() {
    String state = p.enroll(participant, 1, 2);
    assertEquals("REQUESTED", state);
    long id =
        p.db()
            .queryForObject(
                "select id from enrollments where participant_id=1 and program_id=2", Long.class);
    assertThrows(Exception.class, () -> p.enrollmentAction(participant, id, "approve"));
    p.enrollmentAction(officer, id, "approve");
    assertEquals("ENROLLED", p.one("select status from enrollments where id=?", id).get("status"));
  }

  @Test
  void duplicateEnrollmentRejected() {
    assertThrows(Exception.class, () -> p.enroll(participant, 1, 1));
  }

  @Test
  void participantCannotEnrollSomeoneElse() {
    assertThrows(Exception.class, () -> p.enroll(participant, 2, 3));
  }

  @Test
  void cancellationReleasesCapacity() {
    p.db().update("update programs set capacity=1 where id=1");
    p.db()
        .update(
            "update enrollments set status='CANCELLED' where program_id=1 and participant_id<>1");
    long id =
        p.db()
            .queryForObject(
                "select id from enrollments where program_id=1 and participant_id=1", Long.class);
    p.enrollmentAction(participant, id, "cancel");
    assertEquals("ENROLLED", p.enroll(officer, 3, 1));
  }

  @Test
  void scheduleRejectsRoomAndInstructorConflicts() {
    LocalDateTime s = LocalDate.now().plusMonths(2).atTime(9, 0);
    p.schedule(officer, 1, s, s.plusHours(1), "Test room");
    assertThrows(Exception.class, () -> p.schedule(officer, 2, s, s.plusHours(1), "Test room"));
    assertThrows(Exception.class, () -> p.schedule(officer, 3, s, s.plusHours(1), "Another room"));
    p.schedule(officer, 2, s.plusHours(1), s.plusHours(2), "Test room");
  }

  @Test
  void scheduleRejectsParticipantConflict() {
    p.db()
        .update("insert into enrollments(participant_id,program_id,status) values(1,2,'ENROLLED')");
    LocalDateTime s = LocalDate.now().plusMonths(2).atTime(11, 0);
    p.schedule(officer, 1, s, s.plusHours(1), "A");
    assertThrows(Exception.class, () -> p.schedule(officer, 2, s, s.plusHours(1), "B"));
  }

  @Test
  void automaticScheduleIsConflictFreeAndRepeatSafe() {
    LocalDate next =
        LocalDate.now()
            .plusWeeks(1)
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    int expected =
        p.db()
            .queryForObject(
                "select count(*) from programs p where not exists (select 1 from sessions s where"
                    + " s.program_id=p.id and s.starts_at>=? and s.starts_at<?)",
                Integer.class,
                next.atStartOfDay(),
                next.plusDays(5).atStartOfDay());
    assertEquals(expected, scheduler.generate(officer));
    assertEquals(0, scheduler.generate(officer));
    assertEquals(
        0,
        p.db()
            .queryForObject(
                "select count(*) from sessions a join sessions b on a.id<b.id and"
                    + " a.starts_at<b.ends_at and a.ends_at>b.starts_at join programs pa on"
                    + " pa.id=a.program_id join programs pb on pb.id=b.program_id where"
                    + " a.starts_at>=? and (a.room=b.room or pa.instructor_id=pb.instructor_id)",
                Integer.class,
                next.atStartOfDay()));
  }

  @Test
  void completionRequiresAttendanceAndAssessment() {
    long id =
        p.db()
            .queryForObject(
                "select id from enrollments where participant_id=1 and program_id=1", Long.class);
    assertThrows(Exception.class, () -> p.enrollmentAction(officer, id, "complete"));
    p.db().update("update programs set duration=1 where id=1");
    LocalDateTime s = LocalDate.now().minusDays(15).atTime(9, 0);
    p.schedule(officer, 1, s, s.plusHours(1), "Workshop");
    long sid =
        p.db()
            .queryForObject(
                "select id from sessions where program_id=1 and starts_at=?", Long.class, s);
    p.attendance(instructor, sid, 1, true, "Demonstrated safe measuring");
    assertThrows(Exception.class, () -> p.enrollmentAction(officer, id, "complete"));
    assertEquals(100, p.assess(participant, 1, "A"));
    p.enrollmentAction(instructor, id, "complete");
    assertEquals(
        1,
        p.db()
            .queryForObject(
                "select count(*) from certificates where enrollment_id=?", Integer.class, id));
    assertThrows(Exception.class, () -> p.enrollmentAction(instructor, id, "complete"));
  }

  @Test
  void attendanceCannotBeRecordedForFutureSession() {
    LocalDateTime s = LocalDate.now().plusMonths(3).atTime(9, 0);
    p.schedule(officer, 1, s, s.plusHours(1), "Workshop");
    long sid =
        p.db()
            .queryForObject(
                "select id from sessions where program_id=1 and starts_at=?", Long.class, s);
    assertThrows(Exception.class, () -> p.attendance(instructor, sid, 1, true, ""));
  }

  @Test
  void instructorCannotManageAnotherProgram() {
    assertThrows(Exception.class, () -> p.manageProgram(instructor, 2));
    assertThrows(Exception.class, () -> p.participantAccess(instructor, 2));
  }

  @Test
  void participantCannotReadOtherProfiles() throws Exception {
    mvc.perform(get("/participants/2").with(user(participant.email()).roles("PARTICIPANT")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin").with(user(participant.email()).roles("PARTICIPANT")))
        .andExpect(status().isForbidden());
  }

  @Test
  void csrfRequiredAndLoginProtected() throws Exception {
    mvc.perform(get("/dashboard")).andExpect(status().is3xxRedirection());
    mvc.perform(
            post("/feedback")
                .with(user(participant.email()).roles("PARTICIPANT"))
                .param("kind", "FEEDBACK")
                .param("message", "Useful lesson"))
        .andExpect(status().isForbidden());
  }

  @Test
  void privateFeedbackScoped() throws Exception {
    p.sendFeedback(officer, "SUPPORT", "private staff note");
    mvc.perform(get("/feedback").with(user(participant.email()).roles("PARTICIPANT")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private staff note"))));
  }

  @Test
  void publicVerificationDoesNotRevealIdentity() throws Exception {
    var id = p.one("select id from certificates limit 1").get("id");
    mvc.perform(get("/verify/" + id))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Ravi Kumar"))));
    mvc.perform(get("/verify/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  @Test
  void programsEscapeUserContent() throws Exception {
    p.db().update("update programs set title=? where id=1", "<script>alert(1)</script>");
    mvc.perform(get("/programs").with(user(officer.email()).roles("OFFICER")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")));
  }

  @Test
  void projectProgressCannotExceedTarget() {
    assertThrows(Exception.class, () -> p.projectProgress(officer, 1, 100));
    p.projectProgress(officer, 1, 24);
    assertEquals("COMPLETED", p.one("select status from projects where id=1").get("status"));
    assertThrows(Exception.class, () -> p.projectProgress(participant, 2, 1));
  }

  @Test
  void rolePagesRender() throws Exception {
    for (String path :
        List.of(
            "/dashboard",
            "/programs",
            "/programs/new",
            "/programs/1",
            "/participants",
            "/participants/1",
            "/enrollments",
            "/schedule",
            "/sessions/1",
            "/progress",
            "/projects",
            "/feedback",
            "/notifications",
            "/reports",
            "/portfolio/1"))
      mvc.perform(get(path).with(user(officer.email()).roles("OFFICER")))
          .andExpect(status().isOk());
    mvc.perform(get("/admin").with(user("admin@reconnect.local").roles("ADMIN")))
        .andExpect(status().isOk());
    for (String path :
        List.of("/dashboard", "/programs/1", "/progress", "/projects", "/enrollments"))
      mvc.perform(get(path).with(user(participant.email()).roles("PARTICIPANT")))
          .andExpect(status().isOk());
  }

  @Test
  void semanticEngineLoadsAndRanksRelevantText() {
    assertTrue(ml.semantic(), "Download models before running the ML acceptance test");
    assertTrue(
        ml.similarity(
                "Repairing wooden furniture and building cabinets",
                "Carpentry, furniture making, woodworking")
            > ml.similarity(
                "Repairing wooden furniture and building cabinets",
                "Reading novels and discussing poetry"));
    assertEquals(16, CareerData.items().size());
  }

  @Test
  void recommendationsRenderWithoutExistingEnrollments() throws Exception {
    mvc.perform(
            get("/recommendations?participantId=1")
                .with(user(participant.email()).roles("PARTICIPANT")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("MiniLM")));
  }

  @Test
  void evaluateAuthoredMatchingCases() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var cases =
        mapper.readValue(
            java.nio.file.Path.of("scripts/evaluation_cases.json").toFile(),
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
    int semanticTop1 = 0, keywordTop1 = 0, semanticTop5 = 0;
    var programs = p.programs("", "");
    var lines = new ArrayList<String>();
    lines.add("query,expected,semantic_top1,keyword_top1,expected_in_semantic_top5");
    for (var c : cases) {
      var profile = new HashMap<String, Object>();
      profile.put("skills", c.get("query"));
      profile.put("interests", "");
      profile.put("goals", "");
      var ranked = ml.rank(profile, programs);
      String top = ranked.getFirst().get("title").toString();
      String base =
          programs.stream()
              .max(
                  Comparator.comparingDouble(
                      x ->
                          ml.keyword(
                              c.get("query"),
                              x.get("title") + " " + x.get("description") + " " + x.get("skills"))))
              .orElseThrow()
              .get("title")
              .toString();
      boolean hit = ranked.stream().anyMatch(x -> x.get("title").equals(c.get("expected")));
      if (top.equals(c.get("expected"))) semanticTop1++;
      if (base.equals(c.get("expected"))) keywordTop1++;
      if (hit) semanticTop5++;
      lines.add(
          "\""
              + c.get("query")
              + "\",\""
              + c.get("expected")
              + "\",\""
              + top
              + "\",\""
              + base
              + "\","
              + hit);
    }
    java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
    java.nio.file.Files.write(java.nio.file.Path.of("target/recommendation-evaluation.csv"), lines);
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("target/recommendation-evaluation.txt"),
        "Authored smoke evaluation, not independent real-world validation. Cases="
            + cases.size()
            + "; semantic top1="
            + semanticTop1
            + "; keyword top1="
            + keywordTop1
            + "; semantic hit@5="
            + semanticTop5
            + "\n");
    assertTrue(
        semanticTop5 >= 6,
        "Relevant programs should be discoverable in this small authored smoke set");
  }
}
