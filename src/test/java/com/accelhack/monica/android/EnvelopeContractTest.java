package com.accelhack.monica.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Checks what the Android integration actually puts on the wire against MONICA's
 * published contract bundle, which is generated from the validator ingest actually runs.
 *
 * <p>The bundle is not authored here. {@code scripts/spec-sync.py} vendors the copy
 * MONICA serves at {@code https://spec.monica.accelhack.net/v1/} into {@code spec/} and
 * records every digest in {@code spec.lock.json}. This test verifies the copy against
 * the lock before reading it, so a hand-edited spec cannot turn it into a test of
 * nothing, and it fails rather than skips when the copy is missing: without it a change
 * to the shared contract would only be caught in ingest.
 *
 * <p>Every bound asserted here is read from the bundle, so a tightened schema fails
 * this test instead of silently drifting from it.
 */
@Isolated
class EnvelopeContractTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

  private static Path specDirectory;
  private static JsonNode schema;
  private static JsonNode transportSpec;

  // --- the vendored bundle -------------------------------------------------

  @BeforeAll
  static void vendoredSpec() throws Exception {
    Path root = repositoryRoot();
    Path lockFile = root.resolve("spec.lock.json");
    JsonNode lock = MAPPER.readTree(Files.readAllBytes(lockFile));
    for (String key : new String[] {"origin", "version", "revision", "files"}) {
      assertTrue(lock.has(key), "spec.lock.json is missing " + key);
    }
    String version = lock.get("version").asText();
    specDirectory = root.resolve("spec").resolve(version);

    TreeMap<String, String> digests = new TreeMap<>(EnvelopeContractTest::byteOrder);
    for (Iterator<Map.Entry<String, JsonNode>> files = lock.get("files").fields(); files.hasNext();) {
      Map.Entry<String, JsonNode> entry = files.next();
      Path file = specDirectory.resolve(entry.getKey());
      if (!Files.isRegularFile(file)) {
        fail("spec/" + version + "/" + entry.getKey() + " is not vendored. The contract lives in"
            + " MONICA and is pulled in by a script:\n  python3 scripts/spec-sync.py");
      }
      String expected = entry.getValue().asText();
      assertTrue(SHA_256.matcher(expected).matches(), entry.getKey() + ": lock digest is not sha256");
      assertEquals(expected, sha256(Files.readAllBytes(file)),
          "spec/" + version + "/" + entry.getKey() + " does not match spec.lock.json. Run"
              + " `python3 scripts/spec-sync.py` instead of editing the vendored copy.");
      digests.put(entry.getKey(), expected);
    }
    try (Stream<Path> walk = Files.walk(specDirectory)) {
      List<String> undeclared = walk.filter(Files::isRegularFile)
          .map(path -> specDirectory.relativize(path).toString().replace('\\', '/'))
          .filter(path -> !digests.containsKey(path))
          .collect(Collectors.toList());
      assertTrue(undeclared.isEmpty(), "spec/ holds files spec.lock.json does not declare: " + undeclared);
    }

    // `revision` is MONICA's fingerprint for the whole bundle: the sha256 of
    // "<digest>  <path>" lines in byte order of path, joined by "\n" without a
    // trailing newline. Recomputing it means a lock whose entries were edited to
    // agree with a doctored spec still fails here.
    String recomputed = sha256(digests.entrySet().stream()
        .map(entry -> entry.getValue() + "  " + entry.getKey())
        .collect(Collectors.joining("\n")).getBytes(StandardCharsets.UTF_8));
    assertEquals(lock.get("revision").asText(), recomputed,
        "spec.lock.json: revision does not match its own files; run `python3 scripts/spec-sync.py`");

    schema = MAPPER.readTree(Files.readAllBytes(specDirectory.resolve("envelope.json")));
    transportSpec = MAPPER.readTree(Files.readAllBytes(specDirectory.resolve("transport.json")));
  }

  /** surefire runs with the project directory as user.dir; walking up keeps IDE runs working too. */
  private static Path repositoryRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("spec.lock.json"))) return current;
      current = current.getParent();
    }
    throw new IllegalStateException("spec.lock.json not found above " + System.getProperty("user.dir")
        + "; the vendored contract is missing. See README.md.");
  }

  private static int byteOrder(String left, String right) {
    byte[] a = left.getBytes(StandardCharsets.UTF_8);
    byte[] b = right.getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < Math.min(a.length, b.length); i++) {
      int difference = (a[i] & 0xff) - (b[i] & 0xff);
      if (difference != 0) return difference;
    }
    return a.length - b.length;
  }

  private static String sha256(byte[] bytes) throws Exception {
    StringBuilder hex = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }

  private JsonNode errorItemProperty(String name) {
    return schema.at("/$defs/errorItem/properties/" + name);
  }

  private JsonNode envelope() throws Exception {
    RecordingTransport transport = new RecordingTransport();
    MonicaAndroid monica = MonicaAndroid.install(new FakePlatform(),
        MonicaAndroidOptions.builder()
            .dsn("https://mpk_public@ingest.monica.test/1")
            .environment("production")
            .transport(transport)
            .captureUncaughtExceptions(false)
            .trackScreens(false)
            .build());
    try {
      monica.addBreadcrumb("ui.click", "submitButton");
      monica.setUser("u_123");
      monica.setScreen("CheckoutActivity");
      monica.captureException(new IllegalStateException("boom", new java.io.IOException("cause")));
      assertTrue(monica.flush(Duration.ofSeconds(2)));
    } finally {
      monica.close();
    }
    return MAPPER.valueToTree(transport.envelopes().get(0));
  }

  // --- envelope.json -------------------------------------------------------

  @Test
  void carriesEveryFieldTheEnvelopeRequires() throws Exception {
    JsonNode envelope = envelope();
    for (JsonNode required : schema.get("required")) {
      assertTrue(envelope.has(required.asText()), "envelope is missing " + required.asText());
    }
    for (JsonNode required : schema.at("/$defs/sdk/required")) {
      JsonNode value = envelope.at("/sdk/" + required.asText());
      assertTrue(value.isTextual() && !value.asText().isEmpty(), "sdk." + required.asText());
    }
    assertTrue(envelope.get("discarded").isIntegralNumber());
    assertTrue(envelope.get("discarded").asLong() >= schema.at("/properties/discarded/minimum").asLong());
    assertTrue(envelope.get("items").size() <= schema.at("/properties/items/maxItems").asInt());
    Instant.parse(envelope.get("sent_at").asText());
  }

  @Test
  void namesItselfAfterThePublishedArtifact() throws Exception {
    // The bundle defines sdk.name as the package name in the distribution registry, and
    // sdk.version is what a MONICA operator sees, so it must be the version being built.
    JsonNode sdk = envelope().get("sdk");
    assertEquals("com.accelhack.monica:monica-android", sdk.get("name").asText());
    String projectVersion = System.getProperty("monica.project.version");
    assertTrue(projectVersion != null && !projectVersion.isEmpty(),
        "surefire must pass -Dmonica.project.version=${project.version}");
    assertEquals(projectVersion.replace("-SNAPSHOT", ""), sdk.get("version").asText(),
        "MonicaAndroid.SDK_VERSION must move together with pom.xml");
  }

  @Test
  void carriesEveryFieldAnErrorItemRequires() throws Exception {
    JsonNode item = envelope().get("items").get(0);
    for (JsonNode required : schema.at("/$defs/errorItem/required")) {
      assertTrue(item.has(required.asText()), "item is missing " + required.asText());
    }
    assertEquals(errorItemProperty("type").get("const").asText(), item.get("type").asText());
    assertTrue(item.get("event_id").asText()
        .matches(errorItemProperty("event_id").get("pattern").asText()));
    Instant.parse(item.get("timestamp").asText());
    if (errorItemProperty("timestamp").has("pattern")) {
      assertTrue(item.get("timestamp").asText()
          .matches(errorItemProperty("timestamp").get("pattern").asText()), "timestamp pattern");
    }
    String environment = item.get("environment").asText();
    assertTrue(environment.length() >= errorItemProperty("environment").get("minLength").asInt());
    assertTrue(environment.length() <= errorItemProperty("environment").get("maxLength").asInt());
  }

  @Test
  void staysInsideThePlatformAndLevelConstraints() throws Exception {
    // Android reports platform java. Adding an "android" value would mean teaching
    // ingest and grouping to branch on OS instead of language. The schema may express
    // platform as an enum or as a bounded string; both readings are honoured here.
    JsonNode item = envelope().get("items").get(0);
    assertEquals("java", item.get("platform").asText());
    assertWithin(errorItemProperty("platform"), item.get("platform"));
    assertWithin(errorItemProperty("level"), item.get("level"));
  }

  @Test
  void keepsTagsAsStringsAndFramesAnnotated() throws Exception {
    JsonNode item = envelope().get("items").get(0);
    assertFalse(item.get("tags").isEmpty());
    item.get("tags").fields().forEachRemaining(entry ->
        assertTrue(entry.getValue().isTextual(), entry.getKey() + " must be a string"));

    JsonNode values = item.at("/exception/values");
    assertEquals(2, values.size(), "the cause chain runs outermost first");
    assertEquals("java.lang.IllegalStateException", values.get(0).get("type").asText());
    assertEquals("java.io.IOException", values.get(1).get("type").asText());
    int maxFrames = schema.at("/$defs/exceptionValue/properties/stacktrace/properties/frames/maxItems")
        .asInt();
    for (JsonNode value : values) {
      for (JsonNode required : schema.at("/$defs/exceptionValue/required")) {
        assertTrue(value.has(required.asText()), "exception value is missing " + required.asText());
      }
      assertWithin(schema.at("/$defs/mechanism/properties/type"), value.at("/mechanism/type"));
      assertTrue(value.at("/mechanism/handled").isBoolean());
      JsonNode frames = value.at("/stacktrace/frames");
      assertFalse(frames.isEmpty(), "a Java exception always has frames");
      assertTrue(frames.size() <= maxFrames);
      for (JsonNode frame : frames) {
        for (JsonNode required : schema.at("/$defs/frame/required")) {
          assertTrue(frame.has(required.asText()), "frame is missing " + required.asText());
        }
        assertTrue(frame.get("filename").isTextual() && !frame.get("filename").asText().isEmpty());
        assertTrue(frame.get("in_app").isBoolean());
        if (frame.has("lineno")) {
          assertTrue(frame.get("lineno").asInt()
              >= schema.at("/$defs/frame/properties/lineno/minimum").asInt());
        }
      }
    }
  }

  @Test
  void keepsBreadcrumbsAndUserInTheShapeTheSchemaDescribes() throws Exception {
    JsonNode item = envelope().get("items").get(0);
    JsonNode breadcrumb = item.get("breadcrumbs").get(0);
    Instant.parse(breadcrumb.get("timestamp").asText());
    assertEquals("ui.click", breadcrumb.get("category").asText());
    assertTrue(item.at("/user/id").isTextual());
    assertEquals("u_123", item.at("/user/id").asText());
  }

  // --- transport.json ------------------------------------------------------

  @Test
  void postsToTheEndpointTheContractNames() {
    JsonNode endpoint = transportSpec.get("endpoint");
    assertEquals("POST", endpoint.get("method").asText(), "HttpUrlConnectionTransport only POSTs");
    assertEquals(endpoint.get("path").asText(),
        HttpUrlConnectionTransport.endpointOf("https://mpk_public@ingest.monica.test/42").getPath(),
        "the DSN path is the project, not the ingest path");
    assertEquals("application/json", endpoint.get("content_type").asText());
    assertEquals("gzip", endpoint.get("content_encoding").asText());
  }

  @Test
  void acceptsOnlyTheInsecureHostsTheContractAllowsOverPlainHttp() {
    List<String> insecure = new ArrayList<>();
    for (JsonNode host : transportSpec.at("/dsn/insecure_hosts")) insecure.add(host.asText());
    for (String host : insecure) {
      HttpUrlConnectionTransport.endpointOf("http://mpk_public@" + host + ":8080/1");
    }
    assertThrows(IllegalArgumentException.class,
        () -> HttpUrlConnectionTransport.endpointOf("http://mpk_public@ingest.monica.test/1"));
  }

  @Test
  void usesThePublicKeySchemeAndRefusesTheSecretOne() {
    // An APK ships to every user, so this transport only ever holds a public key. The
    // header it sets and the prefix it insists on are both the contract's, not its own.
    JsonNode publicKey = null;
    JsonNode secretKey = null;
    for (JsonNode auth : transportSpec.get("auth")) {
      if ("public".equals(auth.get("kind").asText())) publicKey = auth;
      if ("secret".equals(auth.get("kind").asText())) secretKey = auth;
    }
    assertTrue(publicKey != null && secretKey != null, "transport.json must describe both key kinds");
    assertEquals("X-Monica-Key", publicKey.get("header").asText(),
        "HttpUrlConnectionTransport sets X-Monica-Key; the contract moved the public key elsewhere");
    assertEquals("<key>", publicKey.get("value").asText(), "the public key travels bare");
    String prefix = publicKey.get("key_prefix").asText();
    assertEquals(prefix + "public",
        HttpUrlConnectionTransport.publicKeyOf("https://" + prefix + "public@ingest.monica.test/1"));
    String secret = secretKey.get("key_prefix").asText() + "secret";
    assertThrows(IllegalArgumentException.class,
        () -> HttpUrlConnectionTransport.publicKeyOf("https://" + secret + "@ingest.monica.test/1"));
  }

  @Test
  void retriesWithTheBackoffTheContractDescribes() {
    JsonNode retry = transportSpec.get("retry");
    assertTrue(retry.get("retry_on_network_error").asBoolean());
    assertTrue(retry.at("/retry_after/integer_seconds_only").asBoolean(),
        "HttpUrlConnectionTransport only parses integer Retry-After");
    long maxRetryAfter = retry.at("/retry_after/max_seconds").asLong();
    assertEquals(Duration.ofSeconds(maxRetryAfter),
        HttpUrlConnectionTransport.retryAfter(Long.toString(maxRetryAfter * 10), 0));
    assertEquals(Duration.ofSeconds(7), HttpUrlConnectionTransport.retryAfter("7", 0));

    JsonNode backoff = retry.get("backoff");
    long base = backoff.get("base_ms").asLong();
    long factor = backoff.get("factor").asLong();
    long max = backoff.get("max_ms").asLong();
    double jitterMin = backoff.get("jitter_min").asDouble();
    double jitterMax = backoff.get("jitter_max").asDouble();
    for (int attempt = 0; attempt < 12; attempt++) {
      long ceiling = base;
      for (int i = 0; i < attempt && ceiling < max; i++) ceiling *= factor;
      ceiling = Math.min(ceiling, max);
      for (int sample = 0; sample < 50; sample++) {
        long delay = HttpUrlConnectionTransport.backoff(attempt).toMillis();
        assertTrue(delay >= (long) (ceiling * jitterMin) && delay <= (long) (ceiling * jitterMax),
            "attempt " + attempt + " slept " + delay + "ms, outside the contract's jitter window");
      }
    }
  }

  @Test
  void knowsEveryStatusTheContractDescribes() {
    // The transport's branches are fixed: 2xx accepted, 429 waits, 5xx and I/O back off,
    // the rest is dropped. A status or action added to the contract is one this SDK has
    // not considered, so the vocabulary is pinned here rather than read loosely.
    Map<String, String> expected = new TreeMap<>();
    expected.put("202", "accept");
    expected.put("400", "drop");
    expected.put("401", "drop_and_stop");
    expected.put("413", "split_and_retry");
    expected.put("422", "drop");
    expected.put("429", "wait_retry_after");
    expected.put("5xx", "backoff");
    Map<String, String> actual = new TreeMap<>();
    transportSpec.get("status").fields()
        .forEachRemaining(entry -> actual.put(entry.getKey(), entry.getValue().asText()));
    assertEquals(expected, actual, "transport.json describes a status this SDK has not considered");
    List<String> retryable = new ArrayList<>();
    for (JsonNode status : transportSpec.at("/retry/retryable_statuses")) retryable.add(status.asText());
    assertEquals(List.of("429", "5xx"), retryable);
  }

  /** Enum or bounded string, whichever the schema uses for this property. */
  private static void assertWithin(JsonNode property, JsonNode value) {
    if (property.has("enum")) {
      List<String> allowed = new ArrayList<>();
      for (JsonNode candidate : property.get("enum")) allowed.add(candidate.asText());
      assertTrue(allowed.contains(value.asText()), value + " is not in " + allowed);
      return;
    }
    assertEquals("string", property.get("type").asText());
    assertTrue(value.isTextual());
    if (property.has("minLength")) assertTrue(value.asText().length() >= property.get("minLength").asInt());
    if (property.has("maxLength")) assertTrue(value.asText().length() <= property.get("maxLength").asInt());
  }
}
