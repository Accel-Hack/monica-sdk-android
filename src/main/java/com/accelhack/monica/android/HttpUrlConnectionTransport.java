package com.accelhack.monica.android;

import com.accelhack.monica.MonicaEnvelope;
import com.accelhack.monica.MonicaEvent;
import com.accelhack.monica.MonicaTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Sends envelopes with {@link HttpURLConnection}.
 *
 * <p>Android has no {@code java.net.http.HttpClient}, so the JDK transport used by the
 * server integrations cannot be reused. The retry policy is the one every MONICA SDK
 * follows: 2xx accepted, 429 waits out {@code Retry-After}, 5xx and I/O failures back
 * off with jitter, and every other 4xx is dropped rather than retried forever.
 *
 * <p>An envelope that carries a {@code fatal} event is sent against a deadline: the
 * process is about to die and the crashing thread only waits {@code shutdownTimeout}.
 * Connect and read timeouts are cut down to what is left of it, and a retry is only
 * attempted when its backoff still fits. Without that, the default request timeout
 * alone could outlive the process.
 */
public final class HttpUrlConnectionTransport implements MonicaTransport {
  private static final int MAX_RETRY_AFTER_SECONDS = 60;
  /**
   * How much of a response body is read before the connection is discarded instead. The
   * body is never used; reading it only lets the socket go back to the pool. A captive
   * portal or a broken proxy that streams forever must not pin the single sender thread.
   */
  static final int MAX_DRAIN_BYTES = 64 * 1024;

  private final ObjectMapper mapper = new ObjectMapper();
  private final URL endpoint;
  private final String key;
  private final int maxRetries;
  private final long timeoutMillis;
  private final long fatalDeadlineMillis;

  public HttpUrlConnectionTransport(String dsn, int maxRetries, Duration requestTimeout) {
    this(dsn, maxRetries, requestTimeout, null);
  }

  /**
   * @param fatalDeadline the budget for an envelope that carries a fatal event, normally
   *     the {@code shutdownTimeout}; {@code null} applies the plain request timeout
   */
  public HttpUrlConnectionTransport(String dsn, int maxRetries, Duration requestTimeout,
      Duration fatalDeadline) {
    if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must not be negative");
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
    if (fatalDeadline != null && (fatalDeadline.isZero() || fatalDeadline.isNegative())) {
      throw new IllegalArgumentException("fatalDeadline must be positive");
    }
    this.endpoint = endpointOf(dsn);
    this.key = publicKeyOf(dsn);
    this.maxRetries = maxRetries;
    this.timeoutMillis = Math.min(requestTimeout.toMillis(), Integer.MAX_VALUE);
    this.fatalDeadlineMillis = fatalDeadline == null ? 0 : fatalDeadline.toMillis();
  }

