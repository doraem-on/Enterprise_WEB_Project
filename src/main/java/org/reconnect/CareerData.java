package org.reconnect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public final class CareerData {
  private static final List<Map<String, Object>> ITEMS = load();

  private static List<Map<String, Object>> load() {
    try (var stream = CareerData.class.getResourceAsStream("/data/careers.json")) {
      return new ObjectMapper()
          .readValue(stream, new TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Career catalog could not load", e);
    }
  }

  public static List<Map<String, Object>> items() {
    return ITEMS;
  }
}
