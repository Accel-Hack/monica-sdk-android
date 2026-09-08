package com.accelhack.monica.android;

import com.accelhack.monica.CaptureContext;
import com.accelhack.monica.MonicaClient;
import java.time.Duration;

/**
 * Captures the exception that is about to kill the process, then hands control back.
 *
 * <p>The capture is blocking on purpose. The process has seconds to live, so the
 * crashing thread waits for the flush before delegating to the handler that was
 * installed before MONICA. The HTTP request itself runs on the core sender thread, so
 * this never becomes a network call on the main thread.
 *
 * <p>There is no disk queue. A crash that cannot be sent inside the timeout is lost.
 */
public final class UncaughtExceptionCapture implements Thread.UncaughtExceptionHandler {
  private final MonicaClient client;
  private final Thread.UncaughtExceptionHandler delegate;
  private final Duration timeout;

  public UncaughtExceptionCapture(MonicaClient client, Thread.UncaughtExceptionHandler delegate,
      Duration timeout) {
    if (client == null) throw new IllegalArgumentException("client must not be null");
    if (timeout == null || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative");
    }
    if (delegate instanceof UncaughtExceptionCapture
        && ((UncaughtExceptionCapture) delegate).client == client) {
      // Two captures for one client would report the same crash twice and wait twice.
      throw new IllegalArgumentException("delegate must not be another capture of the same client");
    }
    this.client = client;
    this.delegate = delegate;
    this.timeout = timeout;
  }

  /** The handler this one replaced, so it can be restored on uninstall. */
  public Thread.UncaughtExceptionHandler delegate() {
    return delegate;
  }

  @Override
  public void uncaughtException(Thread thread, Throwable throwable) {
    try {
      client.captureException(throwable, CaptureContext.create()
          .level("fatal")
          .handled(false)
          .tag("thread", thread == null ? "unknown" : thread.getName()));
      client.flush(timeout);
    } catch (Throwable ignored) {
      // MONICA must not replace the application's crash with one of its own.
    } finally {
      if (delegate != null) {
        delegate.uncaughtException(thread, throwable);
      } else if (throwable != null) {
        // Android always has a handler underneath, but a bare JVM may not. Returning
        // silently would leave the thread dead and the process alive with no trace.
        // ThreadGroup#uncaughtException is not an option: it calls back into the default
        // handler, which is this object.
        throwable.printStackTrace();
      }
    }
  }
}
