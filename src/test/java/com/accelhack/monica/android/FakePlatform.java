package com.accelhack.monica.android;

import com.accelhack.monica.MonicaPresenceStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class FakePlatform implements AndroidPlatform {
  /** The package the tests themselves live in, so their own frames can be {@code in_app}. */
  static final String TEST_PACKAGE = "com.accelhack.monica.android";

  private final AndroidEnvironment environment;
  private final List<ScreenListener> listeners = new ArrayList<>();
  private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
  private boolean failTracking;
  /**
   * Starts out as a device that reported a moment ago, so the start heartbeat stays out of
   * the tests that are about errors; {@link #neverReported()} is the fresh install.
   */
  private MemoryPresenceStore presence = alreadyReported();

  FakePlatform() {
    this("com.example.app");
  }

  FakePlatform(String packageName) {
    this(new AndroidEnvironment("Google", "google", "Pixel 8", "14", 34,
        packageName, "2.3.1", 231));
  }

  FakePlatform(AndroidEnvironment environment) {
    this.environment = environment;
  }

  @Override
  public AndroidEnvironment environment() {
    return environment;
  }

  /**
   * Mirrors the real platform: the handle removes only the callbacks it registered,
   * so a new tracker registered before the old handle is closed keeps working.
   */
  @Override
  public AutoCloseable trackScreens(ScreenListener listener) {
    if (failTracking) throw new IllegalStateException("lifecycle callbacks unavailable");
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  @Override
  public MonicaPresenceStore presenceStore() {
    return presence;
  }

  @Override
  public void warn(String message, Throwable failure) {
    warnings.add(message + (failure == null ? "" : ": " + failure));
  }

  FakePlatform neverReported() {
    presence = new MemoryPresenceStore();
    return this;
  }

  MemoryPresenceStore presence() {
    return presence;
  }

  /** For clients built without a platform, which would otherwise send a start first. */
  static MemoryPresenceStore alreadyReported() {
    MemoryPresenceStore store = new MemoryPresenceStore();
    store.setLastReportedAt(System.currentTimeMillis());
    return store;
  }

  FakePlatform failTracking() {
    failTracking = true;
    return this;
  }

  boolean tracking() {
    return !listeners.isEmpty();
  }

  List<String> warnings() {
    return warnings;
  }

  void emit(String lifecycle, String screen) {
    for (ScreenListener listener : new ArrayList<>(listeners)) listener.onScreen(lifecycle, screen);
  }

  /** SharedPreferences in memory: all three values, the sample rate included. */
  static final class MemoryPresenceStore implements MonicaPresenceStore {
    volatile Long lastReportedAt;
    volatile Long intervalMillis;
    volatile Double sampleRate;

    @Override
    public Long getLastReportedAt() {
      return lastReportedAt;
    }

    @Override
    public void setLastReportedAt(long epochMillis) {
      lastReportedAt = epochMillis;
    }

    @Override
    public Long getIntervalMillis() {
      return intervalMillis;
    }

    @Override
    public void setIntervalMillis(long value) {
      intervalMillis = value;
    }

    @Override
    public Double getSampleRate() {
      return sampleRate;
    }

    @Override
    public void setSampleRate(double value) {
      sampleRate = value;
    }
  }
}