  @Override
  public boolean send(MonicaEnvelope envelope) throws Exception {
    byte[] body = gzip(mapper.writeValueAsBytes(envelope));
    long deadlineNanos = fatalDeadlineMillis > 0 && carriesFatal(envelope)
        ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(fatalDeadlineMillis)
        : Long.MAX_VALUE;
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      int timeout = attemptTimeoutMillis(deadlineNanos);
      if (timeout <= 0) return false;
      HttpURLConnection connection = null;
      boolean reusable = false;
      try {
        // Hold the connection before anything on it can fail, so finally can release it.
        connection = (HttpURLConnection) endpoint.openConnection();
        configure(connection, body.length, timeout);
        try (OutputStream output = connection.getOutputStream()) {
          output.write(body);
        }
        int status = connection.getResponseCode();
        if (status >= 200 && status < 300) {
          reusable = true;
          return true;
        }
        // 3xx is not followed (the key must not travel to another host) and 4xx other
        // than 429 will not get better; both are dropped. Anything below 200, including
        // the -1 that stands for an unparseable status line, is treated like an I/O
        // failure and retried.
        if (status >= 300 && status != 429 && status < 500) return false;
        if (attempt == maxRetries) return false;
        Duration delay = status == 429
            ? retryAfter(connection.getHeaderField("Retry-After"), attempt)
            : backoff(attempt);
        if (!fitsBefore(delay, deadlineNanos)) return false;
        release(connection, false);
        connection = null;
        Thread.sleep(delay.toMillis());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      } catch (Exception failure) {
        if (attempt == maxRetries) return false;
        Duration delay = backoff(attempt);
        if (!fitsBefore(delay, deadlineNanos)) return false;
        release(connection, false);
        connection = null;
        try {
          Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return false;
        }
      } finally {
        release(connection, reusable);
      }
    }
    return false;
  }

  private void configure(HttpURLConnection connection, int contentLength, int timeout)
      throws Exception {
    connection.setRequestMethod("POST");
    connection.setConnectTimeout(timeout);
    connection.setReadTimeout(timeout);
    connection.setDoOutput(true);
    connection.setInstanceFollowRedirects(false);
    connection.setFixedLengthStreamingMode(contentLength);
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setRequestProperty("Content-Encoding", "gzip");
    connection.setRequestProperty("X-Monica-Key", key);
  }

  /** The timeout for one attempt: the configured one, cut to what is left of the deadline. */
  private int attemptTimeoutMillis(long deadlineNanos) {
    if (deadlineNanos == Long.MAX_VALUE) return (int) timeoutMillis;
    long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    return (int) Math.min(timeoutMillis, Math.max(remaining, 0));
  }

  private static boolean fitsBefore(Duration delay, long deadlineNanos) {
    if (deadlineNanos == Long.MAX_VALUE) return true;
    long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    // Sleeping the whole budget away leaves nothing for the request itself.
    return delay.toMillis() < remaining;
  }

  private static boolean carriesFatal(MonicaEnvelope envelope) {
    for (MonicaEvent item : envelope.getItems()) {
      if ("fatal".equals(item.get("level"))) return true;
    }
    return false;
  }

  /**
   * Reads what is left of the response so the socket can be pooled, and disconnects
   * when the connection is not worth keeping: an error response, or a body that did not
   * end inside {@link #MAX_DRAIN_BYTES}.
   */
  private static void release(HttpURLConnection connection, boolean reusable) {
    if (connection == null) return;
    boolean drained = false;
    try (InputStream stream = connection.getErrorStream() != null
        ? connection.getErrorStream() : connection.getInputStream()) {
      if (stream == null) {
        drained = true;
      } else {
        byte[] sink = new byte[4096];
        long total = 0;
        int read;
        while ((read = stream.read(sink)) >= 0) {
          total += read;
          if (total > MAX_DRAIN_BYTES) break;
        }
        drained = read < 0;
      }
    } catch (Exception ignored) {
      // A body we do not read cannot fail the send.
    }
    if (!reusable || !drained) connection.disconnect();
  }

  static Duration retryAfter(String header, int attempt) {
    if (header != null) {
      try {
        long seconds = Long.parseLong(header.trim());
        return Duration.ofSeconds(Math.min(Math.max(seconds, 0), MAX_RETRY_AFTER_SECONDS));
      } catch (NumberFormatException ignored) {
        // A non-numeric Retry-After falls through to the jittered backoff.
      }
    }
    return backoff(attempt);
  }

  static Duration backoff(int attempt) {
    long ceiling = Math.min(1_000L << Math.min(attempt, 5), 30_000L);
    return Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1));
  }

  private static byte[] gzip(byte[] value) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      gzip.write(value);
    }
    return bytes.toByteArray();
  }

  static URL endpointOf(String dsn) {
    URI uri = uriOf(dsn);
    boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
    if (!"https".equals(uri.getScheme()) && !local) {
      throw new IllegalArgumentException("dsn must use https except for localhost");
    }
    try {
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
          "/v1/envelope", null, null).toURL();
    } catch (Exception failure) {
      throw new IllegalArgumentException("dsn must be a valid URL", failure);
    }
  }

  /**
   * An APK ships to every user, so anything embedded in it is readable. A secret key
   * in a distributed artifact is a leak, and failing here is what keeps it from
   * shipping as one that merely logged a warning.
   */
  static String publicKeyOf(String dsn) {
    String userInfo = uriOf(dsn).getRawUserInfo();
    String key;
    try {
      key = userInfo == null ? "" : java.net.URLDecoder.decode(
          userInfo.split(":", 2)[0], StandardCharsets.UTF_8.name());
    } catch (Exception failure) {
      throw new IllegalArgumentException("dsn must contain an API key", failure);
    }
    if (key.isEmpty()) throw new IllegalArgumentException("dsn must contain an API key");
    if (!key.startsWith("mpk_")) {
      throw new IllegalArgumentException(
          "Android dsn must contain a public mpk_ key; an msk_ key in an APK is a leaked secret");
    }
    return key;
  }

  private static URI uriOf(String dsn) {
    if (dsn == null || dsn.trim().isEmpty()) {
      throw new IllegalArgumentException("dsn must not be empty");
    }
    URI uri;
    try {
      uri = URI.create(dsn.trim());
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("dsn must be a valid URL", failure);
    }
    if (uri.getScheme() == null || uri.getHost() == null) {
      throw new IllegalArgumentException("dsn must be a valid URL");
    }
    return uri;
  }
}
