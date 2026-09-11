package com.accelhack.monica.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.accelhack.monica.CaptureContext;
import com.accelhack.monica.MonicaEvent;
import com.accelhack.monica.MonicaTransport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/** Touches the process-wide install slot and the default crash handler, hence isolated. */
@Isolated
class MonicaAndroidTest {
  private final Thread.UncaughtExceptionHandler original =
      Thread.getDefaultUncaughtExceptionHandler();

  @AfterEach
  void restore() {
    MonicaAndroid.current().close();
    Thread.setDefaultUncaughtExceptionHandler(original);
  }

  private static MonicaAndroidOptions.Builder options(MonicaTransport transport) {
    return MonicaAndroidOptions.builder()
        .dsn("https://mpk_public@ingest.monica.test/1")
        .environment("test")
        .transport(transport)
        .captureUncaughtExceptions(false)
        .trackScreens(false);
  }

  @Test
  void attachesDeviceOsAndAppContextWithoutIdentifyingTheInstall() {
    RecordingTransport transport = new RecordingTransport();
    MonicaAndroid monica = MonicaAndroid.install(new FakePlatform(),
        options(transport).build());

    monica.captureMessage("boom");
    assertTrue(monica.flush(Duration.ofSeconds(1)));

    MonicaEvent item = transport.only();
    assertEquals("java", item.get("platform"));
    assertEquals("2.3.1", item.get("release"));
    assertEquals("com.accelhack.monica:monica-android",
        transport.envelopes().get(0).getSdk().get("name"));

    Map<String, Object> contexts = contexts(item);
    assertEquals("Pixel 8", value(contexts, "device", "model"));
    assertEquals("Google", value(contexts, "device", "manufacturer"));
    assertEquals("Android", value(contexts, "os", "name"));
    assertEquals(34, value(contexts, "os", "api_level"));
    assertEquals("com.example.app", value(contexts, "app", "app_identifier"));
    assertEquals("231", value(contexts, "app", "app_build"));
    assertNull(item.get("user"));
  }

  @Test
  void marksTheApplicationsOwnPackageAsInApp() {
    RecordingTransport transport = new RecordingTransport();
    // The fake application is this test's own package, so the test frame is the app's.
    MonicaAndroid monica = MonicaAndroid.install(new FakePlatform(FakePlatform.TEST_PACKAGE),
        options(transport).build());

    monica.captureException(new IllegalStateException("boom"));
    assertTrue(monica.flush(Duration.ofSeconds(1)));

    assertEquals(Boolean.TRUE, inAppOfTestFrame(transport.only()));
    assertTrue(anyFrame(transport.only(), Boolean.FALSE), "JUnit's own frames stay out of app");
  }

  @Test
  void leavesFramesOutsideTheApplicationsPackageOutOfApp() {
    RecordingTransport transport = new RecordingTransport();
    MonicaAndroid monica = MonicaAndroid.install(new FakePlatform("com.example.app"),
        options(transport).build());

    monica.captureException(new IllegalStateException("boom"));
    assertTrue(monica.flush(Duration.ofSeconds(1)));

    assertEquals(Boolean.FALSE, inAppOfTestFrame(transport.only()));
    assertFalse(anyFrame(transport.only(), Boolean.TRUE));
  }

  @Test
  void anExplicitInAppPackageReplacesTheDetectedOneInsteadOfJoiningIt() {
    RecordingTransport transport = new RecordingTransport();
    // Detected: this package. Explicit: another. The test frame must not be in_app.
    MonicaAndroid monica = MonicaAndroid.install(new FakePlatform(FakePlatform.TEST_PACKAGE),
        options(transport).inAppPackage("com.example.other").build());

    monica.captureException(new IllegalStateException("boom"));
    assertTrue(monica.flush(Duration.ofSeconds(1)));

    assertEquals(Boolean.FALSE, inAppOfTestFrame(transport.only()));
  }

