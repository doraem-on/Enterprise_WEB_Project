package org.reconnect;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.solution.*;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.*;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Scheduling {
  final Platform p;

  public Scheduling(Platform p) {
    this.p = p;
  }

  public record Slot(LocalDateTime start, String room) {}

  @PlanningEntity
  public static class Assignment {
    @PlanningId public Long id;
    public Long instructor;
    public Set<Long> participants = new HashSet<>();

    @ValueRangeProvider(id = "choices")
    public List<Slot> choices;

    @PlanningVariable(valueRangeProviderRefs = "choices")
    public Slot slot;

    public Assignment() {}
  }

  @PlanningSolution
  public static class Plan {
    @PlanningEntityCollectionProperty public List<Assignment> assignments;
    @PlanningScore public HardSoftScore score;

    public Plan() {}

    public Plan(List<Assignment> a) {
      assignments = a;
    }
  }

  public static class Rules implements ConstraintProvider {
    public Constraint[] defineConstraints(ConstraintFactory f) {
      return new Constraint[] {
        f.forEachUniquePair(Assignment.class)
            .filter(
                (a, b) ->
                    a.slot.start().equals(b.slot.start())
                        && (a.slot.room().equals(b.slot.room())
                            || Objects.equals(a.instructor, b.instructor)
                            || !Collections.disjoint(a.participants, b.participants)))
            .penalize(HardSoftScore.ONE_HARD)
            .asConstraint("No overlapping room, instructor or participant"),
        f.forEach(Assignment.class)
            .penalize(
                HardSoftScore.ONE_SOFT,
                a -> a.slot.start().getDayOfWeek().getValue() * 10 + a.slot.start().getHour())
            .asConstraint("Prefer earlier sessions")
      };
    }
  }

  @Transactional
  public int generate(Platform.Actor a) {
    p.role(a, "ADMIN", "OFFICER");
    p.db().queryForObject("select pg_advisory_xact_lock(846210)", Object.class);
    var assignments = new ArrayList<Assignment>();
    LocalDate next =
        LocalDate.now()
            .plusWeeks(1)
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    for (var program : p.programs("", "")) {
      long id = Platform.num(program, "id");
      if (p.db()
              .queryForObject(
                  "select count(*) from sessions where program_id=? and starts_at>=? and"
                      + " starts_at<?",
                  Long.class,
                  id,
                  next.atStartOfDay(),
                  next.plusDays(5).atStartOfDay())
          > 0) continue;
      Assignment e = new Assignment();
      e.id = id;
      e.instructor = Platform.num(program, "instructor_id");
      e.choices = new ArrayList<>();
      for (var r :
          p.rows(
              "select participant_id from enrollments where program_id=? and status='ENROLLED'",
              id)) e.participants.add(Platform.num(r, "participant_id"));
      for (int day = 0; day < 5; day++)
        for (int hour : new int[] {9, 11, 14}) {
          LocalDateTime start = next.plusDays(day).atTime(hour, 0);
          String room = (String) program.get("location");
          long conflicts =
              p.db()
                  .queryForObject(
                      "select count(*) from sessions s join programs pr on pr.id=s.program_id where"
                          + " s.starts_at<? and s.ends_at>? and (lower(s.room)=lower(?) or"
                          + " pr.instructor_id=? or exists(select 1 from enrollments e1 join"
                          + " enrollments e2 on e1.participant_id=e2.participant_id where"
                          + " e1.program_id=? and e2.program_id=s.program_id and"
                          + " e1.status='ENROLLED' and e2.status='ENROLLED'))",
                      Long.class,
                      start.plusHours(1),
                      start,
                      room,
                      e.instructor,
                      id);
          if (conflicts == 0) e.choices.add(new Slot(start, room));
        }
      p.require(!e.choices.isEmpty(), "No free timeslots for " + program.get("title"));
      assignments.add(e);
    }
    if (assignments.isEmpty()) return 0;
    var config =
        new SolverConfig()
            .withSolutionClass(Plan.class)
            .withEntityClasses(Assignment.class)
            .withConstraintProviderClass(Rules.class)
            .withTerminationSpentLimit(Duration.ofSeconds(2));
    Plan plan = SolverFactory.<Plan>create(config).buildSolver().solve(new Plan(assignments));
    p.require(
        plan.score.isFeasible(), "No conflict-free schedule found; schedule sessions individually");
    for (var e : plan.assignments)
      p.schedule(a, e.id, e.slot.start(), e.slot.start().plusHours(1), e.slot.room());
    return plan.assignments.size();
  }
}
