package com.accelhack.monica.android;

import com.accelhack.monica.BeforeSend;
import com.accelhack.monica.MonicaOptions;
import com.accelhack.monica.MonicaTransport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configuration for {@link MonicaAndroid}.
 *
 * <p>{@link Builder#build()} validates everything the core client would reject, but it
 * never throws: a monitoring SDK is not worth crashing the application over. Problems
 * are collected in {@link #problems()}; {@code MonicaAndroid.install()} logs them and
 * installs nothing. An application that wants to fail fast in debug builds can
 * read {@link #problems()} and throw on its own.
 */
public final class MonicaAndroidOptions {
  static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
  static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
  static final int DEFAULT_MAX_RETRIES = 2;
  static final int DEFAULT_MAX_BREADCRUMBS = 50;

  private final String dsn;
  private final String environment;
  private final String release;
  private final List<String> inAppPackages;
  private final BeforeSend beforeSend;
  private final double sampleRate;
  private final int maxQueueSize;
  private final int maxBreadcrumbs;
  private final int batchSize;
  private final Duration flushInterval;
  private final Duration flushTimeout;
  private final Duration shutdownTimeout;
  private final Duration requestTimeout;
  private final int maxRetries;
  private final boolean captureUncaughtExceptions;
  private final boolean trackScreens;
  private final boolean attachDeviceContext;
  private final MonicaTransport transport;
  private final MonicaDiagnosticListener onDiagnostic;
  private final List<String> problems;

  private MonicaAndroidOptions(Builder builder) {
    List<String> found = new ArrayList<>();
    // The DSN is checked even when a test supplies its own transport, so a secret key is
    // caught at install time rather than on the first crash.
    try {
      HttpUrlConnectionTransport.publicKeyOf(builder.dsn);
      HttpUrlConnectionTransport.endpointOf(builder.dsn);
    } catch (IllegalArgumentException failure) {
      found.add(failure.getMessage());
    }
    dsn = builder.dsn == null ? "" : builder.dsn.trim();
    environment = builder.environment == null ? "" : builder.environment.trim();
    if (environment.isEmpty()) {
      found.add("environment must not be empty");
    } else if (environment.length() > MonicaOptions.MAX_ENVIRONMENT_LENGTH) {
      found.add("environment must not exceed " + MonicaOptions.MAX_ENVIRONMENT_LENGTH + " characters");
    }
    release = builder.release;
    inAppPackages = Collections.unmodifiableList(new ArrayList<>(builder.inAppPackages));
    beforeSend = builder.beforeSend;
    if (!Double.isFinite(builder.sampleRate) || builder.sampleRate < 0 || builder.sampleRate > 1) {
      found.add("sampleRate must be between 0 and 1");
    }
    sampleRate = builder.sampleRate;
    maxQueueSize = positive(builder.maxQueueSize, "maxQueueSize", found);
    maxBreadcrumbs = positive(builder.maxBreadcrumbs, "maxBreadcrumbs", found);
    batchSize = positive(builder.batchSize, "batchSize", found);
    flushInterval = positive(builder.flushInterval, "flushInterval", found);
    flushTimeout = positive(builder.flushTimeout, "flushTimeout", found);
    shutdownTimeout = positive(builder.shutdownTimeout, "shutdownTimeout", found);
    requestTimeout = positive(builder.requestTimeout, "requestTimeout", found);
    if (builder.maxRetries < 0) found.add("maxRetries must not be negative");
    maxRetries = builder.maxRetries;
    captureUncaughtExceptions = builder.captureUncaughtExceptions;
    trackScreens = builder.trackScreens;
    attachDeviceContext = builder.attachDeviceContext;
    transport = builder.transport;
    onDiagnostic = builder.onDiagnostic;
    problems = Collections.unmodifiableList(found);
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Everything wrong with this configuration, in the words {@code install()} logs. Empty
   * when the SDK can run with it. The SDK itself never throws over these; an application
   * that prefers to fail fast during development can.
   */
  public List<String> problems() {
    return problems;
  }

  /** {@code true} when {@link #problems()} is empty. */
  public boolean isValid() {
    return problems.isEmpty();
  }

  String dsn() {
    return dsn;
  }

  String environment() {
    return environment;
  }

  String release() {
    return release;
  }

  List<String> inAppPackages() {
    return inAppPackages;
  }

  BeforeSend beforeSend() {
    return beforeSend;
  }

  double sampleRate() {
    return sampleRate;
  }

  int maxQueueSize() {
    return maxQueueSize;
  }

  int maxBreadcrumbs() {
    return maxBreadcrumbs;
  }

  int batchSize() {
    return batchSize;
  }

  Duration flushInterval() {
    return flushInterval;
  }

  Duration flushTimeout() {
    return flushTimeout;
  }

  Duration shutdownTimeout() {
    return shutdownTimeout;
  }

  Duration requestTimeout() {
    return requestTimeout;
  }

  int maxRetries() {
    return maxRetries;
  }

  boolean captureUncaughtExceptions() {
    return captureUncaughtExceptions;
  }

  boolean trackScreens() {
    return trackScreens;
  }

  boolean attachDeviceContext() {
    return attachDeviceContext;
  }

  MonicaTransport transport() {
    return transport;
  }

  MonicaDiagnosticListener onDiagnostic() {
    return onDiagnostic;
  }

  private static int positive(int value, String name, List<String> problems) {
    if (value <= 0) problems.add(name + " must be positive");
    return value;
  }

  private static Duration positive(Duration value, String name, List<String> problems) {
    if (value == null || value.isZero() || value.isNegative()) {
      problems.add(name + " must be positive");
    }
    return value;
  }

  public static final class Builder {
    private String dsn;
    private String environment;
    private String release;
    private final List<String> inAppPackages = new ArrayList<>();
    private BeforeSend beforeSend;
    private double sampleRate = 1;
    private int maxQueueSize = 100;
    private int maxBreadcrumbs = DEFAULT_MAX_BREADCRUMBS;
    private int batchSize = 30;
    private Duration flushInterval = Duration.ofSeconds(5);
    private Duration flushTimeout = Duration.ofSeconds(2);
    private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
    private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private boolean captureUncaughtExceptions = true;
    private boolean trackScreens = true;
    private boolean attachDeviceContext = true;
    private MonicaTransport transport;
    private MonicaDiagnosticListener onDiagnostic;

    /** The project DSN. It must carry a public {@code mpk_} key. */
    public Builder dsn(String value) {
      this.dsn = value;
      return this;
    }

    /** Required, at most {@value MonicaOptions#MAX_ENVIRONMENT_LENGTH} characters after trimming. */
    public Builder environment(String value) {
      this.environment = value;
      return this;
    }

    public Builder release(String value) {
      this.release = value;
      return this;
    }

    /**
     * Marks frames as {@code in_app}. When nothing is listed, the application's own
     * package name is used.
     */
    public Builder inAppPackage(String value) {
      if (value != null && !value.trim().isEmpty()) inAppPackages.add(value.trim());
      return this;
    }

    public Builder inAppPackages(Iterable<String> values) {
      if (values != null) values.forEach(this::inAppPackage);
      return this;
    }

    /** Decides what actually leaves the device. PII removal belongs here. */
    public Builder beforeSend(BeforeSend value) {
      this.beforeSend = value;
      return this;
    }

    public Builder sampleRate(double value) {
      this.sampleRate = value;
      return this;
    }

    public Builder maxQueueSize(int value) {
      this.maxQueueSize = value;
      return this;
    }

    public Builder maxBreadcrumbs(int value) {
      this.maxBreadcrumbs = value;
      return this;
    }

    public Builder batchSize(int value) {
      this.batchSize = value;
      return this;
    }

    public Builder flushInterval(Duration value) {
      this.flushInterval = value;
      return this;
    }

    /** How long {@code flush()} without an argument and {@code close()} wait for the queue. */
    public Builder flushTimeout(Duration value) {
      this.flushTimeout = value;
      return this;
    }

    /**
     * How long a crashing thread waits for the fatal event to be sent. The transport
     * treats it as the deadline for that envelope, so a {@code requestTimeout} longer
     * than this is cut down to what is left of it.
     */
    public Builder shutdownTimeout(Duration value) {
      this.shutdownTimeout = value;
      return this;
    }

    public Builder requestTimeout(Duration value) {
      this.requestTimeout = value;
      return this;
    }

    public Builder maxRetries(int value) {
      this.maxRetries = value;
      return this;
    }

    public Builder captureUncaughtExceptions(boolean value) {
      this.captureUncaughtExceptions = value;
      return this;
    }

    public Builder trackScreens(boolean value) {
      this.trackScreens = value;
      return this;
    }

    public Builder attachDeviceContext(boolean value) {
      this.attachDeviceContext = value;
      return this;
    }

    /**
     * Replaces the HTTP transport. Intended for tests.
     *
     * <p>A transport supplied here is used as it is, so {@link #onDiagnostic} does not
     * reach it; only the built-in {@code HttpUrlConnectionTransport} reads error bodies.
     */
    public Builder transport(MonicaTransport value) {
      this.transport = value;
      return this;
    }

    /**
     * Replaces what happens when ingest refuses an envelope.
     *
     * <p>By default a {@code 422} — and the {@code 401} that stops sending — is written
     * to logcat under the {@code MONICA} tag, spelling out the {@code issues} paths that
     * say which field ingest rejected. That default is on because those paths are the
     * only way to find out that, say, a {@code beforeSend} allowlist has been dropping a
     * required field and nothing has arrived since.
     *
     * <p>Setting a listener replaces the log line, so {@code onDiagnostic(d -> {})}
     * silences it, and a listener of one's own additionally sees the {@code 4xx} the
     * default stays quiet about ({@code 400}, {@code 413}). It runs on the sender thread
     * and must not block.
     *
     * @param value the listener, or {@code null} to keep the default log line
     * @return this builder
     * @see MonicaDiagnostic
     */
    public Builder onDiagnostic(MonicaDiagnosticListener value) {
      this.onDiagnostic = value;
      return this;
    }

    /** Never throws; anything the SDK could not run with ends up in {@link #problems()}. */
    public MonicaAndroidOptions build() {
      return new MonicaAndroidOptions(this);
    }
  }
}
