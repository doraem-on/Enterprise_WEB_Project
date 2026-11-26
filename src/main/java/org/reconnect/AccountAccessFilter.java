package org.reconnect;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Recheck live account state: deactivation immediately invalidates an existing session. */
public class AccountAccessFilter extends OncePerRequestFilter {
  private final JdbcTemplate db;

  public AccountAccessFilter(JdbcTemplate db) {
    this.db = db;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
      var accounts =
          db.queryForList("select role,active from accounts where email=?", auth.getName());
      if (accounts.isEmpty() || !Boolean.TRUE.equals(accounts.getFirst().get("active"))) {
        SecurityContextHolder.clearContext();
        if (req.getSession(false) != null) req.getSession(false).invalidate();
        res.sendRedirect("/login?disabled");
        return;
      }
      if (accounts.getFirst().get("role").equals("NGO")) {
        String uri = req.getRequestURI();
        boolean allowed =
            uri.equals("/")
                || uri.equals("/dashboard")
                || uri.equals("/login")
                || uri.equals("/logout")
                || uri.equals("/partners")
                || uri.startsWith("/partners/")
                || uri.startsWith("/notifications")
                || uri.equals("/feedback")
                || uri.equals("/app.css")
                || uri.equals("/app.js")
                || uri.equals("/favicon.svg")
                || uri.equals("/error");
        if (!allowed) {
          res.sendError(403);
          return;
        }
      }
    }
    chain.doFilter(req, res);
  }
}
