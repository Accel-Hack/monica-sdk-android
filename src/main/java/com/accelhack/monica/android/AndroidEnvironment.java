package com.accelhack.monica.android;

import com.accelhack.monica.Scope;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The device, OS and application facts MONICA attaches to every event.
 *
 * <p>Only values that describe the build of the device and of the application are
 * collected. Nothing here identifies an individual install or person: no ANDROID_ID,
 * serial, advertising id, account, or location. Anything about the user is the
 * application's call and travels through {@code setUser} or {@code beforeSend}.
 */
public final class AndroidEnvironment {
  private final String manufacturer;
  private final String brand;
  private final String model;
  private final String osVersion;
  private final int apiLevel;
  private final String packageName;
  private final String versionName;
  private final long versionCode;

  public AndroidEnvironment(String manufacturer, String brand, String model, String osVersion,
      int apiLevel, String packageName, String versionName, long versionCode) {
    this.manufacturer = manufacturer;
    this.brand = brand;
    this.model = model;
    this.osVersion = osVersion;
    this.apiLevel = apiLevel;
    this.packageName = packageName;
    this.versionName = versionName;
    this.versionCode = versionCode;
  }

  public String packageName() {
    return packageName;
  }

  public String versionName() {
    return versionName;
  }

  /** Writes the device, os and app contexts onto a scope shared by every event. */
  public void applyTo(Scope scope) {
    Map<String, Object> device = new LinkedHashMap<>();
    putText(device, "manufacturer", manufacturer);
    putText(device, "brand", brand);
    putText(device, "model", model);
    if (!device.isEmpty()) scope.setContext("device", device);

    Map<String, Object> os = new LinkedHashMap<>();
    os.put("name", "Android");
    putText(os, "version", osVersion);
    if (apiLevel > 0) os.put("api_level", apiLevel);
    scope.setContext("os", os);

    Map<String, Object> app = new LinkedHashMap<>();
    putText(app, "app_identifier", packageName);
    putText(app, "app_version", versionName);
    if (versionCode > 0) app.put("app_build", String.valueOf(versionCode));
    if (!app.isEmpty()) scope.setContext("app", app);
  }

  private static void putText(Map<String, Object> target, String key, String value) {
    if (value != null && !value.trim().isEmpty()) target.put(key, value);
  }
}
