# monica-sdk-android

Android アプリで起きた例外とメッセージを MONICA の ingest へ送る SDK
（`com.accelhack.monica:monica-android`）。

## 対応環境

| | |
| --- | --- |
| minSdk | 26 |
| Java | 11（`sourceCompatibility` / `targetCompatibility`） |
| AGP | 7.0 以上 |
| 権限 | `android.permission.INTERNET` |
| 形式 | JAR（AAR ではない。Gradle plugin も Kotlin 専用 API も持たない） |

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## インストール

`monica-android` と、依存する `monica-core` は GitHub Packages にある。registry は
repository ごとなので、2 つ宣言する。

```groovy
// settings.gradle
dependencyResolutionManagement {
  repositories {
    mavenCentral()
    maven {
      url = uri('https://maven.pkg.github.com/Accel-Hack/monica-sdk-android')
      credentials {
        username = providers.environmentVariable('MONICA_PACKAGES_ACTOR').get()
        password = providers.environmentVariable('MONICA_PACKAGES_TOKEN').get()
      }
    }
    maven {
      url = uri('https://maven.pkg.github.com/Accel-Hack/monica-sdk-java')
      credentials {
        username = providers.environmentVariable('MONICA_PACKAGES_ACTOR').get()
        password = providers.environmentVariable('MONICA_PACKAGES_TOKEN').get()
      }
    }
  }
}
```

```groovy
// app/build.gradle
dependencies {
  implementation 'com.accelhack.monica:monica-android:0.1.0'
}

android {
  compileOptions {
    sourceCompatibility JavaVersion.VERSION_11
    targetCompatibility JavaVersion.VERSION_11
  }
  defaultConfig {
    minSdk 26
  }
}
```

credential は build 時に環境変数から渡す。GitHub Packages は package が public でも
匿名 read を拒否するので、`read:packages` を持つ token が要る。

```bash
export MONICA_PACKAGES_ACTOR="$(gh api user --jq .login)"
export MONICA_PACKAGES_TOKEN="$(gh auth token)"
```

`gh auth token` に `read:packages` が無いと 401 になる。その場合は追加する。

```bash
gh auth refresh -s read:packages
```

環境変数が未設定のまま build すると `Cannot query the value of this provider` で落ちる。

## 初期化

`Application#onCreate` で 1 回だけ `install()` を呼ぶ。classpath に置いただけでは
何も送らない。

```java
public final class ExampleApp extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    MonicaAndroid.install(this, MonicaAndroidOptions.builder()
        .dsn(BuildConfig.MONICA_DSN)
        .environment(BuildConfig.DEBUG ? "development" : "production")
        .release(BuildConfig.VERSION_NAME)
        .inAppPackage("com.example.app")
        .build());
  }
}
```

DSN は public key（`mpk_`）を含むものだけを受け付ける。`msk_` を渡すと SDK は無効に
なり、何も送らない（理由は logcat の tag `MONICA` に出る）。DSN の scheme は `https`
（`localhost` / `127.0.0.1` のみ例外）。

## 使い方

```java
MonicaAndroid monica = MonicaAndroid.current();

monica.captureException(error);
monica.captureException(error, CaptureContext.create().tag("feature", "checkout"));
monica.captureMessage("payment retry exhausted", CaptureContext.create().level("warning"));
monica.addBreadcrumb("ui.click", "submitButton");
monica.setUser("u_123");
monica.setScreen("CheckoutFragment");
```

- `MonicaAndroid.current()` は `null` を返さない。install 前、install 失敗後、
  `close()` 後は何もしない instance を返す（`captureXxx` は `null`、`flush` は
  `false`）。動いているかは `isInstalled()` で分かる
- `CaptureContext` は `level` / `message` / `handled` / `tag` / `context` を持つ
- 全ての event に付く tag・context・breadcrumb は `scope()` から足せる
  （`setTag` / `setUser` / `setContext` / `addBreadcrumb`）
