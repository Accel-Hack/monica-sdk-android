package com.accelhack.monica.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What can be checked of the framework-facing class without a device. The compile
 * stubs throw from every method, so nothing here constructs an Activity or Application;
 * {@link AndroidCompatibilityTest} inspects the rest of this class at the bytecode level.
 */
class ContextPlatformTest {
  @Test
  void mapsLifecycleCallbacksToTheFourTransitionsWorthABreadcrumb() {
    List<String> seen = new ArrayList<>();
    ContextPlatform.LifecycleCallbacks callbacks = new ContextPlatform.LifecycleCallbacks(
        (lifecycle, screen) -> seen.add(screen + "." + lifecycle));

    callbacks.onActivityCreated(null, null);
    callbacks.onActivityStarted(null);
    callbacks.onActivityResumed(null);
    callbacks.onActivitySaveInstanceState(null, null);
    callbacks.onActivityPaused(null);
    callbacks.onActivityStopped(null);
    callbacks.onActivityDestroyed(null);

    // started/stopped are no breadcrumbs, only the first start is a return to the
    // foreground; saveInstanceState is silent; a null Activity is named rather than
    // dereferenced.
    assertEquals(Arrays.asList("unknown.created", "unknown.foreground", "unknown.resumed",
        "unknown.paused", "unknown.destroyed"), seen);
  }

  @Test
  void reportsTheForegroundOnlyWhenTheFirstActivityStartsAfterNone() {
    List<String> seen = new ArrayList<>();
    ContextPlatform.LifecycleCallbacks callbacks = new ContextPlatform.LifecycleCallbacks(
        (lifecycle, screen) -> { if ("foreground".equals(lifecycle)) seen.add(lifecycle); });

    callbacks.onActivityStarted(null);
    callbacks.onActivityStarted(null);  // a second Activity on top: still in the foreground
    callbacks.onActivityStopped(null);
    assertEquals(1, seen.size());
    callbacks.onActivityStopped(null);  // the app went to the background
    callbacks.onActivityStopped(null);  // an unmatched stop must not drive the count negative
    callbacks.onActivityStarted(null);
    assertEquals(2, seen.size());
  }

  @Test
  void aListenerThatThrowsDoesNotBreakTheActivityLifecycle() {
    ContextPlatform.LifecycleCallbacks callbacks = new ContextPlatform.LifecycleCallbacks(
        (lifecycle, screen) -> { throw new IllegalStateException("listener bug"); });
    callbacks.onActivityResumed(null);
    callbacks.onActivityDestroyed(null);
  }

  @Test
  void aRotationIsNoReturnToTheForeground() {
    List<String> seen = new ArrayList<>();
    ContextPlatform.LifecycleCallbacks callbacks = new ContextPlatform.LifecycleCallbacks(
        (lifecycle, screen) -> { if ("foreground".equals(lifecycle)) seen.add(lifecycle); });

    callbacks.onActivityStarted(null);
    callbacks.stopped(true);            // the old Activity, torn down for the rotation
    callbacks.onActivityStarted(null);  // the recreated one
    assertEquals(1, seen.size());
    callbacks.stopped(false);           // the count survived: this one reaches zero
    callbacks.onActivityStarted(null);
    assertEquals(2, seen.size());
  }

  @Test
  void keepsThePresenceStateInSharedPreferences() {
    FakeSharedPreferences preferences = new FakeSharedPreferences();
    ContextPlatform.PreferencesPresenceStore store =
        new ContextPlatform.PreferencesPresenceStore(preferences);
    assertNull(store.getLastReportedAt(), "nothing stored reads as null, not 0");
    assertNull(store.getIntervalMillis());
    assertNull(store.getSampleRate());

    store.setLastReportedAt(1_756_512_000_000L);
    store.setIntervalMillis(3_600_000L);
    store.setSampleRate(0.25);

    assertEquals(1_756_512_000_000L, preferences.values.get(ContextPlatform.PRESENCE_LAST_REPORTED_AT));
    assertEquals(3_600_000L, preferences.values.get(ContextPlatform.PRESENCE_INTERVAL_MILLIS));
    assertEquals("0.25", preferences.values.get(ContextPlatform.PRESENCE_SAMPLE_RATE));
    ContextPlatform.PreferencesPresenceStore reread =
        new ContextPlatform.PreferencesPresenceStore(preferences);
    assertEquals(1_756_512_000_000L, reread.getLastReportedAt());
    assertEquals(3_600_000L, reread.getIntervalMillis());
    assertEquals(0.25, reread.getSampleRate());

    preferences.values.put(ContextPlatform.PRESENCE_SAMPLE_RATE, "not a number");
    assertNull(reread.getSampleRate(), "a corrupted rate reads as nothing stored");
  }

  @Test
  void refusesANullContext() {
    assertThrows(IllegalArgumentException.class, () -> ContextPlatform.from(null));
    assertThrows(IllegalArgumentException.class, () -> AndroidPlatform.of(null));
  }
}
