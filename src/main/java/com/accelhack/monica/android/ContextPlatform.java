package com.accelhack.monica.android;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * The only class that touches the Android framework. Everything it does is a read,
 * except for the one line it writes to logcat when the SDK swallows a failure.
 */
final class ContextPlatform implements AndroidPlatform {
  static final String LOG_TAG = "MONICA";

  private final Application application;
  private final AndroidEnvironment environment;

  private ContextPlatform(Application application, AndroidEnvironment environment) {
    this.application = application;
    this.environment = environment;
  }

  static AndroidPlatform from(Context context) {
    if (context == null) throw new IllegalArgumentException("context must not be null");
    Context applicationContext = context.getApplicationContext();
    Context source = applicationContext == null ? context : applicationContext;
    Application application = source instanceof Application ? (Application) source : null;
    return new ContextPlatform(application, read(source));
  }

  @Override
  public AndroidEnvironment environment() {
    return environment;
  }

  @Override
  public AutoCloseable trackScreens(ScreenListener listener) {
    if (listener == null) return () -> { };
    if (application == null) {
      // ActivityLifecycleCallbacks live on the Application. Without one there is nothing
      // to register on, and the integrator should learn that instead of guessing.
      warn("the Context has no Application, so Activity transitions are not tracked", null);
      return () -> { };
    }
    Application.ActivityLifecycleCallbacks callbacks = new LifecycleCallbacks(listener);
    application.registerActivityLifecycleCallbacks(callbacks);
    return () -> application.unregisterActivityLifecycleCallbacks(callbacks);
  }

  @Override
  public void warn(String message, Throwable failure) {
    try {
      if (failure == null) Log.w(LOG_TAG, message);
      else Log.w(LOG_TAG, message, failure);
    } catch (Throwable ignored) {
      // Logging is the last resort; it has nowhere left to report to.
    }
  }

  private static AndroidEnvironment read(Context context) {
    String packageName = text(() -> context.getPackageName());
    PackageInfo info = packageInfo(context, packageName);
    return new AndroidEnvironment(
        text(() -> Build.MANUFACTURER),
        text(() -> Build.BRAND),
        text(() -> Build.MODEL),
        text(() -> Build.VERSION.RELEASE),
        number(() -> Build.VERSION.SDK_INT),
        packageName,
        info == null ? null : info.versionName,
        info == null ? 0 : versionCodeOf(info));
  }

  private static PackageInfo packageInfo(Context context, String packageName) {
    try {
      return packageName == null ? null : context.getPackageManager().getPackageInfo(packageName, 0);
    } catch (Throwable ignored) {
      // A missing PackageInfo only costs context; it must never fail installation.
      return null;
    }
  }

  /**
   * API 28 widened the version code to 64 bits ({@code versionCodeMajor}). The compile
   * stubs predate that, so the accessor is reached by reflection and the legacy field
   * is the fallback on older devices.
   */
  private static long versionCodeOf(PackageInfo info) {
    try {
      Object value = PackageInfo.class.getMethod("getLongVersionCode").invoke(info);
      if (value instanceof Long) return (Long) value;
    } catch (Throwable ignored) {
      // Older than API 28, or the platform refused; the 32-bit field still applies.
    }
    return info.versionCode;
  }

  private static String text(java.util.function.Supplier<String> read) {
    try {
      return read.get();
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static int number(java.util.function.IntSupplier read) {
    try {
      return read.getAsInt();
    } catch (Throwable ignored) {
      return 0;
    }
  }

  /** Package-private so the lifecycle mapping can be tested without a device. */
  static final class LifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
    private final ScreenListener listener;

    LifecycleCallbacks(ScreenListener listener) {
      this.listener = listener;
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
      report("created", activity);
    }

    @Override
    public void onActivityStarted(Activity activity) {
      // Started and stopped duplicate resumed and paused for breadcrumb purposes.
    }

    @Override
    public void onActivityResumed(Activity activity) {
      report("resumed", activity);
    }

    @Override
    public void onActivityPaused(Activity activity) {
      report("paused", activity);
    }

    @Override
    public void onActivityStopped(Activity activity) {
      // See onActivityStarted.
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
      // Nothing worth recording, and outState can hold application data.
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
      report("destroyed", activity);
    }

    private void report(String lifecycle, Activity activity) {
      try {
        listener.onScreen(lifecycle, activity == null ? "unknown"
            : activity.getClass().getSimpleName());
      } catch (Throwable ignored) {
        // Breadcrumbs must never break the Activity lifecycle.
      }
    }
  }
}
