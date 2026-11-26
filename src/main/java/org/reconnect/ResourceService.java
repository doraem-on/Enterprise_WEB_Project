package org.reconnect;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceService {
  private final Platform p;

  public ResourceService(Platform p) {
    this.p = p;
  }

  @Transactional
  public long create(
      Platform.Actor a,
      String name,
      String kind,
      int quantity,
      String unit,
      String location,
      int reorder) {
    p.role(a, "ADMIN", "OFFICER");
    p.require(Set.of("MATERIAL", "EQUIPMENT").contains(kind), "Invalid resource type");
    p.require(quantity >= 0 && reorder >= 0, "Quantities must not be negative");
    long id =
        p.db()
            .queryForObject(
                "insert into resources(name,kind,quantity,unit,location,reorder_level)"
                    + " values(?,?,?,?,?,?) returning id",
                Long.class,
                Platform.required(name, 160),
                kind,
                quantity,
                Platform.required(unit, 40),
                Platform.required(location, 100),
                reorder);
    if (quantity > 0)
      p.db()
          .update(
              "insert into resource_transactions(resource_id,actor_id,change,note)"
                  + " values(?,?,?,'Opening stock')",
              id,
              a.id(),
              quantity);
    p.audit(a, "Resource created", name);
    return id;
  }

  @Transactional
  public void stock(Platform.Actor a, long id, int change, Long project, String note) {
    p.role(a, "ADMIN", "OFFICER");
    var r = p.one("select * from resources where id=? for update", id);
    p.require(change != 0, "Quantity must not be zero");
    if (change < 0) {
      p.require(
          r.get("kind").equals("MATERIAL"),
          "Equipment must be reserved and returned, not consumed");
      p.require(project != null, "Choose the project consuming these materials");
      p.one("select id from projects where id=? and status='ACTIVE'", project);
    }
    p.require(
        (long) change + Platform.num(r, "quantity") >= 0
            && (long) change + Platform.num(r, "quantity") <= 1000000,
        "Insufficient stock or excessive quantity");
    p.db().update("update resources set quantity=quantity+? where id=?", change, id);
    p.db()
        .update(
            "insert into resource_transactions(resource_id,project_id,actor_id,change,note)"
                + " values(?,?,?,?,?)",
            id,
            project,
            a.id(),
            change,
            Platform.required(note, 1000));
    p.audit(a, "Stock adjusted", "Resource " + id + ", change " + change);
  }

  public void capacity(
      long resource, long ignoredSession, int requested, LocalDateTime start, LocalDateTime end) {
    var r = p.one("select * from resources where id=? for update", resource);
    p.require(r.get("kind").equals("EQUIPMENT"), "Only equipment can be reserved");
    TreeMap<LocalDateTime, Integer> events = new TreeMap<>();
    events.merge(start, requested, Integer::sum);
    events.merge(end, -requested, Integer::sum);
    for (var e :
        p.rows(
            "select er.quantity,s.starts_at,s.ends_at from equipment_reservations er join sessions"
                + " s on s.id=er.session_id where er.resource_id=? and er.session_id<>? and"
                + " er.returned_at is null and s.starts_at<? and s.ends_at>?",
            resource,
            ignoredSession,
            end,
            start)) {
      LocalDateTime from = ((java.sql.Timestamp) e.get("starts_at")).toLocalDateTime(),
          to = ((java.sql.Timestamp) e.get("ends_at")).toLocalDateTime();
      events.merge(
          from.isBefore(start) ? start : from, (int) Platform.num(e, "quantity"), Integer::sum);
      events.merge(to.isAfter(end) ? end : to, -(int) Platform.num(e, "quantity"), Integer::sum);
    }
    int used = 0;
    for (int delta : events.values()) {
      used += delta;
      p.require(
          used <= Platform.num(r, "quantity"), "Equipment is already booked during this session");
    }
  }

  @Transactional
  public void reserve(Platform.Actor a, long resource, long session, int quantity) {
    p.role(a, "ADMIN", "OFFICER", "INSTRUCTOR");
    p.db().queryForObject("select pg_advisory_xact_lock(846210)", Object.class);
    var s = p.one("select * from sessions where id=?", session);
    p.manageProgram(a, Platform.num(s, "program_id"));
    p.require(quantity > 0, "Reserve at least one unit");
    var start = ((java.sql.Timestamp) s.get("starts_at")).toLocalDateTime();
    var end = ((java.sql.Timestamp) s.get("ends_at")).toLocalDateTime();
    p.require(end.isAfter(LocalDateTime.now()), "Cannot reserve equipment for a finished session");
    capacity(resource, session, quantity, start, end);
    p.db()
        .update(
            "insert into equipment_reservations(resource_id,session_id,quantity,created_by)"
                + " values(?,?,?,?) on conflict(resource_id,session_id) do update set"
                + " quantity=excluded.quantity,created_by=excluded.created_by,returned_at=null",
            resource,
            session,
            quantity,
            a.id());
    p.audit(a, "Equipment reserved", "Resource " + resource + ", session " + session);
  }

  @Transactional
  public void release(Platform.Actor a, long id) {
    var r =
        p.one(
            "select er.*,s.program_id from equipment_reservations er join sessions s on"
                + " s.id=er.session_id where er.id=? for update of er",
            id);
    p.manageProgram(a, Platform.num(r, "program_id"));
    p.require(r.get("returned_at") == null, "This reservation is already released");
    p.db().update("update equipment_reservations set returned_at=current_timestamp where id=?", id);
    p.audit(a, "Equipment released", "Reservation " + id);
  }
}
