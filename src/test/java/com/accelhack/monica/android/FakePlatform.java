package com.accelhack.monica.android;

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
  public void warn(String message, Throwable failure) {
    warnings.add(message + (failure == null ? "" : ": " + failure));
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
}
