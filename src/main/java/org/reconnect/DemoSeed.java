package org.reconnect;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoSeed implements CommandLineRunner {
  final Platform p;
  final PasswordEncoder encoder;
  final boolean enabled;
  final String password;

  public DemoSeed(
      Platform p,
      PasswordEncoder encoder,
      @Value("${reconnect.demo}") boolean enabled,
      @Value("${reconnect.demo-password}") String password) {
    this.p = p;
    this.encoder = encoder;
    this.enabled = enabled;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(String... args) {
    if (!enabled || p.db().queryForObject("select count(*) from accounts", Long.class) > 0) return;
    String hash = encoder.encode(password);
    account("admin@reconnect.local", "Ananya Rao", "ADMIN", hash);
    account("officer@reconnect.local", "Dev Mehta", "OFFICER", hash);
    long instructor = account("instructor@reconnect.local", "Maya Thomas", "INSTRUCTOR", hash);
    long instructor2 = account("coach@reconnect.local", "Arjun Das", "INSTRUCTOR", hash);
    long participant = account("participant@reconnect.local", "Ravi Kumar", "PARTICIPANT", hash);
    String[][] people = {
      {
        "Ravi Kumar",
        "Secondary school",
        "Carpentry, furniture repair, measuring",
        "Woodworking, gardening, drawing",
        "Build practical skills for furniture making"
      },
      {
        "Aman Singh",
        "Primary school",
        "Gardening, planting, teamwork",
        "Horticulture, reading",
        "Learn sustainable farming"
      },
      {
        "Kabir Ali",
        "Secondary school",
        "Drawing, painting, design",
        "Art, teaching, reading",
        "Develop a creative portfolio"
      },
      {
        "Vikram Patel",
        "Secondary school",
        "Tailoring, sewing, measuring",
        "Textiles, entrepreneurship",
        "Learn garment making"
      },
      {
        "Neeraj Das",
        "Primary school",
        "Cooking, food preparation",
        "Nutrition, gardening",
        "Complete food safety training"
      },
      {
        "Suresh Rao",
        "Secondary school",
        "Arithmetic, bookkeeping",
        "Computers, budgeting",
        "Learn digital skills"
      },
      {
        "Imran Khan",
        "Primary school",
        "Teamwork, sports",
        "Fitness, reading",
        "Build a consistent activity routine"
      },
      {
        "Manoj Shah",
        "Secondary school",
        "Repair, hand tools",
        "Woodworking, design",
        "Complete a vocational certificate"
      },
      {
        "Deepak Roy",
        "Primary school",
        "Gardening, composting",
        "Nature, yoga",
        "Participate in community gardening"
      },
      {
        "Harish Paul",
        "Secondary school",
        "Writing, storytelling",
        "Reading, creative writing",
        "Improve communication skills"
      },
      {
        "Farhan Sheikh",
        "Secondary school",
        "Sewing, textiles",
        "Crafts, design",
        "Make useful products for the community"
      },
      {
        "Ajay Verma",
        "Primary school",
        "Teamwork, gardening",
        "Literacy, mathematics",
        "Improve reading and numeracy"
      }
    };
    int idx = 0;
    for (var x : people) {
      p.db()
          .update(
              "insert into participants(account_id,name,reference,education,skills,interests,goals)"
                  + " values(?,?,?,?,?,?,?)",
              idx == 0 ? participant : null,
              x[0],
              "RC-" + (2026001 + idx),
              x[1],
              x[2],
              x[3],
              x[4]);
      idx++;
    }
    String[][] programs = {
      {
        "Woodcraft & furniture repair",
        "Vocational",
        "Turn practical experience into confident craftsmanship. Learn safe measuring, joinery,"
            + " sanding, and restoring useful furniture.",
        "Carpentry, woodworking, measuring, furniture repair",
        "Basic tool-safety orientation",
        "Workshop A",
        "Measure twice before cutting. Secure the workpiece, use protective equipment, and ask your"
            + " instructor before operating tools.",
        "What should you do before cutting timber?",
        "Measure and secure the workpiece",
        "Skip the measurement",
        "Hold the timber loosely"
      },
      {
        "Grow together",
        "Vocational",
        "Explore seed starting, composting, water-wise gardening, and seasonal growing through a"
            + " shared garden.",
        "Gardening, horticulture, planting, composting",
        "",
        "Garden courtyard",
        "Compost combines suitable plant-based scraps with dry leaves. Turn it regularly and keep"
            + " it damp rather than waterlogged.",
        "Which material is suitable for plant compost?",
        "Dry leaves and vegetable scraps",
        "Plastic wrappers",
        "Glass pieces"
      },
      {
        "Everyday digital skills",
        "Education",
        "Build confidence using a computer: documents, spreadsheets, budgeting, and safe digital"
            + " habits.",
        "Computers, arithmetic, spreadsheets, budgeting",
        "Basic reading",
        "Learning room",
        "A spreadsheet formula starts with an equals sign. Use clear headings, check totals, and"
            + " avoid sharing passwords.",
        "How should you protect a password?",
        "Keep it private",
        "Write it on a public noticeboard",
        "Share it with everyone"
      },
      {
        "Art & expression",
        "Creative",
        "Make space for creativity through drawing, colour, visual storytelling, and collaborative"
            + " artwork.",
        "Drawing, painting, design, storytelling",
        "",
        "Creative studio",
        "A sketchbook is a place to experiment. Begin with simple shapes, observe light and shade,"
            + " and discuss your work respectfully.",
        "What supports a constructive art discussion?",
        "Specific respectful feedback",
        "Judging the artist personally",
        "Ignoring their intentions"
      },
      {
        "Movement & mindfulness",
        "Wellbeing",
        "A gentle, supervised routine of breathing, stretching, and reflection, adapted with staff"
            + " guidance.",
        "Mindfulness, breathing, stretching, reflection",
        "Staff confirms activity suitability",
        "Activity hall",
        "Move at a comfortable pace. Stop if an activity causes pain and speak to the instructor."
            + " Participation and adaptations are discussed together.",
        "What should you do if an exercise causes pain?",
        "Stop and tell the instructor",
        "Continue through the pain",
        "Hide it from staff"
      },
      {
        "Reading circle",
        "Education",
        "Read short stories, discuss ideas, and build everyday literacy in a supportive small"
            + " group.",
        "Reading, literacy, communication, writing",
        "",
        "Library",
        "Read a short passage, identify its main idea, and support your interpretation with an"
            + " example from the text.",
        "What helps explain your interpretation?",
        "An example from the text",
        "An unrelated statement",
        "Speaking over others"
      },
      {
        "Stitch a new chapter",
        "Vocational",
        "Learn accurate measuring, stitching, fabric care, and repair while making useful textile"
            + " items.",
        "Tailoring, sewing, textiles, measuring",
        "Safety orientation",
        "Textile studio",
        "Check measurements and seams before cutting fabric. Keep needles and tools in their"
            + " assigned storage and follow the workshop process.",
        "What comes before cutting fabric?",
        "Checking measurements",
        "Skipping the pattern",
        "Guessing every dimension"
      },
      {
        "Team games & fitness",
        "Physical",
        "Build teamwork and a steady physical activity routine through supervised, adaptable group"
            + " games.",
        "Sports, fitness, teamwork, communication",
        "Staff confirms activity suitability",
        "Sports courtyard",
        "Warm up gradually. Follow agreed rules and make space for different abilities. Tell the"
            + " instructor when you need an adaptation.",
        "What supports inclusive team activity?",
        "Adapting for different abilities",
        "Excluding beginners",
        "Ignoring agreed rules"
      }
    };
    int n = 0;
    for (var x : programs) {
      p.db()
          .update(
              "insert into"
                  + " programs(title,category,description,skills,prerequisites,instructor_id,capacity,duration,location,lesson,question,option_a,option_b,option_c)"
                  + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
              x[0],
              x[1],
              x[2],
              x[3],
              x[4],
              n % 2 == 0 ? instructor : instructor2,
              12 + n * 2,
              n == 5 ? 2 : 4,
              x[5],
              x[6],
              x[7],
              x[8],
              x[9],
              x[10]);
      n++;
    }
    for (int i = 1; i <= 12; i++) {
      int prog = 1 + (i - 1) % 8;
      p.db()
          .update(
              "insert into enrollments(participant_id,program_id,status) values(?,?,?)",
              i,
              prog,
              i > 9 ? "REQUESTED" : "ENROLLED");
    }
    p.db()
        .update(
            "insert into enrollments(participant_id,program_id,status) values(1,6,'COMPLETED')");
    for (int i = 1; i <= 8; i++) {
      LocalDateTime start = LocalDate.now().plusDays(i % 5).atTime(9 + (i % 3) * 2, 0);
      p.db()
          .update(
              "insert into sessions(program_id,starts_at,ends_at,room) values(?,?,?,?)",
              i,
              start,
              start.plusHours(1),
              programs[i - 1][5]);
    }
    for (int i = 1; i <= 2; i++) {
      LocalDateTime start = LocalDate.now().minusDays(i + 2).atTime(10, 0);
      Long sid =
          p.db()
              .queryForObject(
                  "insert into sessions(program_id,starts_at,ends_at,room) values(6,?,?,'Library')"
                      + " returning id",
                  Long.class,
                  start,
                  start.plusHours(1));
      p.db()
          .update(
              "insert into attendance(session_id,participant_id,present,feedback,recorded_by)"
                  + " values(?,1,true,'Participated thoughtfully in the reading discussion',?)",
              sid,
              instructor2);
    }
    p.db().update("insert into assessments(participant_id,program_id,score) values(1,6,100)");
    Long eid =
        p.db()
            .queryForObject(
                "select id from enrollments where participant_id=1 and program_id=6", Long.class);
    p.db()
        .update(
            "insert into certificates(id,enrollment_id,issued_by) values(?,?,?)",
            UUID.randomUUID(),
            eid,
            instructor2);
    p.db()
        .update(
            "insert into projects(title,description,beneficiary,target,completed,unit,skills)"
                + " values('A desk for every learner','Repair school desks through supervised,"
                + " voluntary workshop sessions. Each restored piece supports a welcoming"
                + " classroom.','Community learning centre',40,16,'desks','Carpentry, measuring,"
                + " repair'),('The community garden','Grow seasonal vegetables while practising"
                + " sustainable gardening and teamwork.','Local community kitchen',100,35,'kg"
                + " harvested','Gardening, composting'),('Books with a second life','Repair and"
                + " catalogue donated books for a community reading room.','Neighbourhood reading"
                + " room',120,72,'books','Reading, organisation, crafts')");
    p.db().update("insert into project_members values(1,1),(2,2),(3,3)");
    p.db()
        .update(
            "insert into notifications(account_id,message) values(?,'Welcome to ReConnect. Explore"
                + " your programs and build your next chapter.')",
            participant);
  }

  long account(String email, String name, String role, String hash) {
    return p.db()
        .queryForObject(
            "insert into accounts(email,name,role,password) values(?,?,?,?) returning id",
            Long.class,
            email,
            name,
            role,
            hash);
  }
}