  @Test
  void recordsScreenTransitionsAsBreadcrumbsAndATag() {
    RecordingTransport transport = new RecordingTransport();
    FakePlatform platform = new FakePlatform();
    MonicaAndroid monica = MonicaAndroid.install(platform,
        options(transport).trackScreens(true).build());
    assertTrue(platform.tracking());

    platform.emit("created", "CheckoutActivity");
    platform.emit("resumed", "CheckoutActivity");
    // Leaving a screen is a breadcrumb but must not move the tag: the crash that follows
    // happened on the screen the user still sees.
    platform.emit("paused", "CheckoutActivity");
    platform.emit("created", "ReceiptActivity");
    monica.captureMessage("boom");
    assertTrue(monica.flush(Duration.ofSeconds(1)));

    MonicaEvent item = transport.only();
    assertEquals("CheckoutActivity", tags(item).get("screen"));
    List<?> breadcrumbs = (List<?>) item.get("breadcrumbs");
    assertEquals(4, breadcrumbs.size());
    @SuppressWarnings("unchecked")
    Map<String, Object> first = (Map<String, Object>) breadcrumbs.get(0);
    assertEquals("ui.lifecycle", first.get("category"));
    assertEquals("CheckoutActivity.created", first.get("message"));
    @SuppressWarnings("unchecked")
    Map<String, Object> last = (Map<String, Object>) breadcrumbs.get(3);
    assertEquals("ReceiptActivity.created", last.get("message"));
  }

  @Test
  void ignoresScreenTransitionsAfterClose() {
    RecordingTransport transport = new RecordingTransport();
    FakePlatform platform = new FakePlatform();
    MonicaAndroid monica = MonicaAndroid.install(platform,
        options(transport).trackScreens(true).build());
    monica.close();

    // The real platform unregisters, but a callback already in flight may still land.
    platform.emit("resumed", "LateActivity");
    assertEquals(0, transport.items().size());
    assertFalse(platform.tracking());
  }

  @Test
  void keepsOnlyTheMostRecentBreadcrumbs() {
    RecordingTransport transport = new RecordingTransport();
    MonicaAndroid monica = MonicaAndroid.install(new FakePlatform(),
        options(transport).maxBreadcrumbs(3).build());

    for (int index = 0; index < 10; index++) monica.addBreadcrumb("ui.click", "tap-" + index);
    monica.captureMessage("boom");
    assertTrue(monica.flush(Duration.ofSeconds(1)));

    List<?> breadcrumbs = (List<?>) transport.only().get("breadcrumbs");
    assertEquals(3, breadcrumbs.size());
    @SuppressWarnings("unchecked")
    Map<String, Object> oldest = (Map<String, Object>) breadcrumbs.get(0);
    assertEquals("tap-7", oldest.get("message"));
  }

  @Test
  void sendsNothingAboutTheUserUntilTheApplicationSaysSo() {
    RecordingTransport transport = new RecordingTransport();
    MonicaAndroid monica = MonicaAndroid.install(new FakePlatform(),
        options(transport).build());

    monica.setUser("u_123");
    monica.captureMessage("boom");
    assertTrue(monica.flush(Duration.ofSeconds(1)));

    @SuppressWarnings("unchecked")
    Map<String, Object> user = (Map<String, Object>) transport.only().get("user");
    assertEquals("u_123", user.get("id"));
  }

  @Test
  void capturesTheCrashAsFatalAndStillLetsThePreviousHandlerRun() {
    RecordingTransport transport = new RecordingTransport();
    AtomicReference<Throwable> delegated = new AtomicReference<>();
    Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> delegated.set(throwable));

    MonicaAndroid.install(new FakePlatform(),
        options(transport).captureUncaughtExceptions(true).build());

    Thread.UncaughtExceptionHandler installed = Thread.getDefaultUncaughtExceptionHandler();
    IllegalStateException crash = new IllegalStateException("boom");
    installed.uncaughtException(Thread.currentThread(), crash);

