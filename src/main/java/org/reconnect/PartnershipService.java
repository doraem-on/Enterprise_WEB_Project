package org.reconnect;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartnershipService {
  private final Platform p;

  public PartnershipService(Platform p) {
    this.p = p;
  }

  @Transactional
  public void apply(Platform.Actor a, String name, String mission, String contact) {
    p.role(a, "NGO");
    p.require(contact.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"), "Enter a valid contact email");
    p.require(
        p.rows("select id from organizations where account_id=?", a.id()).isEmpty(),
        "An organization application already exists");
    p.db()
        .update(
            "insert into organizations(account_id,name,mission,contact) values(?,?,?,?)",
            a.id(),
            Platform.required(name, 180),
            Platform.required(mission, 4000),
            Platform.required(contact, 180));
    p.audit(a, "Partner applied", name);
  }

  @Transactional
  public void reviewOrganization(Platform.Actor a, long id, String status, String reason) {
    p.role(a, "ADMIN", "OFFICER");
    p.require(
        Set.of("APPROVED", "REJECTED", "SUSPENDED").contains(status), "Choose a review decision");
    var o = p.one("select * from organizations where id=? for update", id);
    p.db()
        .update(
            "update organizations set status=?,review_note=?,reviewed_by=? where id=?",
            status,
            Platform.required(reason, 2000),
            a.id(),
            id);
    p.db()
        .update(
            "insert into notifications(account_id,message) values(?,?)",
            o.get("account_id"),
            "Your organization review: " + status.toLowerCase());
    p.audit(a, "Partner reviewed", "Organization " + id + ": " + status);
  }

  @Transactional
  public long propose(
      Platform.Actor a,
      String title,
      String kind,
      String description,
      String skills,
      int capacity) {
    p.role(a, "NGO");
    var o = p.one("select * from organizations where account_id=? for update", a.id());
    p.require(
        o.get("status").equals("APPROVED"),
        "Organization approval is required before submitting proposals");
    p.require(Set.of("WORKSHOP", "PROJECT").contains(kind), "Choose workshop or project");
    p.require(capacity >= 1 && capacity <= 100, "Capacity/target must be between 1 and 100");
    long id =
        p.db()
            .queryForObject(
                "insert into proposals(organization_id,title,kind,description,skills,capacity)"
                    + " values(?,?,?,?,?,?) returning id",
                Long.class,
                o.get("id"),
                Platform.required(title, 160),
                kind,
                Platform.required(description, 3000),
                Platform.required(skills, 2000),
                capacity);
    p.audit(a, "Partner proposal submitted", "Proposal " + id);
    return id;
  }

  @Transactional
  public void reviewProposal(
      Platform.Actor a, long id, boolean approve, String reason, Long instructor, String location) {
    p.role(a, "ADMIN", "OFFICER");
    var x =
        p.one(
            "select p.*,o.status organization_status,o.account_id,o.name organization from"
                + " proposals p join organizations o on o.id=p.organization_id where p.id=? for"
                + " update of p,o",
            id);
    p.require(x.get("status").equals("PENDING"), "This proposal has already been reviewed");
    reason = Platform.required(reason, 2000);
    Long program = null, project = null;
    if (approve) {
      p.require(x.get("organization_status").equals("APPROVED"), "Organization is not approved");
      if (x.get("kind").equals("WORKSHOP")) {
        p.require(instructor != null, "Assign an internal instructor to the workshop");
        p.one("select id from accounts where id=? and role='INSTRUCTOR' and active", instructor);
        program =
            p.db()
                .queryForObject(
                    "insert into"
                        + " programs(title,category,description,skills,prerequisites,instructor_id,capacity,duration,location,lesson,question,option_a,option_b,option_c,answer,status)"
                        + " values(?,'Education',?,?,'Staff review"
                        + " required',?,?,1,?,'','','','','','A','ARCHIVED') returning id",
                    Long.class,
                    x.get("title"),
                    x.get("description"),
                    x.get("skills"),
                    instructor,
                    x.get("capacity"),
                    Platform.required(location, 100));
      } else
        project =
            p.db()
                .queryForObject(
                    "insert into projects(title,description,beneficiary,target,unit,skills)"
                        + " values(?,?,?,?, 'deliverables',?) returning id",
                    Long.class,
                    x.get("title"),
                    x.get("description"),
                    x.get("organization"),
                    x.get("capacity"),
                    x.get("skills"));
    }
    p.db()
        .update(
            "update proposals set status=?,review_note=?,reviewed_by=?,program_id=?,project_id=?"
                + " where id=?",
            approve ? "APPROVED" : "REJECTED",
            reason,
            a.id(),
            program,
            project,
            id);
    p.db()
        .update(
            "insert into notifications(account_id,message) values(?,?)",
            x.get("account_id"),
            "Proposal reviewed: " + x.get("title"));
    p.audit(a, "Partner proposal reviewed", "Proposal " + id);
  }
}
