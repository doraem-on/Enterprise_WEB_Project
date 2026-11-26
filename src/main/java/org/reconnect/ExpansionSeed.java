package org.reconnect;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(2)
public class ExpansionSeed implements CommandLineRunner {
  private final Platform p;
  private final PasswordEncoder encoder;
  private final boolean demo;
  private final String password;

  public ExpansionSeed(
      Platform p,
      PasswordEncoder encoder,
      @Value("${reconnect.demo}") boolean demo,
      @Value("${reconnect.demo-password}") String password) {
    this.p = p;
    this.encoder = encoder;
    this.demo = demo;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(String... args) {
    if (!demo) return;
    var officers = p.rows("select id from accounts where role='OFFICER' order by id limit 1");
    if (officers.isEmpty()) return;
    long officer = Platform.num(officers.getFirst(), "id");
    for (var program : p.rows("select * from programs where lesson<>''")) {
      long pid = Platform.num(program, "id");
      if (p.db()
              .queryForObject(
                  "select count(*) from lessons where program_id=? and position=2", Long.class, pid)
          == 0) {
        long lesson =
            p.db()
                .queryForObject(
                    "insert into lessons(program_id,title,body,position) values(?,?,?,2) returning"
                        + " id",
                    Long.class,
                    pid,
                    "Practice, reflect, and improve",
                    "Apply the skill from your first lesson in a supervised session. Plan the task,"
                        + " follow the instructor's agreed safety process, and describe one thing"
                        + " you improved. Ask for support or an adaptation whenever needed. Respect"
                        + " everyone's pace and record what you practised.");
        p.db()
            .update(
                "insert into quiz_questions(lesson_id,question,option_a,option_b,option_c,answer)"
                    + " values(?,'What should you do when you need help or an adaptation?','Ask the"
                    + " instructor and agree on an approach','Hide the difficulty','Leave without"
                    + " telling anyone','A'),(?,'What makes a useful reflection?','Describe what"
                    + " you practised and what you would improve','Copy another person without"
                    + " thinking','Skip feedback entirely','A')",
                lesson,
                lesson);
      }
      if (p.db()
              .queryForObject(
                  "select count(*) from assignments where program_id=?", Long.class, pid)
          == 0) {
        p.db()
            .update(
                "insert into assignments(program_id,title,instructions,kind,rubric)"
                    + " values(?,'Learning reflection','Describe one activity you practised, the"
                    + " steps you followed, and one improvement you want to make.','WRITTEN','Clear"
                    + " description (40), reflection on learning (40), a practical next step"
                    + " (20)')",
                pid);
        if (program.get("category").equals("Vocational"))
          p.db()
              .update(
                  "insert into assignments(program_id,title,instructions,kind,rubric)"
                      + " values(?,'Supervised practical demonstration','Arrange a demonstration"
                      + " with your instructor. Submit a short description of the task and how you"
                      + " prepared; the instructor records observations before marking"
                      + " it.','PRACTICAL','Preparation and agreed safety process (40), task"
                      + " technique (40), reflection and care of materials (20)')",
                  pid);
      }
    }
    // Historical certificates remain valid; new curriculum does not fabricate progress.
    if (p.db()
            .queryForObject(
                "select count(*) from accounts where email='partner@reconnect.local'", Long.class)
        == 0) {
      long ngo =
          p.db()
              .queryForObject(
                  "insert into accounts(email,password,name,role)"
                      + " values('partner@reconnect.local',?,'Community Skills Foundation','NGO')"
                      + " returning id",
                  Long.class,
                  encoder.encode(password));
      p.db()
          .update(
              "insert into"
                  + " organizations(account_id,name,mission,contact,status,review_note,reviewed_by)"
                  + " values(?,'Community Skills Foundation','Fictional demonstration partner"
                  + " offering creative and vocational"
                  + " workshops.','partner@reconnect.local','APPROVED','Seeded fictional"
                  + " organization for the local demonstration.',?)",
              ngo,
              officer);
    }
    if (p.db().queryForObject("select count(*) from resources", Long.class) == 0) {
      p.db()
          .update(
              "insert into resources(name,kind,quantity,unit,location,reorder_level) values('Timber"
                  + " boards','MATERIAL',60,'boards','Workshop store',12),('Cotton"
                  + " fabric','MATERIAL',80,'metres','Textile store',15),('Supervised tool"
                  + " kits','EQUIPMENT',4,'kits','Workshop A',1),('Learning"
                  + " tablets','EQUIPMENT',8,'tablets','Learning room',2)");
      p.db()
          .update(
              "insert into resource_transactions(resource_id,actor_id,change,note) select"
                  + " id,?,quantity,'Fictional opening inventory' from resources",
              officer);
    }
    var ravi = p.rows("select id from participants where reference='RC-2026001'");
    if (!ravi.isEmpty()
        && p.db()
                .queryForObject(
                    "select count(*) from goals where participant_id=?",
                    Long.class,
                    ravi.getFirst().get("id"))
            == 0) {
      long goal =
          p.db()
              .queryForObject(
                  "insert into goals(participant_id,title,description,review_date,created_by)"
                      + " values(?,'Build a practical skills portfolio','Discuss a learning path,"
                      + " practise one workshop task, and reflect on the result.',?,?) returning"
                      + " id",
                  Long.class,
                  ravi.getFirst().get("id"),
                  LocalDate.now().plusDays(30),
                  officer);
      p.db()
          .update(
              "insert into milestones(goal_id,title,due_date) values(?,'Agree on a personal"
                  + " learning goal',?),(?,'Finish the first learning module',?),(?,'Review"
                  + " progress with the rehabilitation officer',?)",
              goal,
              LocalDate.now().plusDays(3),
              goal,
              LocalDate.now().plusDays(14),
              goal,
              LocalDate.now().plusDays(28));
      p.db()
          .update(
              "insert into goal_events(goal_id,actor_id,note) values(?,?,'Demo plan proposed;"
                  + " awaiting participant agreement.')",
              goal,
              officer);
    }
  }
}