    assertSame(crash, delegated.get());
    MonicaEvent item = transport.only();
    assertEquals("fatal", item.get("level"));
    assertEquals(Boolean.FALSE, mechanism(item).get("handled"));
    assertNotNull(tags(item).get("thread"));
  }

  @Test
  void theCrashHandlerWaitsShutdownTimeoutAndNoLonger() throws Exception {
    // A transport that never answers: the only thing bounding the crashing thread is the
    // timeout wired from the options, and it must be shutdownTimeout, not flushTimeout.
    CountDownLatch release = new CountDownLatch(1);
    MonicaTransport stuck = envelope -> {
      release.await(10, TimeUnit.SECONDS);
      return true;
    };
    AtomicReference<Throwable> delegated = new AtomicReference<>();
    Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> delegated.set(throwable));
    try {
      MonicaAndroid.install(new FakePlatform(), options(stuck)
          .captureUncaughtExceptions(true)
          .shutdownTimeout(Duration.ofMillis(300))
          .flushTimeout(Duration.ofSeconds(8))
          .build());

      long started = System.nanoTime();
      IllegalStateException crash = new IllegalStateException("boom");
      Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), crash);
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

      assertSame(crash, delegated.get());
      assertTrue(elapsedMillis >= 250, "waited only " + elapsedMillis + "ms");
      assertTrue(elapsedMillis < 4_000, "waited " + elapsedMillis + "ms, flushTimeout leaked in");
    } finally {
      release.countDown();
    }
  }

  @Test
  void closingRestoresTheHandlerItReplacedAndStopsTracking() {
    RecordingTransport transport = new RecordingTransport();
    Thread.UncaughtExceptionHandler previous = (thread, throwable) -> { };
    Thread.setDefaultUncaughtExceptionHandler(previous);
    FakePlatform platform = new FakePlatform();

    MonicaAndroid monica = MonicaAndroid.install(platform, options(transport)
        .captureUncaughtExceptions(true)
        .trackScreens(true)
        .build());
    assertTrue(monica.isInstalled());
    monica.close();

    assertSame(previous, Thread.getDefaultUncaughtExceptionHandler());
    assertFalse(platform.tracking());
    assertFalse(monica.isInstalled());
    assertFalse(MonicaAndroid.current().isInstalled());
  }

  @Test
  void aSecondInstallClosesTheFirst() {
    RecordingTransport firstTransport = new RecordingTransport();
    RecordingTransport secondTransport = new RecordingTransport();
    FakePlatform platform = new FakePlatform();
    Thread.UncaughtExceptionHandler previous = (thread, throwable) -> { };
    Thread.setDefaultUncaughtExceptionHandler(previous);

    MonicaAndroid first = MonicaAndroid.install(platform,
        options(firstTransport).trackScreens(true).captureUncaughtExceptions(true).build());
    MonicaAndroid replacement = MonicaAndroid.install(platform,
        options(secondTransport).trackScreens(true).captureUncaughtExceptions(true).build());

    assertSame(replacement, MonicaAndroid.current());
    assertFalse(first.isInstalled());
    assertNull(first.captureMessage("into the closed one"), "the first instance is closed");
    // The first handler stepped aside before the second went in, so the chain is
    // replacement -> previous, not replacement -> first -> previous.
    Thread.UncaughtExceptionHandler installed = Thread.getDefaultUncaughtExceptionHandler();
    assertTrue(installed instanceof UncaughtExceptionCapture);
    assertSame(previous, ((UncaughtExceptionCapture) installed).delegate());

    platform.emit("resumed", "OnlyTheSecondSeesThis");
    replacement.captureMessage("boom");
    assertTrue(replacement.flush(Duration.ofSeconds(1)));
    assertEquals(0, firstTransport.items().size());
    assertEquals(1, secondTransport.items().size());
    assertEquals("OnlyTheSecondSeesThis", tags(secondTransport.only()).get("screen"));
  }

  @Test
  void aCaptureAfterCloseIsDropped() {
    RecordingTransport transport = new RecordingTransport();
    MonicaAndroid monica = MonicaAndroid.install(new FakePlatform(), options(transport).build());
    monica.close();

    assertNull(monica.captureMessage("boom"));
    assertEquals(0, transport.items().size());
  }

  @Test
  void currentIsNeverNullAndDoesNothingUntilInstalled() {
    MonicaAndroid nothing = MonicaAndroid.current();
    assertNotNull(nothing);
    assertFalse(nothing.isInstalled());
    assertNull(nothing.client());

    // Every public method on the README's example path must be safe to call.
    assertNull(nothing.captureException(new IllegalStateException("boom")));
    assertNull(nothing.captureMessage("boom"));
    nothing.addBreadcrumb("ui.click", "button");
    nothing.setUser("u_1");
    nothing.setScreen("Nowhere");
    nothing.scope().setTag("k", "v");
    assertFalse(nothing.flush(Duration.ofSeconds(1)));
    assertEquals(0, nothing.stats().getQueued());
    nothing.close();
    assertSame(nothing, MonicaAndroid.current());
  }

  @Test
  void installNeverThrowsEvenForNullArguments() {
    FakePlatform platform = new FakePlatform();
    MonicaAndroidOptions options = options(new RecordingTransport()).build();

    assertFalse(MonicaAndroid.install((AndroidPlatform) null, options).isInstalled());
    assertFalse(MonicaAndroid.install((android.content.Context) null, options).isInstalled());
    MonicaAndroid withNullOptions = MonicaAndroid.install(platform, null);
    assertFalse(withNullOptions.isInstalled());
    assertSame(withNullOptions, MonicaAndroid.current());
    assertEquals(1, platform.warnings().size());
    assertTrue(platform.warnings().get(0).contains("null options"), platform.warnings().get(0));
  }

  @Test
  void aSecretKeyDisablesTheSdkInsteadOfCrashingTheApp() {
    // Keeping msk_ out of APKs is the point; the price is a silent SDK, not a dead app.
    FakePlatform platform = new FakePlatform();
    Thread.UncaughtExceptionHandler previous = (thread, throwable) -> { };
    Thread.setDefaultUncaughtExceptionHandler(previous);
    MonicaAndroidOptions options = MonicaAndroidOptions.builder()
        .dsn("https://msk_secret@ingest.monica.test/1")
        .environment("production")
        .transport(new RecordingTransport())
        .build();

    MonicaAndroid installed = MonicaAndroid.install(platform, options);

    assertFalse(installed.isInstalled());
    assertSame(installed, MonicaAndroid.current());
    assertNull(installed.captureMessage("boom"));
    assertSame(previous, Thread.getDefaultUncaughtExceptionHandler(), "nothing was installed");
    assertEquals(1, platform.warnings().size());
    assertTrue(platform.warnings().get(0).contains("mpk_"), platform.warnings().get(0));
  }

  @Test
  void invalidOptionsReplaceAWorkingInstallWithADisabledOne() {
    // A second install with broken options must not leave the first one half alive.
    FakePlatform platform = new FakePlatform();
    MonicaAndroid first = MonicaAndroid.install(platform,
        options(new RecordingTransport()).trackScreens(true).build());
    MonicaAndroid second = MonicaAndroid.install(platform,
        options(new RecordingTransport()).maxQueueSize(0).batchSize(-1).build());

    assertFalse(first.isInstalled());
    assertFalse(second.isInstalled());
    assertFalse(platform.tracking());
    assertTrue(platform.warnings().get(0).contains("maxQueueSize must be positive"));
    assertTrue(platform.warnings().get(0).contains("batchSize must be positive"));
  }

  @Test
  void aFailingPlatformDisablesTheIntegrationInsteadOfThrowing() {
    Thread.UncaughtExceptionHandler previous = (thread, throwable) -> { };
    Thread.setDefaultUncaughtExceptionHandler(previous);
    List<String> warnings = new ArrayList<>();
    AndroidPlatform broken = new AndroidPlatform() {
      @Override
      public AndroidEnvironment environment() {
        throw new IllegalStateException("framework is unavailable");
      }

      @Override
      public AutoCloseable trackScreens(ScreenListener listener) {
        return () -> { };
      }

      @Override
      public void warn(String message, Throwable failure) {
        warnings.add(message + ": " + failure.getMessage());
      }
    };

    MonicaAndroid installed = MonicaAndroid.install(broken,
        options(new RecordingTransport()).captureUncaughtExceptions(true).build());

    assertFalse(installed.isInstalled());
    assertSame(installed, MonicaAndroid.current());
    assertNull(installed.captureMessage("boom"));
    assertSame(previous, Thread.getDefaultUncaughtExceptionHandler(),
        "a failed install must not leave a handler nobody owns");
    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("framework is unavailable"), warnings.get(0));
  }

  @Test
  void losingScreenTrackingCostsNeitherTheInstallNorTheCrashHandler() {
    RecordingTransport transport = new RecordingTransport();
    FakePlatform platform = new FakePlatform().failTracking();

    MonicaAndroid monica = MonicaAndroid.install(platform, options(transport)
        .trackScreens(true)
        .captureUncaughtExceptions(true)
        .build());

    assertTrue(monica.isInstalled());
    assertTrue(Thread.getDefaultUncaughtExceptionHandler() instanceof UncaughtExceptionCapture);
    assertEquals(1, platform.warnings().size());
    assertTrue(platform.warnings().get(0).contains("Activity transitions"));
    monica.captureMessage("still works");
    assertTrue(monica.flush(Duration.ofSeconds(1)));
    assertEquals(1, transport.items().size());
  }

  @Test
  void applicationCodeThatFailsHardNeverEscapesACapture() {
    FakePlatform platform = new FakePlatform();
    // beforeSend runs application code on the capture path; here it dies with an Error.
    MonicaAndroid monica = MonicaAndroid.install(platform, options(new RecordingTransport())
        .beforeSend((event, hint) -> { throw new StackOverflowError(); })
        .build());

    assertNull(monica.captureMessage("boom"));
    assertNull(monica.captureException(new IllegalStateException("boom")));
    assertTrue(monica.isInstalled(), "one bad hook call does not take the integration down");
  }

  @Test
  void breadcrumbsFromAnotherThreadDuringCaptureNeverEscapeAsExceptions() throws Exception {
    RecordingTransport transport = new RecordingTransport();
    MonicaAndroid monica = MonicaAndroid.install(new FakePlatform(),
        options(transport).maxBreadcrumbs(5).flushInterval(Duration.ofHours(1)).build());

    // The main thread stamps lifecycle breadcrumbs while a worker captures. Before the
    // scope was synchronized this threw ArrayIndexOutOfBounds into the writer or
    // ConcurrentModification into the capture, which silently lost the event.
    AtomicReference<Throwable> writerFailure = new AtomicReference<>();
    Thread writer = new Thread(() -> {
      try {
        for (int index = 0; index < 20_000; index++) monica.addBreadcrumb("ui.lifecycle", "a." + index);
      } catch (Throwable failure) {
        writerFailure.set(failure);
      }
    });
    writer.start();
    int captured = 0;
    for (int index = 0; index < 200; index++) {
      if (monica.captureMessage("boom-" + index) != null) captured++;
    }
    writer.join(10_000);

    assertNull(writerFailure.get());
    assertEquals(200, captured, "every capture must survive the concurrent writer");
  }

  @Test
  void appliesCaptureContextOnTopOfTheScope() {
    RecordingTransport transport = new RecordingTransport();
    MonicaAndroid monica = MonicaAndroid.install(new FakePlatform(), options(transport).build());

    monica.captureException(new IllegalStateException("boom"),
        CaptureContext.create().tag("feature", "checkout"));
    assertTrue(monica.flush(Duration.ofSeconds(1)));

    assertEquals("checkout", tags(transport.only()).get("feature"));
  }

  @Test
  void installsWithTheDefaultHttpTransport() {
    // No transport supplied: the facade builds the HTTP one with the crash deadline. The
    // transport's own tests cover what that deadline does.
    MonicaAndroid monica = MonicaAndroid.install(new FakePlatform(), MonicaAndroidOptions.builder()
        .dsn("https://mpk_public@ingest.monica.test/1")
        .environment("test")
        .captureUncaughtExceptions(true)
        .trackScreens(false)
        .build());
    assertTrue(monica.isInstalled());
    assertNotNull(monica.client());
  }

  private static Object inAppOfTestFrame(MonicaEvent item) {
    for (Map<String, Object> frame : frames(item)) {
      String function = String.valueOf(frame.get("function"));
      if (function.startsWith(MonicaAndroidTest.class.getName() + ".")) return frame.get("in_app");
    }
    throw new AssertionError("the test's own frame must be in the stack");
  }

  private static boolean anyFrame(MonicaEvent item, Boolean inApp) {
    for (Map<String, Object> frame : frames(item)) {
      if (inApp.equals(frame.get("in_app"))) return true;
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> contexts(MonicaEvent item) {
    return (Map<String, Object>) item.get("contexts");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> tags(MonicaEvent item) {
    Map<String, String> tags = (Map<String, String>) item.get("tags");
    return tags == null ? new java.util.LinkedHashMap<>() : tags;
  }

  @SuppressWarnings("unchecked")
  private static Object value(Map<String, Object> contexts, String group, String key) {
    Map<String, Object> values = (Map<String, Object>) contexts.get(group);
    return values == null ? null : values.get(key);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> frames(MonicaEvent item) {
    Map<String, Object> exception = (Map<String, Object>) item.get("exception");
    List<Map<String, Object>> values = (List<Map<String, Object>>) exception.get("values");
    Map<String, Object> stacktrace = (Map<String, Object>) values.get(0).get("stacktrace");
    return stacktrace == null ? new ArrayList<>()
        : (List<Map<String, Object>>) stacktrace.get("frames");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mechanism(MonicaEvent item) {
    Map<String, Object> exception = (Map<String, Object>) item.get("exception");
    List<Map<String, Object>> values = (List<Map<String, Object>>) exception.get("values");
    return (Map<String, Object>) values.get(0).get("mechanism");
  }
}
