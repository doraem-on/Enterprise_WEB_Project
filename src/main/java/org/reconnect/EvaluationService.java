package org.reconnect;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationService {
  private final Platform p;
  private final RecommendationEngine ml;

  public EvaluationService(Platform p, RecommendationEngine ml) {
    this.p = p;
    this.ml = ml;
  }

  @Transactional
  public void rate(Platform.Actor a, long program, int rating, String note, boolean consent) {
    p.role(a, "PARTICIPANT");
    p.program(program);
    p.require(consent, "Feedback is optional; consent is required to save it");
    p.require(rating >= 1 && rating <= 5, "Choose a rating from 1 to 5");
    p.db()
        .update(
            "insert into recommendation_feedback(participant_id,program_id,rating,note,model)"
                + " values(?,?,?,?,?) on conflict(participant_id,program_id) do update set"
                + " rating=excluded.rating,note=excluded.note,model=excluded.model,created_at=current_timestamp",
            p.ownParticipant(a),
            program,
            rating,
            Platform.text(note, 1000),
            ml.status());
  }

  @Transactional
  public void withdraw(Platform.Actor a, long program) {
    p.role(a, "PARTICIPANT");
    p.db()
        .update(
            "delete from recommendation_feedback where participant_id=? and program_id=?",
            p.ownParticipant(a),
            program);
  }

  @Transactional
  public long addCase(Platform.Actor a, String query, long expected, String rationale) {
    p.role(a, "ADMIN", "OFFICER", "INSTRUCTOR");
    p.program(expected);
    long id =
        p.db()
            .queryForObject(
                "insert into evaluation_cases(query,expected_program_id,rationale,author_id)"
                    + " values(?,?,?,?) returning id",
                Long.class,
                Platform.required(query, 2000),
                expected,
                Platform.required(rationale, 2000),
                a.id());
    p.audit(a, "Evaluation case drafted", "Case " + id);
    return id;
  }

  @Transactional
  public void reviewCase(Platform.Actor a, long id) {
    p.role(a, "ADMIN", "OFFICER", "INSTRUCTOR");
    var c = p.one("select * from evaluation_cases where id=? for update", id);
    p.require(
        Platform.num(c, "author_id") != a.id(), "A different reviewer must validate this label");
    p.require(c.get("reviewer_id") == null, "This label is already reviewed");
    p.db()
        .update(
            "update evaluation_cases set reviewer_id=?,reviewed_at=current_timestamp where id=?",
            a.id(),
            id);
    p.audit(a, "Evaluation label reviewed", "Case " + id);
  }

  @Transactional
  public long run(Platform.Actor a) {
    p.role(a, "ADMIN", "OFFICER");
    var cases =
        p.rows(
            "select e.* from evaluation_cases e join programs p on p.id=e.expected_program_id where"
                + " reviewer_id is not null and p.status='ACTIVE' order by e.id");
    p.require(!cases.isEmpty(), "Add cases and obtain a second human review before evaluation");
    var catalog = p.programs("", "");
    int top1 = 0, hit5 = 0, baseline = 0;
    List<Map<String, Object>> results = new ArrayList<>();
    for (var c : cases) {
      String query = c.get("query").toString();
      var profile = Map.<String, Object>of("skills", query, "interests", "", "goals", "");
      var ranked = ml.rank(profile, catalog);
      long predicted = Platform.num(ranked.getFirst(), "id"),
          expected = Platform.num(c, "expected_program_id");
      boolean hit = ranked.stream().anyMatch(x -> Platform.num(x, "id") == expected);
      long base =
          Platform.num(
              catalog.stream()
                  .max(
                      Comparator.comparingDouble(
                          x ->
                              ml.keyword(
                                  query,
                                  x.get("title")
                                      + " "
                                      + x.get("description")
                                      + " "
                                      + x.get("skills"))))
                  .orElseThrow(),
              "id");
      if (predicted == expected) top1++;
      if (hit) hit5++;
      if (base == expected) baseline++;
      results.add(
          Map.of(
              "id",
              c.get("id"),
              "predicted",
              predicted,
              "expected",
              expected,
              "query",
              query,
              "hit",
              hit));
    }
    long id =
        p.db()
            .queryForObject(
                "insert into evaluation_runs(actor_id,model,case_count,top1,hit5,baseline_top1)"
                    + " values(?,?,?,?,?,?) returning id",
                Long.class,
                a.id(),
                ml.status(),
                cases.size(),
                top1,
                hit5,
                baseline);
    for (var r : results)
      p.db()
          .update(
              "insert into"
                  + " evaluation_results(run_id,case_id,predicted_program_id,expected_program_id,query_snapshot,top5_hit)"
                  + " values(?,?,?,?,?,?)",
              id,
              r.get("id"),
              r.get("predicted"),
              r.get("expected"),
              r.get("query"),
              r.get("hit"));
    p.audit(a, "Recommendation evaluation run", "Run " + id);
    return id;
  }
}
