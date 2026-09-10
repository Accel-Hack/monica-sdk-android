package com.accelhack.monica.android;

import android.content.Context;
import com.accelhack.monica.CaptureContext;
import com.accelhack.monica.MonicaClient;
import com.accelhack.monica.MonicaStats;
import com.accelhack.monica.MonicaTransport;
import com.accelhack.monica.Scope;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The MONICA integration for an Android application.
 *
 * <p>Install it once, normally from {@code Application#onCreate}:
 *
 * <pre>{@code
 * MonicaAndroid.install(this, MonicaAndroidOptions.builder()
 *     .dsn(BuildConfig.MONICA_DSN)
 *     .environment(BuildConfig.DEBUG ? "development" : "production")
 *     .release(BuildConfig.VERSION_NAME)
 *     .build());
 * }</pre>
 *
 * <p>Initialisation is explicit. Nothing starts sending because the library is on the
 * classpath.
 *
 * <p>Nothing in here throws into the application. A configuration mistake, a
 * {@code null} argument or a failure while installing is written to logcat under the
 * {@code MONICA} tag and leaves the SDK disabled; every other failure is swallowed the
 * same way and turns the affected call into a no-op. {@link #current()} never returns
 * {@code null}: before an install, after a failed one and after {@link #close()} it
 * returns an instance that does nothing, so code copied from the README cannot NPE.
 */
public final class MonicaAndroid implements AutoCloseable {
  static final String SDK_NAME = "com.accelhack.monica:monica-android";
  static final String SDK_VERSION = "0.1.0";

  private static final Object INSTALL_LOCK = new Object();
  private static final MonicaAndroid DISABLED = new MonicaAndroid(null, null, null, null);
  private static volatile MonicaAndroid current = DISABLED;

  /** {@code null} only on the disabled instance. */
  private final MonicaClient client;
  private final AndroidPlatform platform;
  private final Duration closeTimeout;
  private final UncaughtExceptionCapture uncaughtExceptionCapture;
  private final Scope detachedScope = new Scope();
  private volatile AutoCloseable screenTracking;
  private volatile boolean closed;

  private MonicaAndroid(MonicaClient client, AndroidPlatform platform, Duration closeTimeout,
      UncaughtExceptionCapture uncaughtExceptionCapture) {
    this.client = client;
    this.platform = platform;
    this.closeTimeout = closeTimeout;
    this.uncaughtExceptionCapture = uncaughtExceptionCapture;
  }

  /**
   * Installs the integration for a live Android {@link Context}. Never throws.
   *
   * @return the installed integration, or one that does nothing when the arguments or the
   *     options are unusable; the reason is in logcat under {@code MONICA}
   */
  public static MonicaAndroid install(Context context, MonicaAndroidOptions options) {
    if (context == null) {
      warn(null, "install(context, options) was given a null Context; MONICA is disabled", null);
      return replaceCurrent(DISABLED);
    }
    AndroidPlatform platform;
    try {
      platform = AndroidPlatform.of(context);
    } catch (Throwable failure) {
      warn(null, "install failed while reading the Context; MONICA is disabled", failure);
      return replaceCurrent(DISABLED);
    }
    return install(platform, options);
  }

  /**
   * Installs the integration on any {@link AndroidPlatform}.
   *
   * <p>A second install replaces the first: the previous instance is closed, which
   * flushes it for up to its {@code flushTimeout} and restores the crash handler it had
   * replaced. Like {@link #close()}, that is a blocking call.
   *
   * @return the installed integration, or one that does nothing when the arguments or the
   *     options are unusable or installation failed; the reason is in logcat under
   *     {@code MONICA}
   */
  public static MonicaAndroid install(AndroidPlatform platform, MonicaAndroidOptions options) {
    if (platform == null) {
      warn(null, "install(platform, options) was given a null platform; MONICA is disabled", null);
      return replaceCurrent(DISABLED);
    }
    if (options == null) {
      warn(platform, "install() was given null options; MONICA is disabled", null);
      return replaceCurrent(DISABLED);
    }
    if (!options.isValid()) {
      // A misconfigured monitoring SDK is not worth a crash. Say why, then stay quiet.
      warn(platform, "MONICA is disabled because its options are invalid: "
          + String.join("; ", options.problems()), null);
      return replaceCurrent(DISABLED);
    }
    synchronized (INSTALL_LOCK) {
      current.close();
      MonicaAndroid installed;
      try {
        installed = create(platform, options);
      } catch (Throwable failure) {
        warn(platform, "install failed; MONICA is disabled until the next install", failure);
        installed = DISABLED;
      }
      current = installed;
      return installed;
    }
  }

  private static MonicaAndroid replaceCurrent(MonicaAndroid replacement) {
    synchronized (INSTALL_LOCK) {
      current.close();
      current = replacement;
      return replacement;
    }
  }

  private static MonicaAndroid create(AndroidPlatform platform, MonicaAndroidOptions options) {
    AndroidEnvironment environment = platform.environment();
    MonicaClient client = buildClient(platform, options, environment);
    try {
      if (options.attachDeviceContext() && environment != null) {
        environment.applyTo(client.globalScope());
      }
      UncaughtExceptionCapture capture = null;
      if (options.captureUncaughtExceptions()) {
        capture = new UncaughtExceptionCapture(client,
            Thread.getDefaultUncaughtExceptionHandler(), options.shutdownTimeout());
      }
      MonicaAndroid installed = new MonicaAndroid(client, platform, options.flushTimeout(), capture);
      if (options.trackScreens()) {
        try {
          installed.screenTracking = platform.trackScreens(installed::onScreen);
        } catch (Throwable failure) {
          // Screen breadcrumbs are a nicety. Losing them must not cost the crash handler.
          warn(platform, "Activity transitions are not tracked", failure);
        }
      }
      // The one step with a global side effect goes last, so nothing after it can fail
      // and leave a handler installed that no instance owns.
      if (capture != null) Thread.setDefaultUncaughtExceptionHandler(capture);
      return installed;
    } catch (Throwable failure) {
      try {
        client.close(Duration.ZERO);
      } catch (Throwable ignored) {
        // The sender thread is a daemon; leaking it is the lesser evil here.
      }
      throw failure;
    }
  }

  /**
   * The installed integration. Never {@code null}: before an install, after a failed
   * install and after {@link #close()} this is an instance whose methods do nothing.
   */
  public static MonicaAndroid current() {
    return current;
  }

  /** Whether this instance is backed by a live client, as opposed to the disabled one. */
  public boolean isInstalled() {
    return client != null && !closed;
  }

  /** The core client, or {@code null} on the disabled instance. */
  public MonicaClient client() {
    return client;
  }

  /**
   * The scope applied to every event: tags, contexts and breadcrumbs. On the disabled
   * instance this is a scope nothing reads, so writing to it is harmless.
   */
  public Scope scope() {
    try {
      return client == null ? detachedScope : client.globalScope();
    } catch (Throwable failure) {
      return detachedScope;
    }
  }

  public String captureException(Throwable throwable) {
    try {
      return client == null ? null : client.captureException(throwable);
    } catch (Throwable failure) {
      return swallow("captureException", failure);
    }
  }

  public String captureException(Throwable throwable, CaptureContext context) {
    try {
      return client == null ? null : client.captureException(throwable, context);
    } catch (Throwable failure) {
      return swallow("captureException", failure);
    }
  }

  public String captureMessage(String message) {
    try {
      return client == null ? null : client.captureMessage(message);
    } catch (Throwable failure) {
      return swallow("captureMessage", failure);
    }
  }

  public String captureMessage(String message, CaptureContext context) {
    try {
      return client == null ? null : client.captureMessage(message, context);
    } catch (Throwable failure) {
      return swallow("captureMessage", failure);
    }
  }

  public void addBreadcrumb(String category, String message) {
    try {
      if (client != null) client.globalScope().addBreadcrumb(category, message);
    } catch (Throwable failure) {
      swallow("addBreadcrumb", failure);
    }
  }

  /**
   * Identifies the person using the application. Nothing is sent about them until this
   * is called.
   */
  public void setUser(String id) {
    Map<String, Object> user = new LinkedHashMap<>();
    if (id != null) user.put("id", id);
    setUser(user);
  }

  /** Identifies the person using the application with whatever fields apply. */
  public void setUser(Map<String, Object> user) {
    try {
      if (client != null) client.globalScope().setUser(user);
    } catch (Throwable failure) {
      swallow("setUser", failure);
    }
  }

  /** Records the screen the application considers itself on. */
  public void setScreen(String screen) {
    try {
      if (client != null && screen != null) client.globalScope().setTag("screen", screen);
    } catch (Throwable failure) {
      swallow("setScreen", failure);
    }
  }

  /**
   * Blocks until the queue has been sent or the timeout passes. Do not call it on the
   * main thread; the sender thread does the network I/O, but this call waits for it.
   *
   * @return {@code false} when something was not accepted in time, or nothing is installed
   */
  public boolean flush(Duration timeout) {
    try {
      return client != null && client.flush(timeout);
    } catch (Throwable failure) {
      swallow("flush", failure);
      return false;
    }
  }

  public MonicaStats stats() {
    try {
      return client == null ? new MonicaStats(0, 0) : client.stats();
    } catch (Throwable failure) {
      swallow("stats", failure);
      return new MonicaStats(0, 0);
    }
  }

  /**
   * Flushes for up to the {@code flushTimeout}, removes the crash handler and the
   * lifecycle callbacks, and stops sending.
   *
   * <p>This blocks for the flush, so it is not for the main thread: the default 2 seconds
   * are below the ANR threshold, but a longer {@code flushTimeout} may not be. Closing
   * is idempotent and the disabled instance closes as a no-op.
   */
  @Override
  public void close() {
    close(closeTimeout);
  }

  /** {@link #close()} with an explicit bound on how long the flush may block. */
  public void close(Duration timeout) {
    if (client == null) return;
    synchronized (INSTALL_LOCK) {
      if (closed) return;
      closed = true;
      try {
        AutoCloseable tracking = screenTracking;
        if (tracking != null) {
          screenTracking = null;
          try {
            tracking.close();
          } catch (Throwable failure) {
            swallow("close (screen tracking)", failure);
          }
        }
        restoreUncaughtExceptionHandler();
        client.close(timeout == null ? closeTimeout : timeout);
      } catch (Throwable failure) {
        swallow("close", failure);
      } finally {
        if (current == this) current = DISABLED;
      }
    }
  }

  private void restoreUncaughtExceptionHandler() {
    if (uncaughtExceptionCapture == null) return;
    // Only step aside if nothing else has been installed on top of us; otherwise the
    // other handler would silently lose its own delegate.
    if (Thread.getDefaultUncaughtExceptionHandler() == uncaughtExceptionCapture) {
      Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionCapture.delegate());
    }
  }

  private void onScreen(String lifecycle, String screen) {
    try {
      if (closed || client == null) return;
      client.globalScope().addBreadcrumb("ui.lifecycle", screen + "." + lifecycle);
      if ("resumed".equals(lifecycle)) setScreen(screen);
    } catch (Throwable failure) {
      swallow("onScreen", failure);
    }
  }

  private <T> T swallow(String operation, Throwable failure) {
    warn(platform, operation + " failed inside MONICA; the call did nothing", failure);
    return null;
  }

  private static void warn(AndroidPlatform platform, String message, Throwable failure) {
    try {
      if (platform != null) {
        platform.warn(message, failure);
      } else {
        // Before a platform exists there is only stderr, which logcat still shows.
        System.err.println("MONICA: " + message);
        if (failure != null) failure.printStackTrace();
      }
    } catch (Throwable ignored) {
      // There is nothing left to report to.
    }
  }

  /**
   * What the transport does with a rejection ingest explained. The configured listener
   * wins; without one, a {@code 422} and the {@code 401} that stops sending become a
   * logcat line, because those field paths are the only sign that a {@code beforeSend}
   * has been dropping a required field and nothing has arrived since. A {@code 400} or
   * {@code 413} only reaches a listener of the integrator's own: the SDK has nothing to
   * add to them, and logcat noise on every event is how a warning gets ignored.
   */
  private static MonicaDiagnosticListener diagnosticListener(AndroidPlatform platform,
      MonicaAndroidOptions options) {
    if (options.onDiagnostic() != null) return options.onDiagnostic();
    return diagnostic -> {
      if (diagnostic.status() == 422 || diagnostic.stopped()) {
        warn(platform, diagnostic.describe(), null);
      }
    };
  }

  private static MonicaClient buildClient(AndroidPlatform platform,
      MonicaAndroidOptions options, AndroidEnvironment environment) {
    MonicaTransport transport = options.transport() != null ? options.transport()
        : new HttpUrlConnectionTransport(options.dsn(), options.maxRetries(),
            options.requestTimeout(),
            options.captureUncaughtExceptions() ? options.shutdownTimeout() : null,
            diagnosticListener(platform, options));
    MonicaClient.Builder builder = MonicaClient.builder()
        .sdk(SDK_NAME, SDK_VERSION)
        .environment(options.environment())
        .beforeSend(options.beforeSend())
        .sampleRate(options.sampleRate())
        .maxQueueSize(options.maxQueueSize())
        .maxBreadcrumbs(options.maxBreadcrumbs())
        .batchSize(options.batchSize())
        .flushInterval(options.flushInterval())
        .flushTimeout(options.flushTimeout())
        .transport(transport);
    String release = options.release() != null ? options.release()
        : environment == null ? null : environment.versionName();
    if (release != null) builder.release(release);
    if (!options.inAppPackages().isEmpty()) {
      builder.inAppPackages(options.inAppPackages());
    } else if (environment != null && environment.packageName() != null) {
      builder.inAppPackage(environment.packageName());
    }
    return builder.build();
  }
}
