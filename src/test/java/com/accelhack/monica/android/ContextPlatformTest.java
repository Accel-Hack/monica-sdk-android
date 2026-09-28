package com.accelhack.monica.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  void refusesANullContext() {
    assertThrows(IllegalArgumentException.class, () -> ContextPlatform.from(null));
    assertThrows(IllegalArgumentException.class, () -> AndroidPlatform.of(null));
  }
}
