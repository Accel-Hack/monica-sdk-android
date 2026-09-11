package com.accelhack.monica.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class MonicaAndroidOptionsTest {
  private static MonicaAndroidOptions.Builder valid() {
    return MonicaAndroidOptions.builder()
        .dsn("https://mpk_public@ingest.monica.test/1")
        .environment("production");
  }

  /** build() never throws; the problem has to show up, by name, in problems(). */
  private static void rejects(String name, Consumer<MonicaAndroidOptions.Builder> mutate) {
    MonicaAndroidOptions.Builder builder = valid();
    mutate.accept(builder);
    MonicaAndroidOptions options = builder.build();
    assertFalse(options.isValid(), name + " must be reported at build time");
    assertTrue(options.problems().stream().anyMatch(problem -> problem.contains(name)),
        name + " missing from " + options.problems());
  }

  @Test
  void reportsASecretKeyEvenWhenATransportIsSupplied() {
    MonicaAndroidOptions options = MonicaAndroidOptions.builder()
        .dsn("https://msk_secret@ingest.monica.test/1")
        .environment("production")
        .transport(new RecordingTransport())
        .build();
    assertFalse(options.isValid());
    assertEquals(1, options.problems().size());
    assertTrue(options.problems().get(0).contains("mpk_"), options.problems().get(0));
  }

  @Test
  void requiresADsnAndAnEnvironmentWithoutThrowing() {
    MonicaAndroidOptions noDsn = MonicaAndroidOptions.builder().environment("production").build();
    assertEquals(1, noDsn.problems().size());
    assertTrue(noDsn.problems().get(0).contains("dsn"));
    MonicaAndroidOptions noEnvironment = MonicaAndroidOptions.builder()
        .dsn("https://mpk_public@ingest.monica.test/1").build();
    assertEquals(java.util.Collections.singletonList("environment must not be empty"),
        noEnvironment.problems());
    rejects("environment", builder -> builder.environment("   "));
    // Nothing set at all: every problem is listed, none of them throws.
    MonicaAndroidOptions empty = MonicaAndroidOptions.builder().build();
    assertEquals(2, empty.problems().size());
  }

  @Test
  void trimsTheEnvironmentAndBoundsItLikeTheSchema() {
    assertEquals("production", valid().environment("  production ").build().environment());
    assertEquals(128, valid().environment("e".repeat(128)).build().environment().length());
    rejects("environment", builder -> builder.environment("e".repeat(129)));
  }

  @Test
  void aValidConfigurationHasNoProblems() {
    MonicaAndroidOptions options = valid().build();
    assertTrue(options.isValid());
    assertTrue(options.problems().isEmpty());
  }

  @Test
  void everyMisconfigurationIsReportedAtBuildTimeNotAtInstall() {
    // Neither build() nor install() throws, so build() has to name all of these.
    rejects("shutdownTimeout", builder -> builder.shutdownTimeout(Duration.ZERO));
    rejects("requestTimeout", builder -> builder.requestTimeout(Duration.ofSeconds(-1)));
    rejects("flushInterval", builder -> builder.flushInterval(null));
    rejects("flushInterval", builder -> builder.flushInterval(Duration.ZERO));
    rejects("flushTimeout", builder -> builder.flushTimeout(Duration.ofMillis(-1)));
    rejects("maxQueueSize", builder -> builder.maxQueueSize(0));
    rejects("batchSize", builder -> builder.batchSize(-5));
    rejects("maxBreadcrumbs", builder -> builder.maxBreadcrumbs(0));
    rejects("maxRetries", builder -> builder.maxRetries(-1));
    rejects("sampleRate", builder -> builder.sampleRate(1.5));
    rejects("sampleRate", builder -> builder.sampleRate(-0.1));
    rejects("sampleRate", builder -> builder.sampleRate(Double.NaN));
  }

  @Test
  void defaultsFavourGettingTheCrashOutBeforeTheProcessDies() {
    MonicaAndroidOptions options = valid().build();
    assertEquals(Duration.ofSeconds(5), options.shutdownTimeout());
    assertEquals(Duration.ofSeconds(10), options.requestTimeout());
    assertEquals(Duration.ofSeconds(2), options.flushTimeout());
    assertEquals(2, options.maxRetries());
    assertEquals(50, options.maxBreadcrumbs());
    assertTrue(options.captureUncaughtExceptions());
    assertTrue(options.trackScreens());
    assertTrue(options.attachDeviceContext());
  }

  @Test
  void keepsInAppPackagesInTheOrderTheyWereDeclared() {
    MonicaAndroidOptions options = valid()
        .inAppPackage(" com.example.app ")
        .inAppPackage("")
        .inAppPackages(java.util.Arrays.asList("com.example.lib", null))
        .build();
    assertEquals(java.util.Arrays.asList("com.example.app", "com.example.lib"),
        options.inAppPackages());
  }
}
