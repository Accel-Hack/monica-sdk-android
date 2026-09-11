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
  private final AtomicInteger redirectTargetHits = new AtomicInteger();
  private final CountDownLatch hang = new CountDownLatch(1);
  private volatile boolean hangResponses;
  private volatile int bodyBytes;

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
