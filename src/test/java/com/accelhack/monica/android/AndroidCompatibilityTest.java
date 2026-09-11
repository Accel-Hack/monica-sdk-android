package com.accelhack.monica.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.accelhack.monica.MonicaClient;
import java.io.DataInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/**
 * Guards what cannot be checked by running this module on a JVM: the bytecode that ends
 * up in an APK — this module <em>and</em> {@code monica-core}, which ships with it — has
 * to be something the Android toolchain accepts, it must not reference packages the
 * platform does not have, and the one class that talks to the framework must not read
 * anything that identifies an install.
 *
 * <p>Class files keep every referenced type, field and method name as a UTF-8 constant,
 * so scanning the raw bytes finds a forbidden reference wherever it was introduced.
 * JDK-level API availability (a Java 11 method missing on API 26) is animal-sniffer's
 * job in the pom, not this test's.
 */
class AndroidCompatibilityTest {
  /** Java 11, the release this project compiles to; D8 has accepted it since AGP 4.2. */
  private static final int JAVA_11_CLASS_VERSION = 55;

  /** Packages Android does not ship. {@code java.sql} exists there, so it is not listed. */
  private static final String[] ABSENT_FROM_ANDROID = {
    "java/net/http/",
    "java/awt/",
    "javax/swing/",
    "java/lang/management/",
    "java/rmi/",
    "javax/naming/",
    "jdk/internal/",
  };

  /**
   * Core classes allowed to reference an absent package, and which one. The JDK
   * transport is for server applications; nothing on Android instantiates it, and the
   * keep rules tell R8 not to warn about it.
   */
  private static final Map<String, String> TOLERATED_IN_CORE = Collections.singletonMap(
      "JdkHttpTransport", "java/net/http/");

  /**
   * Framework APIs that identify a device or a person. Their names would appear in the
   * constant pool of any class that touched them.
   */
  private static final String[] IDENTIFYING_REFERENCES = {
    "android/provider/Settings",
    "ANDROID_ID",
    "android/telephony/",
    "getDeviceId",
    "getImei",
    "getSerial",
    "SERIAL",
    "AdvertisingIdClient",
    "android/location/",
    "android/accounts/",
    "getExternalFilesDir",
    "getDataDir",
  };

  /** Framework packages; {@code com.accelhack.monica.android} itself would match a bare "android/". */
  private static final String[] FRAMEWORK_PACKAGES = {
    "android/app/", "android/content/", "android/os/", "android/util/", "android/provider/",
    "android/telephony/", "android/location/", "android/accounts/", "android/net/",
  };

