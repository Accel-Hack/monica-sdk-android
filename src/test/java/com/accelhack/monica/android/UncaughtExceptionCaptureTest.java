package com.accelhack.monica.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.accelhack.monica.MonicaClient;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UncaughtExceptionCaptureTest {
  @Test
  void handsTheCrashBackToThePreviousHandlerEvenWhenCaptureFails() {
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .transport(envelope -> { throw new IllegalStateException("transport is down"); })
        .build();
    AtomicReference<Throwable> delegated = new AtomicReference<>();
    UncaughtExceptionCapture capture = new UncaughtExceptionCapture(client,
        (thread, throwable) -> delegated.set(throwable), Duration.ofMillis(200));

    IllegalStateException crash = new IllegalStateException("boom");
    capture.uncaughtException(Thread.currentThread(), crash);

    assertSame(crash, delegated.get());
    client.close();
  }

  @Test
  void withoutAPreviousHandlerTheCrashStillLeavesATrace() {
    RecordingTransport transport = new RecordingTransport();
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .transport(transport)
        .build();
    UncaughtExceptionCapture capture =
        new UncaughtExceptionCapture(client, null, Duration.ofSeconds(1));

    PrintStream originalErr = System.err;
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    System.setErr(new PrintStream(stderr, true));
    try {
      capture.uncaughtException(Thread.currentThread(), new IllegalStateException("boom"));
    } finally {
      System.setErr(originalErr);
    }

    assertEquals("fatal", transport.only().get("level"));
    // Swallowing it whole would leave a dead thread and a live process with no clue why.
    assertTrue(stderr.toString().contains("IllegalStateException: boom"), stderr.toString());
    client.close();
  }

  @Test
  void flushesBeforeDelegatingSoTheEventIsNotLostWithTheProcess() {
    RecordingTransport transport = new RecordingTransport();
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .transport(transport)
        // A slow periodic flush would hide a missing explicit one.
        .flushInterval(Duration.ofHours(1))
        .build();
    AtomicReference<Integer> sentWhenDelegated = new AtomicReference<>();
    UncaughtExceptionCapture capture = new UncaughtExceptionCapture(client,
        (thread, throwable) -> sentWhenDelegated.set(transport.items().size()),
        Duration.ofSeconds(2));

    capture.uncaughtException(Thread.currentThread(), new IllegalStateException("boom"));

    assertEquals(1, sentWhenDelegated.get());
    client.close();
  }

  @Test
  void givesUpOnASlowTransportAtTheTimeoutAndStillDelegates() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .transport(envelope -> {
          release.await(10, TimeUnit.SECONDS);
          return true;
        })
        .flushInterval(Duration.ofHours(1))
        .build();
    AtomicReference<Throwable> delegated = new AtomicReference<>();
    UncaughtExceptionCapture capture = new UncaughtExceptionCapture(client,
        (thread, throwable) -> delegated.set(throwable), Duration.ofMillis(100));

    try {
      long started = System.nanoTime();
      IllegalStateException crash = new IllegalStateException("boom");
      capture.uncaughtException(Thread.currentThread(), crash);
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

      assertSame(crash, delegated.get());
      assertTrue(elapsedMillis >= 90, "returned after " + elapsedMillis + "ms without waiting");
      assertTrue(elapsedMillis < 3_000, "waited " + elapsedMillis + "ms past the timeout");
    } finally {
      release.countDown();
      client.close(Duration.ofMillis(100));
    }
  }

  @Test
  void rejectsAnUnusableConfiguration() {
    MonicaClient client = MonicaClient.builder().environment("test")
        .transport(new RecordingTransport()).build();
    assertThrows(IllegalArgumentException.class,
        () -> new UncaughtExceptionCapture(null, null, Duration.ofSeconds(1)));
    assertThrows(IllegalArgumentException.class,
        () -> new UncaughtExceptionCapture(client, null, Duration.ofSeconds(-1)));
    // Chaining a capture behind another capture of the same client would report and
    // wait twice for one crash.
    UncaughtExceptionCapture first = new UncaughtExceptionCapture(client, null, Duration.ofSeconds(1));
    assertThrows(IllegalArgumentException.class,
        () -> new UncaughtExceptionCapture(client, first, Duration.ofSeconds(1)));
    assertTrue(client.close(Duration.ofSeconds(1)));
  }
}