- `stats()` は queue の `getQueued()` と、溢れて捨てた `getDiscarded()` を返す
- `flush(Duration)` は queue を送り切るまで、`close()` は `flushTimeout` まで
  ブロックする。main thread では呼ばない
- `install()` を 2 回呼ぶと前の instance が `close()` され、置き換わる

### 設定ミスで落とさない

`MonicaAndroidOptions.build()` は不正な設定を見つけても例外を投げず、`problems()` に
集める。`install()` はそれを logcat に書き、何もしない instance を返す。開発中に
気付きたい場合はアプリ側で投げる。

```java
MonicaAndroidOptions options = builder.build();
if (BuildConfig.DEBUG && !options.problems().isEmpty()) {
  throw new IllegalStateException("MONICA: " + options.problems());
}
```

## オプション

| option | 型 | 既定 | 説明 |
| --- | --- | --- | --- |
| `dsn` | `String` | 必須 | `mpk_` の public key を含む DSN |
| `environment` | `String` | 必須 | `production` など。前後の空白は落とす。128 文字まで |
| `release` | `String` | アプリの `versionName` | |
| `inAppPackage` / `inAppPackages` | `String` / `Iterable<String>` | アプリの package 名 | frame の `in_app` 判定 |
| `beforeSend` | `BeforeSend` | なし | 送信前の最後の関門。PII の除去はここ |
| `onDiagnostic` | `MonicaDiagnosticListener` | logcat へ 1 行 | ingest が envelope を弾いた理由の受け取り先 |
| `sampleRate` | `double` | `1.0` | 0.0〜1.0 |
| `maxQueueSize` | `int` | `100` | 溢れた分は捨てる |
| `batchSize` | `int` | `30` | 1 envelope に載せる event 数 |
| `maxBreadcrumbs` | `int` | `50` | 超えた分は古い順に落とす |
| `flushInterval` | `Duration` | `5s` | 通常時の送信間隔 |
| `flushTimeout` | `Duration` | `2s` | `close()` が待つ上限 |
| `shutdownTimeout` | `Duration` | `5s` | クラッシュ時に送信を待つ上限。fatal を含む envelope の送信締切にもなる |
| `requestTimeout` | `Duration` | `10s` | 1 リクエストの connect / read timeout |
| `maxRetries` | `int` | `2` | 再試行回数。負数は不正 |
| `captureUncaughtExceptions` | `boolean` | `true` | 未捕捉例外を `level: fatal` で送る |
| `trackScreens` | `boolean` | `true` | Activity 遷移の breadcrumb |
| `attachDeviceContext` | `boolean` | `true` | 端末 / OS / アプリ context |
| `transport` | `MonicaTransport` | 組み込みの HTTP transport | テスト用の差し替え口 |

## 自動で収集するもの

`attachDeviceContext` が `true` のとき、全ての event に次が付く。

| context | 内容 |
| --- | --- |
| `contexts.device` | `manufacturer` / `brand` / `model` |
| `contexts.os` | `name`（`Android`）/ `version` / `api_level` |
| `contexts.app` | `app_identifier`（package 名）/ `app_version`（versionName）/ `app_build`（versionCode） |

`trackScreens` が `true` のとき、Activity の `created` / `resumed` / `paused` /
`destroyed` を `ui.lifecycle` breadcrumb に残し、`resumed` では `screen` tag も更新する。
値は Activity の単純クラス名で、実行時の入力は含まない。

`ANDROID_ID`、serial、IMEI、広告 ID、アカウント、位置情報、実ファイルパスは一切読まず、
パーミッションを要求する API も呼ばない。個人に関する値は `setUser()` と `beforeSend`
で明示したものだけが送られる。

## 送信結果と診断

握り潰した失敗と、ingest が envelope を弾いた理由は logcat の tag `MONICA` に
`Log.w` で 1 行出る（`adb logcat -s MONICA`）。既定で出るのは `422` と、送信を止める
`401` の 2 つ。