  @Test
  void everyClassIsBytecodeTheAndroidToolchainAccepts() throws Exception {
    Map<String, byte[]> classes = shippedClasses();
    assertFalse(classes.isEmpty());
    for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
      try (DataInputStream stream = new DataInputStream(
          new java.io.ByteArrayInputStream(entry.getValue()))) {
        assertEquals(0xCAFEBABE, stream.readInt(), entry.getKey() + " is not a class file");
        stream.readUnsignedShort();
        assertTrue(stream.readUnsignedShort() <= JAVA_11_CLASS_VERSION,
            entry.getKey() + " is newer than Java 11 bytecode");
      }
    }
  }

  @Test
  void nothingThatShipsInTheApkReferencesAPackageAndroidDoesNotHave() throws Exception {
    Map<String, byte[]> classes = shippedClasses();
    assertTrue(classes.keySet().stream().anyMatch(name -> name.startsWith("core:")),
        "monica-core must be part of the scan; it ships in the same APK");
    List<String> offences = new ArrayList<>();
    for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
      String constants = new String(entry.getValue(), StandardCharsets.ISO_8859_1);
      for (String absent : ABSENT_FROM_ANDROID) {
        if (!constants.contains(absent)) continue;
        if (entry.getKey().startsWith("core:") && absent.equals(TOLERATED_IN_CORE.get(
            simpleName(entry.getKey())))) {
          continue;
        }
        offences.add(entry.getKey() + " → " + absent);
      }
    }
    assertEquals(Collections.emptyList(), offences, "these references would not resolve on a device");
  }

  @Test
  void theToleratedReferenceIsStillThereSoTheExceptionListDoesNotRot() throws Exception {
    // If core stops referencing java.net.http the allow-list above is dead weight and
    // the dontwarn in the keep rules can go too.
    byte[] transport = shippedClasses().get("core:JdkHttpTransport");
    assertNotNull(transport);
    assertTrue(new String(transport, StandardCharsets.ISO_8859_1).contains("java/net/http/"));
  }

  @Test
  void theFrameworkFacingClassReadsNothingThatIdentifiesAnInstall() throws Exception {
    // AndroidEnvironment only carries what it is handed, so the invariant the README
    // promises lives entirely in ContextPlatform. The constant pool is the proof.
    Map<String, byte[]> classes = shippedClasses();
    List<String> offences = new ArrayList<>();
    for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
      if (!entry.getKey().startsWith("android:ContextPlatform")) continue;
      String constants = new String(entry.getValue(), StandardCharsets.ISO_8859_1);
      for (String forbidden : IDENTIFYING_REFERENCES) {
        if (constants.contains(forbidden)) offences.add(entry.getKey() + " → " + forbidden);
      }
    }
    assertTrue(classes.containsKey("android:ContextPlatform"), "ContextPlatform must be scanned");
    assertEquals(Collections.emptyList(), offences, "the SDK must not read these");
  }

  @Test
  void onlyContextPlatformTouchesTheFramework() throws Exception {
    // The design leans on this: everything else can be tested here, on a JVM.
    List<String> offenders = new ArrayList<>();
    for (Map.Entry<String, byte[]> entry : shippedClasses().entrySet()) {
      if (!entry.getKey().startsWith("android:")) continue;
      String name = simpleName(entry.getKey());
      if (name.startsWith("ContextPlatform")) continue;
      String constants = new String(entry.getValue(), StandardCharsets.ISO_8859_1);
      for (String framework : FRAMEWORK_PACKAGES) {
        // The public entry points name android.content.Context in their signatures;
        // that is the API surface, not framework use.
        if (framework.equals("android/content/") && constants.contains("android/content/Context")
            && !constants.contains("android/content/pm/")) {
          continue;
        }
        if (constants.contains(framework)) offenders.add(entry.getKey() + " → " + framework);
      }
    }
    assertEquals(Collections.emptyList(), offenders);
  }

  @Test
  void shipsTheKeepRulesThatAnAarWouldCarry() throws Exception {
    // Read from the classpath, not the source tree: this is what the jar carries.
    URL rules = AndroidCompatibilityTest.class.getClassLoader()
        .getResource("META-INF/proguard/monica-android.pro");
    assertNotNull(rules, "the keep rules must be packaged, not just present in src/");
    String contents;
    try (InputStream stream = rules.openStream()) {
      contents = new String(readAll(stream), StandardCharsets.UTF_8);
    }
    // Obfuscating these renames the JSON fields the ingest endpoint validates.
    assertTrue(contents.contains("-keep class com.accelhack.monica.MonicaEnvelope"));
    assertTrue(contents.contains("-keep class com.accelhack.monica.MonicaEvent"));
    assertTrue(contents.contains("SourceFile,LineNumberTable"));
    assertTrue(contents.contains("InnerClasses,EnclosingMethod"));
    assertTrue(contents.contains("-dontwarn java.net.http."));
  }

  /** Every class file on the APK's side of the dependency graph: this module and core. */
  private static Map<String, byte[]> shippedClasses() throws Exception {
    Map<String, byte[]> classes = new LinkedHashMap<>();
    Path here = Path.of(System.getProperty("user.dir"), "target", "classes").normalize();
    assertTrue(Files.isDirectory(here), here + " must exist; run the module's compile phase");
    readDirectory("android:", here, classes);
    // Core is wherever the test classpath resolved it: target/classes in a reactor
    // build, the installed jar when this module is built alone.
    URL core = MonicaClient.class.getProtectionDomain().getCodeSource().getLocation();
    Path corePath = Path.of(core.toURI());
    if (Files.isDirectory(corePath)) readDirectory("core:", corePath, classes);
    else readJar("core:", corePath, classes);
    return classes;
  }

  private static void readDirectory(String prefix, Path root, Map<String, byte[]> into)
      throws Exception {
    try (Stream<Path> paths = Files.walk(root)) {
      List<Path> files = paths.filter(path -> path.toString().endsWith(".class")).sorted()
          .collect(Collectors.toList());
      for (Path path : files) {
        into.put(prefix + strip(path.getFileName().toString()), Files.readAllBytes(path));
      }
    }
  }

  private static void readJar(String prefix, Path jar, Map<String, byte[]> into) throws Exception {
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
      for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
        if (!entry.getName().endsWith(".class")) continue;
        String name = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
        into.put(prefix + strip(name), readAll(zip));
      }
    }
  }

  private static String strip(String fileName) {
    return fileName.substring(0, fileName.length() - ".class".length());
  }

  private static String simpleName(String key) {
    String name = key.substring(key.indexOf(':') + 1);
    int inner = name.indexOf('$');
    return inner < 0 ? name : name.substring(0, inner);
  }

  private static byte[] readAll(InputStream stream) throws Exception {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = stream.read(buffer)) >= 0) bytes.write(buffer, 0, read);
    return bytes.toByteArray();
  }
}
