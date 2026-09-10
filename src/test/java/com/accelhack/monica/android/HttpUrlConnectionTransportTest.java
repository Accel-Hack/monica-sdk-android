package com.accelhack.monica.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.accelhack.monica.MonicaClient;
import com.accelhack.monica.MonicaEnvelope;
import com.accelhack.monica.MonicaEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpUrlConnectionTransportTest {
  private HttpServer server;
  private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
  private final List<int[]> responses = Collections.synchronizedList(new ArrayList<>());
  private final List<MonicaDiagnostic> diagnostics = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger redirectTargetHits = new AtomicInteger();
  private final CountDownLatch hang = new CountDownLatch(1);
  private volatile boolean hangResponses;
  private volatile int bodyBytes;
  private volatile byte[] errorBody;
  private volatile boolean trickleBody;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/envelope", this::handle);
    server.createContext("/elsewhere", exchange -> {
      redirectTargetHits.incrementAndGet();
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
    });
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.start();
  }

  @AfterEach
  void stop() {
    hang.countDown();
    server.stop(0);
  }

  private String dsn() {
    return "http://mpk_public@127.0.0.1:" + server.getAddress().getPort() + "/1";
  }

  /** Each entry is {status, retryAfterSeconds}; a negative retryAfter omits the header. */
  private void respondWith(int[]... programmed) {
    Collections.addAll(responses, programmed);
  }

  private void handle(HttpExchange exchange) throws java.io.IOException {
    byte[] body = readAll(exchange.getRequestBody());
    requests.add(new Request(exchange.getRequestHeaders().getFirst("X-Monica-Key"),
        exchange.getRequestHeaders().getFirst("Content-Encoding"),
        exchange.getRequestHeaders().getFirst("Content-Type"),
        body));
    if (hangResponses) {
      try {
        hang.await(15, TimeUnit.SECONDS);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }
    int index = requests.size() - 1;
    int[] programmed = index < responses.size() ? responses.get(index) : new int[] {202, -1};
    if (programmed[1] >= 0) {
      exchange.getResponseHeaders().add("Retry-After", String.valueOf(programmed[1]));
    }
    if (programmed[0] >= 300 && programmed[0] < 400) {
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:"
          + server.getAddress().getPort() + "/elsewhere");
    }
    byte[] error = errorBody;
    if (error != null) {
      if (trickleBody) {
        // A body that arrives byte by byte: the read timeout never fires, so only a
        // wall-clock bound can keep a fatal send inside its deadline.
        exchange.sendResponseHeaders(programmed[0], 0);
        try (OutputStream output = exchange.getResponseBody()) {
          for (byte value : error) {
            output.write(value);
            output.flush();
            if (hang.await(200, TimeUnit.MILLISECONDS)) break;
          }
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
        }
      } else {
        exchange.sendResponseHeaders(programmed[0], error.length);
        try (OutputStream output = exchange.getResponseBody()) {
          output.write(error);
        }
      }
      exchange.close();
      return;
    }
    if (bodyBytes > 0) {
      exchange.sendResponseHeaders(programmed[0], bodyBytes);
      try (OutputStream output = exchange.getResponseBody()) {
        byte[] chunk = new byte[8192];
        for (int written = 0; written < bodyBytes; written += chunk.length) {
          output.write(chunk, 0, Math.min(chunk.length, bodyBytes - written));
        }
      }
    } else {
      exchange.sendResponseHeaders(programmed[0], -1);
    }
    exchange.close();
  }

  /** What ingest actually answers a 422 with; see spec/v1/error.json. */
  private static final String REJECTION = "{\"error\":{\"code\":\"invalid_envelope\","
      + "\"message\":\"envelope failed validation\",\"issues\":[{"
      + "\"path\":\"$.items[0].request.method\","
      + "\"message\":\"Invalid type: Expected string\"}]}}";

  private HttpUrlConnectionTransport reporting(int maxRetries) {
    return new HttpUrlConnectionTransport(dsn(), maxRetries, Duration.ofSeconds(5), null,
        diagnostics::add);
  }

  /** Options for an install that only exercises sending: no crash handler, no Activities. */
  private MonicaAndroidOptions.Builder installOptions() {
    return MonicaAndroidOptions.builder()
        .dsn(dsn())
        .environment("test")
        .captureUncaughtExceptions(false)
        .trackScreens(false)
        .flushInterval(Duration.ofHours(1))
        .maxRetries(2);
  }

  /** A well-formed rejection that is nevertheless past the drain cap. */
  private static byte[] oversizedBody() {
    StringBuilder padded = new StringBuilder("{\"error\":{\"code\":\"invalid_envelope\","
        + "\"message\":\"envelope failed validation\",\"issues\":[");
    while (padded.length() < HttpUrlConnectionTransport.MAX_DRAIN_BYTES * 2) {
      padded.append("{\"path\":\"$.items[0].message\",\"message\":\"too long\"},");
    }
    padded.append("{\"path\":\"$.items[0].message\",\"message\":\"too long\"}]}}");
    return padded.toString().getBytes(StandardCharsets.UTF_8);
  }

  private MonicaClient client(int maxRetries) {
    return client(new HttpUrlConnectionTransport(dsn(), maxRetries, Duration.ofSeconds(5)));
  }

  private static MonicaClient client(HttpUrlConnectionTransport transport) {
    return MonicaClient.builder()
        .environment("test")
        .transport(transport)
        .flushInterval(Duration.ofHours(1))
        .build();
  }

  @Test
  void sendsAGzippedEnvelopeAuthenticatedWithThePublicKeyHeader() throws Exception {
    try (MonicaClient client = client(0)) {
      client.captureMessage("boom");
      assertTrue(client.flush(Duration.ofSeconds(5)));
    }

    assertEquals(1, requests.size());
    Request request = requests.get(0);
    assertEquals("mpk_public", request.key);
    assertEquals("gzip", request.encoding);
    assertEquals("application/json", request.contentType);

    JsonNode envelope = new ObjectMapper().readTree(gunzip(request.body));
    assertEquals("com.accelhack.monica:monica-core", envelope.get("sdk").get("name").asText());
    assertEquals("boom", envelope.get("items").get(0).get("message").asText());
  }

  @Test
  void neverRetriesAClientError() throws Exception {
    respondWith(new int[] {400, -1});
    try (MonicaClient client = client(3)) {
      client.captureMessage("boom");
      assertFalse(client.flush(Duration.ofSeconds(5)));
    }
    assertEquals(1, requests.size());
  }

  @Test
  void doesNotFollowARedirectBecauseTheKeyMustStayOnThisHost() throws Exception {
    respondWith(new int[] {302, -1});
    try (MonicaClient client = client(3)) {
      client.captureMessage("boom");
      assertFalse(client.flush(Duration.ofSeconds(5)));
    }
    assertEquals(1, requests.size());
    assertEquals(0, redirectTargetHits.get(), "the Location target must never see the key");
  }

  @Test
  void waitsOutTheRetryAfterOfA429AndThenSucceeds() throws Exception {
    respondWith(new int[] {429, 1}, new int[] {202, -1});
    long started = System.nanoTime();
    try (MonicaClient client = client(3)) {
      client.captureMessage("boom");
      assertTrue(client.flush(Duration.ofSeconds(10)));
    }
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertEquals(2, requests.size());
    assertTrue(elapsedMillis >= 950, "did not wait the second the server asked for: " + elapsedMillis);
  }

  @Test
  void givesUpOnServerErrorsOnceTheRetryBudgetIsSpent() throws Exception {
    respondWith(new int[] {500, -1}, new int[] {500, -1}, new int[] {500, -1});
    try (MonicaClient client = client(1)) {
      client.captureMessage("boom");
      assertFalse(client.flush(Duration.ofSeconds(10)));
    }
    assertEquals(2, requests.size());
  }

  @Test
  void retriesWhenTheServerRefusesTheConnection() throws Exception {
    int port = server.getAddress().getPort();
    server.stop(0);
    HttpUrlConnectionTransport transport = new HttpUrlConnectionTransport(
        "http://mpk_public@127.0.0.1:" + port + "/1", 1, Duration.ofSeconds(2));

    long started = System.nanoTime();
    assertFalse(transport.send(envelope("boom", "error")));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    // One refused attempt, one backoff (0.5s to 1s), one more refused attempt.
    assertTrue(elapsedMillis >= 450, "did not back off before retrying: " + elapsedMillis);
  }

  @Test
  void treatsAnUnparseableStatusLineAsAFailureWorthRetrying() throws Exception {
    // A captive portal or a broken proxy answers with something that is not HTTP.
    AtomicInteger accepted = new AtomicInteger();
    try (ServerSocket garbage = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
      Thread acceptor = new Thread(() -> {
        while (!garbage.isClosed()) {
          try (Socket socket = garbage.accept()) {
            accepted.incrementAndGet();
            socket.getInputStream().read(new byte[4096]);
            socket.getOutputStream().write("this is not http\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          } catch (Exception ignored) {
            return;
          }
        }
      });
      acceptor.setDaemon(true);
      acceptor.start();

      HttpUrlConnectionTransport transport = new HttpUrlConnectionTransport(
          "http://mpk_public@127.0.0.1:" + garbage.getLocalPort() + "/1", 1, Duration.ofSeconds(2));
      assertFalse(transport.send(envelope("boom", "error")));
    }
    assertEquals(2, accepted.get(), "the garbage answer must be retried, not dropped as a 4xx");
  }

  @Test
  void aServerThatNeverAnswersIsAbandonedAtTheReadTimeout() throws Exception {
    hangResponses = true;
    HttpUrlConnectionTransport transport = new HttpUrlConnectionTransport(dsn(), 0,
        Duration.ofMillis(300));

    long started = System.nanoTime();
    assertFalse(transport.send(envelope("boom", "error")));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertTrue(elapsedMillis >= 250, "returned before the timeout: " + elapsedMillis);
    assertTrue(elapsedMillis < 5_000, "the read timeout was not applied: " + elapsedMillis);
  }

  @Test
  void aFatalEnvelopeIsBoundByTheCrashDeadlineNotTheRequestTimeout() throws Exception {
    hangResponses = true;
    // Ten seconds per attempt and two retries would outlive the process by a wide margin;
    // the deadline has to override both.
    HttpUrlConnectionTransport transport = new HttpUrlConnectionTransport(dsn(), 2,
        Duration.ofSeconds(10), Duration.ofMillis(400));

    long started = System.nanoTime();
    assertFalse(transport.send(envelope("crash", "fatal")));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertTrue(elapsedMillis >= 350, "gave up before the deadline: " + elapsedMillis);
    assertTrue(elapsedMillis < 3_000, "the deadline did not bound the fatal send: " + elapsedMillis);
    assertEquals(1, requests.size(), "no time was left for a retry");
  }

  @Test
  void aFatalEnvelopeStillRetriesWhenTheFirstAttemptFailsFast() throws Exception {
    respondWith(new int[] {500, -1}, new int[] {202, -1});
    HttpUrlConnectionTransport transport = new HttpUrlConnectionTransport(dsn(), 2,
        Duration.ofSeconds(10), Duration.ofSeconds(5));

    assertTrue(transport.send(envelope("crash", "fatal")));
    assertEquals(2, requests.size());
  }

  @Test
  void theDeadlineDoesNotApplyToOrdinaryEnvelopes() throws Exception {
    respondWith(new int[] {500, -1}, new int[] {202, -1});
    HttpUrlConnectionTransport transport = new HttpUrlConnectionTransport(dsn(), 2,
        Duration.ofSeconds(10), Duration.ofMillis(1));

    assertTrue(transport.send(envelope("boom", "error")));
    assertEquals(2, requests.size());
  }

  @Test
  void stopsDrainingAnEndlessResponseBody() throws Exception {
    // A response body far past the drain cap must not keep the sender thread busy. The
    // cap only bounds what is read; the send itself is judged by the status.
    bodyBytes = HttpUrlConnectionTransport.MAX_DRAIN_BYTES * 8;
    HttpUrlConnectionTransport transport = new HttpUrlConnectionTransport(dsn(), 0,
        Duration.ofSeconds(5));
    assertTrue(transport.send(envelope("boom", "error")));
    assertTrue(transport.send(envelope("boom", "error")), "the next send must still work");
    assertEquals(2, requests.size());
  }

  @Test
  void warnsWithTheIssuePathsIngestReturnedForA422() throws Exception {
    // The whole point of reading the body: without these paths, an integrator whose
    // beforeSend drops a required field has no way to learn that nothing arrives.
    respondWith(new int[] {422, -1});
    errorBody = REJECTION.getBytes(StandardCharsets.UTF_8);
    FakePlatform platform = new FakePlatform();

    try (MonicaAndroid monica = MonicaAndroid.install(platform, installOptions().build())) {
      monica.captureMessage("boom");
      assertFalse(monica.flush(Duration.ofSeconds(5)));
    }

    assertEquals(1, requests.size(), "a 422 must still not be retried");
    String warning = String.join("\n", platform.warnings());
    assertTrue(warning.contains("monica: ingest rejected the envelope with 422 (invalid_envelope)"),
        warning);
    assertTrue(warning.contains("$.items[0].request.method"), warning);
    assertTrue(warning.contains("Invalid type: Expected string"), warning);
  }

  @Test
  void theDefaultWarningStaysQuietForA400ItCannotExplain() throws Exception {
    // A 400 carries no issues, so a line per event would only teach integrators to
    // ignore the tag. It still reaches a listener of their own.
    respondWith(new int[] {400, -1});
    errorBody = "{\"error\":{\"code\":\"bad_request\",\"message\":\"malformed body\"}}"
        .getBytes(StandardCharsets.UTF_8);
    FakePlatform platform = new FakePlatform();

    try (MonicaAndroid monica = MonicaAndroid.install(platform, installOptions().build())) {
      monica.captureMessage("boom");
      assertFalse(monica.flush(Duration.ofSeconds(5)));
    }

    assertEquals(Collections.emptyList(), platform.warnings());
  }

  @Test
  void handsTheParsedIssuesToTheListener() throws Exception {
    respondWith(new int[] {422, -1});
    errorBody = REJECTION.getBytes(StandardCharsets.UTF_8);

    assertFalse(reporting(2).send(envelope("boom", "error")));

    assertEquals(1, requests.size());
    assertEquals(1, diagnostics.size());
    MonicaDiagnostic diagnostic = diagnostics.get(0);
    assertEquals(422, diagnostic.status());
    assertEquals("invalid_envelope", diagnostic.code());
    assertEquals("envelope failed validation", diagnostic.message());
    assertFalse(diagnostic.stopped());
    assertEquals(1, diagnostic.issues().size());
    assertEquals("$.items[0].request.method", diagnostic.issues().get(0).path());
    assertEquals("Invalid type: Expected string", diagnostic.issues().get(0).message());
  }

  @Test
  void reportsA422WithNoIssuesRatherThanFailingOnABodyThatDoesNotFitTheContract() throws Exception {
    // Every one of these is something an ingest, a proxy or a captive portal can answer
    // with. None of them may throw, and none may turn the drop into a retry.
    byte[][] bodies = {
      new byte[0],
      "not json at all".getBytes(StandardCharsets.UTF_8),
      "[]".getBytes(StandardCharsets.UTF_8),
      "{\"error\":\"a string, not an object\"}".getBytes(StandardCharsets.UTF_8),
      "{\"error\":{\"issues\":[{\"path\":7,\"message\":\"not a string\"},{\"path\":\"$.a\"}]}}"
          .getBytes(StandardCharsets.UTF_8),
      "{\"error\":{\"code\":\"invalid_envelope\",\"message\":\"truncated\",\"issues\":[{\"path\":"
          .getBytes(StandardCharsets.UTF_8),
      oversizedBody(),
    };
    for (byte[] body : bodies) {
      requests.clear();
      responses.clear();
      diagnostics.clear();
      respondWith(new int[] {422, -1});
      errorBody = body;

      String label = "body of " + body.length + " bytes";
      assertFalse(reporting(2).send(envelope("boom", "error")), label);
      assertEquals(1, requests.size(), label + ": must not be retried");
      assertEquals(1, diagnostics.size(), label + ": the status is still worth reporting");
      assertEquals(422, diagnostics.get(0).status(), label);
      assertEquals(Collections.emptyList(), diagnostics.get(0).issues(), label);
    }
  }

  @Test
  void stopsSendingAfterA401BecauseTheKeyItselfWasRefused() throws Exception {
    respondWith(new int[] {401, -1}, new int[] {202, -1});
    errorBody = "{\"error\":{\"code\":\"unauthorized\",\"message\":\"unknown key\"}}"
        .getBytes(StandardCharsets.UTF_8);
    HttpUrlConnectionTransport transport = reporting(2);

    assertFalse(transport.send(envelope("boom", "error")));
    assertTrue(transport.isStopped());
    assertFalse(transport.send(envelope("boom", "error")), "a stopped transport accepts nothing");
    assertFalse(transport.send(envelope("crash", "fatal")), "not even a crash gets through");

    assertEquals(1, requests.size(), "nothing may be posted after a 401");
    assertEquals(1, diagnostics.size(), "the stop is reported once, not per dropped envelope");
    assertTrue(diagnostics.get(0).stopped());
    // The code is "unknown" even though the stub sent one, and that is the JDK, not this
    // transport: with setFixedLengthStreamingMode its HttpURLConnection withholds the
    // error stream for exactly 401 and 407, the two statuses its authentication retry
    // owns. 400 and 422 are unaffected, which is what matters — a 401 carries no issues.
    // Android's HttpURLConnection is OkHttp-backed and does hand the body over, so on a
    // device the real code can appear here; the wording has to hold either way.
    assertEquals("monica: ingest rejected the envelope with 401 (unknown);"
        + " no further envelopes will be sent", diagnostics.get(0).describe());
  }

  @Test
  void wordsAStopTheSameWayWhicheverEndTheCodeCameFrom() {
    // No issue count: a refused key is not about a field. Both branches are pinned here
    // rather than over HTTP, because which one a 401 takes depends on the platform.
    assertEquals("monica: ingest rejected the envelope with 401 (unauthorized);"
        + " no further envelopes will be sent",
        new MonicaDiagnostic(401, "unauthorized", "unknown key", Collections.emptyList(), true)
            .describe());
    assertEquals("monica: ingest rejected the envelope with 401 (unknown);"
        + " no further envelopes will be sent",
        new MonicaDiagnostic(401, null, null, Collections.emptyList(), true).describe());
  }

  @Test
  void doesNotReadTheBodyOfA429ItIsAboutToRetry() throws Exception {
    respondWith(new int[] {429, 0}, new int[] {202, -1});
    errorBody = REJECTION.getBytes(StandardCharsets.UTF_8);

    assertTrue(reporting(2).send(envelope("boom", "error")));

    assertEquals(2, requests.size());
    assertEquals(Collections.emptyList(), diagnostics, "a retried status is not a rejection");
  }

  @Test
  void aTricklingErrorBodyDoesNotOutliveTheFatalDeadline() throws Exception {
    // The read timeout is per read, so a body that dribbles a byte at a time stays
    // inside it forever. Reading the issues must not cost the crashing thread its
    // deadline; giving up on the body is the right trade.
    respondWith(new int[] {422, -1});
    errorBody = REJECTION.getBytes(StandardCharsets.UTF_8);
    trickleBody = true;
    HttpUrlConnectionTransport transport = new HttpUrlConnectionTransport(dsn(), 2,
        Duration.ofSeconds(10), Duration.ofMillis(600), diagnostics::add);

    long started = System.nanoTime();
    assertFalse(transport.send(envelope("crash", "fatal")));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertTrue(elapsedMillis < 3_000, "the deadline did not bound the body read: " + elapsedMillis);
    assertEquals(1, requests.size());
    assertEquals(1, diagnostics.size());
    assertEquals(422, diagnostics.get(0).status());
    assertEquals(Collections.emptyList(), diagnostics.get(0).issues(),
        "the body was abandoned mid-read, so there is nothing to report but the status");
  }

  @Test
  void aListenerOfTheIntegratorsOwnReplacesTheLogLine() throws Exception {
    respondWith(new int[] {422, -1});
    errorBody = REJECTION.getBytes(StandardCharsets.UTF_8);
    FakePlatform platform = new FakePlatform();

    try (MonicaAndroid monica = MonicaAndroid.install(platform,
        installOptions().onDiagnostic(diagnostics::add).build())) {
      monica.captureMessage("boom");
      assertFalse(monica.flush(Duration.ofSeconds(5)));
    }

    assertEquals(1, diagnostics.size());
    assertEquals("$.items[0].request.method", diagnostics.get(0).issues().get(0).path());
    assertEquals(Collections.emptyList(), platform.warnings(),
        "a listener means the integrator decides what gets logged");
  }

  @Test
  void describesManyIssuesWithoutSpellingOutEveryOne() {
    List<MonicaDiagnostic.Issue> issues = new ArrayList<>();
    for (int index = 0; index < 12; index++) {
      issues.add(new MonicaDiagnostic.Issue("$.items[" + index + "]", "required"));
    }
    String line = new MonicaDiagnostic(422, "invalid_envelope", "no", issues, false).describe();
    assertTrue(line.contains("12 issue(s)"), line);
    assertTrue(line.contains("$.items[9]"), line);
    assertFalse(line.contains("$.items[10]"), line);
    assertTrue(line.contains("and 2 more"), line);
  }

  @Test
  void capsRetryAfterAndFallsBackToBackoffForAnythingElse() {
    assertEquals(Duration.ofSeconds(60), HttpUrlConnectionTransport.retryAfter("100000", 0));
    assertEquals(Duration.ofSeconds(7), HttpUrlConnectionTransport.retryAfter(" 7 ", 0));
    assertEquals(Duration.ZERO, HttpUrlConnectionTransport.retryAfter("-3", 0));
    // An HTTP-date Retry-After is legal but not parsed; the jittered backoff applies.
    Duration dated = HttpUrlConnectionTransport.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT", 0);
    assertTrue(dated.toMillis() >= 500 && dated.toMillis() <= 1_000, dated.toString());
    Duration missing = HttpUrlConnectionTransport.retryAfter(null, 3);
    assertTrue(missing.toMillis() >= 4_000 && missing.toMillis() <= 8_000, missing.toString());
    assertTrue(HttpUrlConnectionTransport.backoff(20).toMillis() <= 30_000, "backoff is capped");
  }

  @Test
  void refusesASecretKeyBecauseAnApkIsReadable() {
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> new HttpUrlConnectionTransport("https://msk_secret@ingest.monica.test/1", 1,
            Duration.ofSeconds(5)));
    assertTrue(failure.getMessage().contains("mpk_"));
  }

  @Test
  void refusesPlainHttpOutsideLocalhost() {
    assertThrows(IllegalArgumentException.class,
        () -> new HttpUrlConnectionTransport("http://mpk_public@ingest.monica.test/1", 1,
            Duration.ofSeconds(5)));
    assertEquals("http://localhost:8787/v1/envelope",
        HttpUrlConnectionTransport.endpointOf("http://mpk_public@localhost:8787/1").toString());
  }

  @Test
  void refusesADsnWithoutAKey() {
    assertThrows(IllegalArgumentException.class,
        () -> new HttpUrlConnectionTransport("https://ingest.monica.test/1", 1,
            Duration.ofSeconds(5)));
  }

  @Test
  void refusesAnUnusableRetryOrTimeoutConfiguration() {
    assertThrows(IllegalArgumentException.class,
        () -> new HttpUrlConnectionTransport("https://mpk_public@ingest.monica.test/1", -1,
            Duration.ofSeconds(5)));
    assertThrows(IllegalArgumentException.class,
        () -> new HttpUrlConnectionTransport("https://mpk_public@ingest.monica.test/1", 1,
            Duration.ZERO));
    assertThrows(IllegalArgumentException.class,
        () -> new HttpUrlConnectionTransport("https://mpk_public@ingest.monica.test/1", 1,
            Duration.ofSeconds(5), Duration.ZERO));
  }

  @Test
  void alwaysPostsToTheEnvelopeEndpointWhateverThePathIs() throws Exception {
    // The project path in a DSN is not the ingest path; every SDK posts to /v1/envelope.
    java.net.URL endpoint = HttpUrlConnectionTransport.endpointOf(
        "https://mpk_public@ingest.monica.test/42");
    assertEquals("https://ingest.monica.test/v1/envelope", endpoint.toString());
  }

  private static MonicaEnvelope envelope(String message, String level) {
    MonicaEvent event = new MonicaEvent()
        .put("type", "error")
        .put("event_id", java.util.UUID.randomUUID().toString())
        .put("timestamp", java.time.Instant.now().toString())
        .put("level", level)
        .put("platform", "java")
        .put("environment", "test")
        .put("message", message);
    return new MonicaEnvelope("com.accelhack.monica:monica-android", "0.0.0",
        java.time.Instant.now().toString(), 0, Collections.singletonList(event));
  }

  private static byte[] gunzip(byte[] value) throws Exception {
    try (GZIPInputStream stream = new GZIPInputStream(new java.io.ByteArrayInputStream(value))) {
      return readAll(stream);
    }
  }

  private static byte[] readAll(InputStream stream) throws java.io.IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int read;
    while ((read = stream.read(buffer)) >= 0) bytes.write(buffer, 0, read);
    return bytes.toByteArray();
  }

  private static final class Request {
    private final String key;
    private final String encoding;
    private final String contentType;
    private final byte[] body;

    private Request(String key, String encoding, String contentType, byte[] body) {
      this.key = key;
      this.encoding = encoding;
      this.contentType = contentType;
      this.body = body;
    }
  }
}
