package com.accelhack.monica.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.accelhack.monica.MonicaClient;
import com.accelhack.monica.MonicaEvent;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shape of the contexts. Whether the SDK reads anything identifying is a property of
 * {@code ContextPlatform}, checked at the bytecode level in {@link AndroidCompatibilityTest}.
 */
class AndroidEnvironmentTest {
  private static Map<String, Object> contextsFor(AndroidEnvironment environment) {
    RecordingTransport transport = new RecordingTransport();
    try (MonicaClient client = MonicaClient.builder()
        .environment("test")
        .transport(transport)
        .build()) {
      environment.applyTo(client.globalScope());
      client.captureMessage("boom");
      assertTrue(client.flush(Duration.ofSeconds(1)));
    }
    MonicaEvent item = transport.only();
    @SuppressWarnings("unchecked")
    Map<String, Object> contexts = (Map<String, Object>) item.get("contexts");
    return contexts;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> group(Map<String, Object> contexts, String name) {
    return (Map<String, Object>) contexts.get(name);
  }

  @Test
  void describesTheBuildOfTheDeviceAndOfTheApplication() {
    Map<String, Object> contexts = contextsFor(new AndroidEnvironment(
        "Google", "google", "Pixel 8", "14", 34, "com.example.app", "2.3.1", 231));

    assertEquals("Google", group(contexts, "device").get("manufacturer"));
    assertEquals("google", group(contexts, "device").get("brand"));
    assertEquals("Pixel 8", group(contexts, "device").get("model"));
    assertEquals("Android", group(contexts, "os").get("name"));
    assertEquals("14", group(contexts, "os").get("version"));
    assertEquals(34, group(contexts, "os").get("api_level"));
    assertEquals("com.example.app", group(contexts, "app").get("app_identifier"));
    assertEquals("2.3.1", group(contexts, "app").get("app_version"));
    assertEquals("231", group(contexts, "app").get("app_build"));
  }

  @Test
  void carriesA64BitVersionCodeWithoutTruncation() {
    // API 28 widened versionCode; versionCodeMajor lives in the upper 32 bits.
    long wide = (7L << 32) | 231;
    Map<String, Object> contexts = contextsFor(new AndroidEnvironment(
        "Google", "google", "Pixel 8", "14", 34, "com.example.app", "2.3.1", wide));
    assertEquals(String.valueOf(wide), group(contexts, "app").get("app_build"));
  }

  @Test
  void leavesOutValuesTheFrameworkCouldNotProvide() {
    Map<String, Object> contexts = contextsFor(
        new AndroidEnvironment(null, "  ", null, null, 0, null, null, 0));

    assertNull(contexts.get("device"));
    assertNull(contexts.get("app"));
    Map<String, Object> os = group(contexts, "os");
    assertEquals("Android", os.get("name"));
    assertFalse(os.containsKey("version"));
    assertFalse(os.containsKey("api_level"));
  }
}
