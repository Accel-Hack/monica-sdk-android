package com.accelhack.monica.android;

import android.content.Context;

/**
 * Everything {@link MonicaAndroid} needs from the Android framework.
 *
 * <p>Keeping the framework behind this interface is what makes the integration
 * testable: the classes that decide <em>what</em> to send hold no android types, and
 * the one class that does — {@code ContextPlatform} — only reads values and writes to
 * the log.
 */
public interface AndroidPlatform {
  /** Device, OS and application facts read once at install time. */
  AndroidEnvironment environment();

  /**
   * Starts reporting Activity transitions.
   *
   * @return a handle that stops the reporting, never {@code null}
   */
  AutoCloseable trackScreens(ScreenListener listener);

  /**
   * Records a failure the SDK swallowed instead of letting it reach the application.
   *
   * <p>The SDK never throws into the host, so this is the only way an integrator can
   * learn why events are not arriving. The default keeps tests quiet; the real platform
   * writes a line to logcat under the {@code MONICA} tag.
   *
   * <p>A rejection ingest explained comes through here too, not only a swallowed
   * failure: it is how the default {@link MonicaDiagnosticListener} reports a {@code 422}
   * and the {@code 401} that stops sending, which is also what lets a test read those
   * warnings off a fake platform instead of logcat.
   */
  default void warn(String message, Throwable failure) {
    // Silent unless the platform decides otherwise.
  }

  /** Wraps a live Android {@link Context}. */
  static AndroidPlatform of(Context context) {
    return ContextPlatform.from(context);
  }
}