```text
monica: ingest rejected the envelope with 422 (invalid_envelope): 1 issue(s); $.items[0].request.method: Invalid type: Expected string
```

プログラムから受け取るには `onDiagnostic` に `MonicaDiagnosticListener` を渡す。渡すと
既定の logcat 行は出なくなり、代わりに `400` / `413` も届く。

詳しくは [TROUBLESHOOTING.md](TROUBLESHOOTING.md) を見る。

## 制約

- **難読化するアプリは、次の 1 行を `proguard-rules.pro` に足す。** SDK 自身の keep
  ルールは jar の `META-INF/proguard/monica-android.pro` に入っていて AGP が自動で
  読むが、アプリの package 名は SDK からは分からない。無いとアプリの frame が 1 つも
  `in_app` にならず、同じクラッシュが別 Issue に散り、スタックも読めない

  ```
  -keepnames class com.example.app.** { *; }   # inAppPackage() に渡す package と一致させる
  ```
- 難読化したビルドでは、frame の filename は残っているクラス名から導出した
  `MainActivity.java` になる。Kotlin のアプリでは、難読化していないビルドの
  `MainActivity.kt` と grouping が分かれる
- **ディスクへの永続キューは持たない。** 未送信の event はプロセスが終わると失われる。
  クラッシュ時は `shutdownTimeout` まで送信を待ってから元の handler へ委譲するので、
  この値を短くすると取りこぼしやすくなる
- `413`（契約上は `split_and_retry`）は分割せず破棄する。envelope は送信前に
  `batchSize` で分割される
- SDK の public メソッドは全て `Throwable` を握るので、SDK の不具合でアプリは落ちない。
  代わりに失敗は logcat にしか出ない
- `transport` を自前のものに差し替えると `onDiagnostic` は呼ばれない

## ライセンス

Apache License 2.0（[LICENSE](LICENSE)）。

---

## 開発者向け

### ビルドとテスト

```bash
export MONICA_PACKAGES_ACTOR="$(gh api user --jq .login)"
export MONICA_PACKAGES_TOKEN="$(gh auth token)"
mvn -s .github/maven-settings.xml verify
```

Java 11 で build する。CI は Java 11 と 18 で `mvn verify` を回す。

### monica-core の解決

`monica-core` は `Accel-Hack/monica-sdk-java` の GitHub Packages にある。手元では
credential を `gh` から借りる（token を発行して置かない）。401 になる場合は権限を足す。

```bash
gh auth refresh -s read:packages
```

core を直してからここで確かめるには、先に core を publish する。

### 公開契約（spec/）

```sh
python3 scripts/spec-sync.py                 # 配信元から取り込み直す
python3 scripts/spec-sync.py --check         # 取り込んだコピーが spec.lock.json と一致するか（network 不要）
python3 scripts/spec-sync.py --check-remote  # さらに配信元が動いていないか
```

取り込み先は `spec/v1/`、記録は `spec.lock.json`。手で `spec/` を編集しても次の取り込みで
消える。契約テストは `src/test/java/com/accelhack/monica/android/EnvelopeContractTest.java`
で、`mvn verify` の一部として走る。CI の `公開契約` job が `--check-remote` を毎日回す。
落ちたら取り込み直し、`mvn verify` を通してから commit する。

### リリース

1. `pom.xml` を `X.Y.Z-SNAPSHOT` にし、`MonicaAndroid.SDK_VERSION` を `X.Y.Z` に揃える
   （契約テストが突き合わせる）
2. main へ merge する
3. その commit へ `vX.Y.Z` tag を付けて push する

`.github/workflows/maven-release.yml` が tag と POM version の対応を検証し、release
version へ変換してから、source / Javadoc jar を含む artifact を
<https://maven.pkg.github.com/Accel-Hack/monica-sdk-android> へ公開する。secret は要らない。
