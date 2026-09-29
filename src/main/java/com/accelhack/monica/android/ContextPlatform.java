package com.accelhack.monica.android;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import com.accelhack.monica.MonicaPresenceStore;

/**
 * The only class that touches the Android framework. Everything it does is a read,
 * except for the one line it writes to logcat when the SDK swallows a failure and the
 * presence heartbeat's state it keeps in {@code SharedPreferences}.
 */
final class ContextPlatform implements AndroidPlatform {
  static final String LOG_TAG = "MONICA";
  /** The {@code SharedPreferences} file and keys of the presence heartbeat (README). */
  static final String PRESENCE_PREFERENCES = "com.accelhack.monica.presence";
  static final String PRESENCE_LAST_REPORTED_AT = "last_reported_at";
  static final String PRESENCE_INTERVAL_MILLIS = "interval_ms";
  static final String PRESENCE_SAMPLE_RATE = "sample_rate";

  private final Context context;
  private final Application application;
  private final AndroidEnvironment environment;

  private ContextPlatform(Context context, Application application,
      AndroidEnvironment environment) {
    this.context = context;
    this.application = application;
    this.environment = environment;
  }

  static AndroidPlatform from(Context context) {
    if (context == null) throw new IllegalArgumentException("context must not be null");
    Context applicationContext = context.getApplicationContext();
    Context source = applicationContext == null ? context : applicationContext;
    Application application = source instanceof Application ? (Application) source : null;
    return new ContextPlatform(source, application, read(source));
  }

  @Override
  public AndroidEnvironment environment() {
    return environment;
  }

  @Override
  public MonicaPresenceStore presenceStore() {
    return new PreferencesPresenceStore(
        context.getSharedPreferences(PRESENCE_PREFERENCES, Context.MODE_PRIVATE));
  }

  @Override
  public AutoCloseable trackScreens(ScreenListener listener) {
    if (listener == null) return () -> { };
    if (application == null) {
      // ActivityLifecycleCallbacks live on the Application. Without one there is nothing
      // to register on; MonicaAndroid warns and lets the heartbeat run without the foreground.
      throw new IllegalStateException("the Context has no Application, so Activity"
          + " transitions and returns to the foreground are not tracked");
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
    /** Callbacks arrive on the main thread, so a plain counter is enough. */
    private int started;
    /** A stop for a rotation: the start of the recreated Activity is not a return. */
    private boolean restarting;

    LifecycleCallbacks(ScreenListener listener) {
      this.listener = listener;
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
      report("created", activity);
    }

    @Override
    public void onActivityStarted(Activity activity) {
      // Started and stopped duplicate resumed and paused for breadcrumb purposes; they only
      // count, so the first start after none is a return to the foreground.
      if (restarting) {
        restarting = false;
        return;
      }
      if (started++ == 0) report("foreground", activity);
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
      stopped(activity != null && activity.isChangingConfigurations());
    }

    /**
     * Package-private because the compile stubs cannot construct an Activity. A stop for a
     * configuration change keeps the count, so the recreated Activity's start is not taken
     * for a return to the foreground. The last stop is reported as {@code background}.
     */
    void stopped(boolean changingConfigurations) {
      if (changingConfigurations) {
        restarting = true;
        return;
      }
      if (started > 0 && --started == 0) report("background", null);
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

  /**
   * The presence heartbeat's state in {@code SharedPreferences}. monica-core validates what
   * it writes, so this only persists. {@code SharedPreferences} has no double, so the rate is
   * kept as its decimal string.
   */
  static final class PreferencesPresenceStore implements MonicaPresenceStore {
    private final SharedPreferences preferences;

    PreferencesPresenceStore(SharedPreferences preferences) {
      this.preferences = preferences;
    }

    @Override
    public Long getLastReportedAt() {
      return readLong(PRESENCE_LAST_REPORTED_AT);
    }

    @Override
    public void setLastReportedAt(long epochMillis) {
      preferences.edit().putLong(PRESENCE_LAST_REPORTED_AT, epochMillis).apply();
    }

    @Override
    public Long getIntervalMillis() {
      return readLong(PRESENCE_INTERVAL_MILLIS);
    }

    @Override
    public void setIntervalMillis(long intervalMillis) {
      preferences.edit().putLong(PRESENCE_INTERVAL_MILLIS, intervalMillis).apply();
    }

    @Override
    public Double getSampleRate() {
      String value = preferences.getString(PRESENCE_SAMPLE_RATE, null);
      try {
        return value == null ? null : Double.valueOf(value);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }

    @Override
    public void setSampleRate(double sampleRate) {
      preferences.edit().putString(PRESENCE_SAMPLE_RATE, Double.toString(sampleRate)).apply();
    }

    private Long readLong(String key) {
      return preferences.contains(key) ? preferences.getLong(key, 0) : null;
    }
  }
}
