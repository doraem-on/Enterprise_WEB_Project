package org.reconnect;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RecommendationEngine {
  private final String directory;
  private OrtSession session;
  private HuggingFaceTokenizer tokenizer;
  private OrtEnvironment environment;
  private final Map<String, float[]> cache = new ConcurrentHashMap<>();
  private String status = "Keyword matching (model unavailable)";

  public RecommendationEngine(@Value("${reconnect.models}") String directory) {
    this.directory = directory;
  }

  @PostConstruct
  public void load() {
    try {
      Path root = Path.of(directory);
      if (!Files.exists(root.resolve("minilm.onnx"))) return;
      environment = OrtEnvironment.getEnvironment();
      try (var options = new OrtSession.SessionOptions()) {
        options.setIntraOpNumThreads(2);
        session = environment.createSession(root.resolve("minilm.onnx").toString(), options);
      }
      tokenizer =
          HuggingFaceTokenizer.newInstance(
              root.resolve("tokenizer.json"),
              Map.of("truncation", "true", "maxLength", "256", "padding", "false"));
      status = "MiniLM semantic matching · 384 dimensions";
    } catch (Exception | LinkageError e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Embedding model unavailable; using keyword matching: {}", e.getMessage());
    }
  }

  public String status() {
    return status;
  }

  public boolean semantic() {
    return tokenizer != null && session != null;
  }

  private float[] embed(String text) throws Exception {
    if (cache.containsKey(text)) return cache.get(text);
    var enc = tokenizer.encode(text);
    long[][] ids = {enc.getIds()}, mask = {enc.getAttentionMask()}, types = {enc.getTypeIds()};
    try (var a = OnnxTensor.createTensor(environment, ids);
        var b = OnnxTensor.createTensor(environment, mask);
        var c = OnnxTensor.createTensor(environment, types)) {
      var inputs = new HashMap<String, OnnxTensor>();
      inputs.put("input_ids", a);
      inputs.put("attention_mask", b);
      if (session.getInputNames().contains("token_type_ids")) inputs.put("token_type_ids", c);
      try (var result = session.run(inputs)) {
        float[][][] output = (float[][][]) result.get(0).getValue();
        float[] pooled = new float[output[0][0].length];
        int count = 0;
        for (int i = 0; i < output[0].length; i++)
          if (mask[0][i] != 0) {
            count++;
            for (int j = 0; j < pooled.length; j++) pooled[j] += output[0][i][j];
          }
        double norm = 0;
        for (int j = 0; j < pooled.length; j++) {
          pooled[j] /= Math.max(1, count);
          norm += pooled[j] * pooled[j];
        }
        for (int j = 0; j < pooled.length; j++)
          pooled[j] /= (float) Math.sqrt(Math.max(norm, 1e-12));
        if (cache.size() > 2000) cache.clear();
        cache.put(text, pooled);
        return pooled;
      }
    }
  }

  public static Set<String> words(String s) {
    Set<String> set = new HashSet<>();
    for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z]+")) if (w.length() > 2) set.add(w);
    set.removeAll(
        Set.of(
            "and",
            "the",
            "for",
            "with",
            "from",
            "that",
            "this",
            "into",
            "your",
            "you",
            "want",
            "learn",
            "goal",
            "interested",
            "skills",
            "through",
            "their",
            "have",
            "can",
            "are",
            "our",
            "will"));
    return set;
  }

  public double keyword(String a, String b) {
    var x = words(a);
    var y = words(b);
    long intersection = x.stream().filter(y::contains).count();
    return intersection / Math.sqrt(Math.max(1, x.size() * y.size()));
  }

  public double similarity(String a, String b) {
    if (semantic())
      try {
        float[] x = embed(a), y = embed(b);
        double sum = 0;
        for (int i = 0; i < x.length; i++) sum += x[i] * y[i];
        return sum;
      } catch (Exception e) {
        status = "Keyword matching (inference fallback)";
      }
    return keyword(a, b);
  }

  public List<Map<String, Object>> rank(
      Map<String, Object> profile, List<Map<String, Object>> items) {
    String input =
        profile.get("skills")
            + ". Interested in "
            + profile.get("interests")
            + ". Goal: "
            + profile.get("goals");
    var result = new ArrayList<Map<String, Object>>();
    for (var item : items) {
      var r = new LinkedHashMap<>(item);
      String desc = item.get("title") + ". " + item.get("description") + ". " + item.get("skills");
      double score = similarity(input, desc);
      r.put("similarity", Math.round(Math.max(0, score) * 100));
      var overlap = words(input);
      overlap.retainAll(words(desc));
      r.put(
          "reason",
          overlap.isEmpty()
              ? "Related to your stated interests and learning goals"
              : "Shared interests: "
                  + String.join(", ", overlap.stream().sorted().limit(5).toList()));
      r.put("review", item.getOrDefault("prerequisites", ""));
      result.add(r);
    }
    result.sort(Comparator.comparingDouble(r -> -((Number) r.get("similarity")).doubleValue()));
    return result.stream().limit(5).toList();
  }

  @PreDestroy
  public void close() throws Exception {
    if (tokenizer != null) tokenizer.close();
    if (session != null) session.close();
  }
}
