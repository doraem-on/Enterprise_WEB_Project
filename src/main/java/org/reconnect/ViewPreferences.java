package org.reconnect;

import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@ControllerAdvice
public class ViewPreferences {
  private final Platform p;
  private final boolean demo;

  public ViewPreferences(Platform p, @Value("${reconnect.demo}") boolean demo) {
    this.p = p;
    this.demo = demo;
  }

  public static class Text {
    private final String language;
    private final Properties messages = new Properties();

    Text(String language) {
      this.language = language;
      if (!language.equals("en"))
        try (var stream =
            ViewPreferences.class.getResourceAsStream("/i18n/" + language + ".properties")) {
          if (stream != null) messages.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
    }

    public String getLanguage() {
      return language;
    }

    public String t(String key) {
      return key == null ? "" : messages.getProperty(key, key);
    }
  }

  @ModelAttribute
  public void common(HttpServletRequest request, Principal principal, Model m) {
    String supplied = request.getParameter("lang");
    if (supplied != null && Set.of("en", "hi", "pa").contains(supplied))
      request.getSession().setAttribute("uiLanguage", supplied);
    Object current = request.getSession().getAttribute("uiLanguage");
    String lang = current == null ? "en" : current.toString();
    m.addAttribute("ui", new Text(lang));
    m.addAttribute("currentPath", request.getRequestURI());
    m.addAttribute("demo", demo);
    if (principal != null) {
      var a = p.actor(principal.getName());
      m.addAttribute("actor", a);
      m.addAttribute("isStaff", a.staff());
      m.addAttribute("isParticipant", a.role().equals("PARTICIPANT"));
      m.addAttribute("isPartner", a.role().equals("NGO"));
      m.addAttribute(
          "unread",
          p.db()
              .queryForObject(
                  "select count(*) from notifications where account_id=? and not seen",
                  Long.class,
                  a.id()));
    }
  }
}
