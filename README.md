# monica-sdk-android

Android アプリへ組み込む MONICA SDK `com.accelhack.monica:monica-android` の正本。
`monica-core` の queue、batch、sampling、`beforeSend`、envelope 分割をそのまま使い、
Android で成立しない部分だけを差し替える。

- transport は `HttpURLConnection`。Android に `java.net.http.HttpClient` は無い
- DSN は **public key（`mpk_`）だけ**を受け付ける。APK は誰でも展開できるので、
  `msk_` を渡すと SDK は無効になり、何も送らない（logcat の tag `MONICA` に理由が出る）
- 端末 / OS / アプリ version を `contexts` へ載せる。個体を特定する値は読まない
- 未捕捉例外を `level: fatal` で捕まえ、送り切ってから元の handler へ委譲する

AAR ではなく素の JAR で出す。Kotlin 専用 API も Gradle / AGP も足さない。
`com.google.android:android` は compile 時の stub（`provided`）で、端末では実際の
framework が使われる。

```bash
mvn verify
```

Java 11 で build する（Android の言語水準）。`monica-core` は別 repository
（`Accel-Hack/monica-sdk-java`）から公開されたものを使うので、core を直してから
ここで確かめるには、先に core を publish する必要がある。

### monica-core の解決

`monica-core` は  `Accel-Hack/monica-sdk-java` の
**GitHub Packages** にある。

credential は `gh` から借りるので, gh auth tokenに`read:packages`の権限が必要。
401でpackagesが取得できない場合は以下のコマンドで権限を追加する。

```bash
gh auth refresh -s read:packages
```

```bash
export MONICA_PACKAGES_ACTOR="$(gh api user --jq .login)"
export MONICA_PACKAGES_TOKEN="$(gh auth token)"
mvn -s .github/maven-settings.xml verify
```

## 導入

```groovy
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

`monica-core` は GitHub Packages にあるので、以下を `settings.gradle` に足す。

```groovy
dependencyResolutionManagement {
  repositories {
    mavenCentral()
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

credential は build 時に env から渡す。`read:packages` を持つ token なら何でもよい。
未設定のまま build すると `Cannot query the value of this provider` で落ちる。

```bash
export MONICA_PACKAGES_ACTOR="$(gh api user --jq .login)"
export MONICA_PACKAGES_TOKEN="$(gh auth token)"
```

`minSdk 26` / AGP 7.0 以上。`monica-core` が `java.time`、`java.util.function`、
`CompletableFuture` を使うため、これらが素で使える API level を下限にしている。
21〜25 は core library desugaring を有効にすれば動くと思われるが、**未確認**。
この jar は animal-sniffer（`gummy-bears-api-26`）で API 26 に無い JDK API を参照して
いないことをビルドで検査している（`monica-core` 側も同じ検査を持つ）。

インターネット権限が要る。

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## 初期化

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
        .beforeSend((event, hint) -> event)
        .build());
  }
}
```

`install()` を呼ぶまで何も送らない。classpath に置いただけでは動き出さない。

```java
MonicaAndroid monica = MonicaAndroid.current();
monica.captureException(error, CaptureContext.create().tag("feature", "checkout"));
monica.captureMessage("payment retry exhausted", CaptureContext.create().level("warning"));
monica.addBreadcrumb("ui.click", "submitButton");
monica.setUser("u_123");
monica.setScreen("CheckoutFragment");
```

### SDK の失敗はアプリへ波及させない

- **設定ミスでも投げない。** DSN（空、`msk_`、https 以外）、`environment`（空、129 文字以上）、
  0 以下の `maxQueueSize` / `batchSize` / `maxBreadcrumbs`、0 以下や `null` の Duration、
  0..1 の外の `sampleRate` は `MonicaAndroidOptions.build()` が見つけて `problems()` に集める。
  `install()` は問題があれば logcat に理由を書き、何もしない instance を返す。`null` 引数も同じ
- **開発中に厳しくしたいときは自分で投げる。** SDK は落とさないので、必要ならアプリ側で

  ```java
  MonicaAndroidOptions options = builder.build();
  if (BuildConfig.DEBUG && !options.problems().isEmpty()) {
    throw new IllegalStateException("MONICA: " + options.problems());
  }
  ```
- **`current()` は `null` を返さない。** install 前、install 失敗後、`close()` 後は何もしない
  instance を返す。`captureXxx` は `null`、`flush` は `false` を返す。`isInstalled()` で見分ける
- **public メソッドは全て `Throwable` を握る。** SDK の不具合でアプリは落ちない
- **握り潰した失敗は logcat の tag `MONICA` に `Log.w` で 1 行残す。** 「event が届かない」ときは
  `adb logcat -s MONICA` を見る
- **`flush()` と `close()` はブロックする。main thread で呼ばない。** `close()` は
  `flushTimeout`（既定 2 秒）までしか待たず、クラッシュ経路の `shutdownTimeout` とは別

## option

| option | 既定 | 意味 |
| --- | --- | --- |
| `dsn` | 必須 | `mpk_` の public key を含む DSN |
| `environment` | 必須 | `production` など。前後の空白は落とす。128 文字まで |
| `release` | `versionName` | 未指定ならアプリの versionName |
| `inAppPackage` | アプリの package 名 | frame の `in_app` 判定 |
| `beforeSend` | なし | 送信前の最後の関門。PII の除去はここ |
| `onDiagnostic` | logcat へ 1 行 | ingest が envelope を弾いた理由の受け取り先 |
| `sampleRate` | `1.0` | |
| `maxQueueSize` / `batchSize` | `100` / `30` | |
| `maxBreadcrumbs` | `50` | 超えた分は古い順に落とす |
| `flushInterval` / `flushTimeout` | `5s` / `2s` | 通常時の送信間隔と、`flush()` / `close()` が待つ上限 |
| `shutdownTimeout` | `5s` | クラッシュ時に送信を待つ上限。fatal を含む envelope の送信締切にもなる |
| `requestTimeout` / `maxRetries` | `10s` / `2` | 通常の event 用。fatal では `shutdownTimeout` の残り時間に切り詰められる |
| `captureUncaughtExceptions` | `true` | |
| `trackScreens` | `true` | Activity 遷移の breadcrumb |
| `attachDeviceContext` | `true` | 端末 / OS / アプリ context |

## 自動で集めるもの / 集めないもの

`contexts.device` に manufacturer / brand / model、`contexts.os` に `Android` と
version / API level、`contexts.app` に package 名と versionName / versionCode。
Activity 遷移は `ui.lifecycle` breadcrumb と `screen` tag に残す。値は Activity の
単純クラス名で、実行時の入力は含まない。

`ANDROID_ID`、serial、IMEI、広告 ID、アカウント、位置情報、実ファイルパスは
**一切読まない**。パーミッションを要求する API も呼ばない。何が PII かはアプリ側にしか
判断できないので、`setUser()` と `beforeSend` で明示した値だけを送る。
`AndroidCompatibilityTest` が、framework に触る唯一のクラスの constant pool に
これらの API 名が無いことをビルドで確かめている。

## ingest が envelope を弾いたとき

`spec/v1/ingest.md` は `422`（envelope schema 不正）について「破棄し、`issues` の path を
見て payload を直す」と定めている。この path は **SDK が body を読まないと誰にも届かない。**
実際に、`beforeSend` で allowlist 方式に組み直した際に `request.method`（`request` が
あるなら必須）を落としてしまい、導入以来 1 件も送信されていないことに長く気付かなかった、
という事故が起きている。

そこで transport は `429` を除く `4xx` の body を `error.json` として読み、**`422` は
既定で logcat の tag `MONICA` に 1 行残す。**

```text
monica: ingest rejected the envelope with 422 (invalid_envelope): 1 issue(s); $.items[0].request.method: Invalid type: Expected string
```

書式は 6 つの SDK で共通。API key も envelope の中身も出さない。1 envelope につき 1 回で、
再試行のたびには出さない（`4xx` は再試行しないので、そもそも 1 回しか起きない）。
issues が 10 件を超える分は `and N more` に丸める。

プログラムから受け取るには `onDiagnostic` を渡す。**渡すと既定の logcat 行は出なくなる**
ので、`onDiagnostic(d -> {})` が無効化にあたる。自前の listener には既定が黙っている
`400` / `413` も届く（SDK 側に足せる説明が無く、event ごとに logcat を汚すと警告自体が
無視されるため、既定では出さない）。

```java
MonicaAndroidOptions.builder()
    .onDiagnostic(diagnostic -> {
      Log.w("MONICA", diagnostic.describe());          // 既定と同じ 1 行
      for (MonicaDiagnostic.Issue issue : diagnostic.issues()) {
        myOwnMetrics.count("monica.rejected", issue.path());
      }
    })
```

`MonicaDiagnostic` は `status()` / `code()` / `message()` / `issues()` / `stopped()` /
`describe()` を持つ。`code()` は人が読む用で、分岐は `status()` で行う。sender thread の
上で呼ばれるのでブロックしてはいけない。listener が投げた例外は握り潰す。

**結果型ではなく listener なのは、`monica-core` 0.1.1 の `MonicaTransport#send` が
`boolean` を返すから。** core の API はこの repository から変えられない。core が結果を
返す `deliver()` を持ったら、そちらへ寄せて listener は互換のための薄い層にする。

`401`（`drop_and_stop`）は破棄した上で **その transport から以後 POST しない**。key 自体が
拒否されているので、送っても拒否され続けるだけで、モバイル回線ではその通信量は利用者の
ものだから。止まったことは既定の logcat 行と `HttpUrlConnectionTransport#isStopped()` で分かる。
この行も 6 つの SDK で共通で、field の話ではないので issue 数は付けない（body に `code` が
無ければ `unknown`）。

```text
monica: ingest rejected the envelope with 401 (unauthorized); no further envelopes will be sent
```

なお **JVM の `HttpURLConnection` は `401` / `407` の error body を渡さない**（`setFixedLengthStreamingMode`
と併用したとき、認証の再送を自前で扱う経路に入るため。`400` や `422` は渡す）。そのため
`mvn verify` の中では `401` の `code` は `unknown` になる。端末の `HttpURLConnection` は OkHttp 実装で
body を渡すので、実機では `code` が入り得る。`422` の issues はこの影響を受けない。

`.transport()` で自分の transport を渡した場合、`onDiagnostic` はそこへは届かない。
body を読むのは組み込みの `HttpUrlConnectionTransport` だけ。

## クラッシュ

未捕捉例外は `level: fatal` / `handled: false` で capture し、クラッシュしたスレッドを
`shutdownTimeout` まで待たせてから、元々登録されていた handler へ委譲する。HTTP 送信自体は
core の sender thread で走るので、main thread から `NetworkOnMainThreadException` にはならない。

fatal を含む envelope は `shutdownTimeout` を締切として送る。`requestTimeout`（既定 10 秒）と
`maxRetries`（既定 2）をそのまま使うと 1 回目の試行だけで締切を超えうるので、transport は
残り時間を connect / read のタイムアウトにし、backoff を挟む余裕が無ければ再試行しない。
初回リクエストは TLS 確立込みで 3〜5 秒かかることがあるため、`shutdownTimeout` を短くすると
その分クラッシュを取りこぼしやすくなる。

**ディスクへの永続キューは持たない。** 時間内に送れなかったクラッシュは失われる。

## R8 / ProGuard

SDK 自身が動くための keep ルールは jar の `META-INF/proguard/monica-android.pro` に入れてあり、
AGP が自動で読む。envelope の getter 名が難読化されると ingest が弾くため、model class と
`SourceFile` / `LineNumberTable` を保持している。

**難読化するアプリは、次の 1 行を自分の `proguard-rules.pro` に足す。** これは SDK からは配れない
（アプリの package 名を SDK は知らない）。

```
-keepnames class com.example.app.** { *; }   # inAppPackage() に渡す package と一致させる
```

無いと、クラス名が `c6` になってアプリの frame が 1 つも `in_app` にならない。グルーピングが
フレームワークの frame に落ちて、**同じクラッシュが OS ごと・ビルドごとに別 Issue になり、
スタックも読めない**。`-keepnames` は名前だけ残して shrink と最適化は効かせる指定なので、
APK サイズへの影響はほぼ無い。

ファイル名は気にしなくてよい。R8 は難読化時に SourceFile を必ず書き換える（既定は
`r8-map-id-<ビルドごとのハッシュ>`、`-renamesourcefileattribute` を書けばその文字列）ので、
SDK は **`.` を含まない SourceFile を「ファイル名無し」と見て**、残っているクラス名から
`MainActivity.java` を導出する。`-renamesourcefileattribute` に何を書いても同じ扱いになる。

**Kotlin の注意:** 難読化していないビルドは `MainActivity.kt`、難読化したビルドは導出した
`MainActivity.java` が frame の filename になる。grouping は拡張子を潰さないので、同じクラッシュが
debug と release で別 Issue になる。release だけを送るアプリでは問題にならない。

## 公開契約

protocol は言語に依存しない契約なので、この repository は持たない。MONICA が
<https://spec.monica.accelhack.net/v1/> に配信しているものを取り込んだコピーが
`spec/` にある。

```text
spec.lock.json   取り込んだ内容の記録（origin、version、revision、全ファイルの sha256）
spec/v1/         取り込んだコピー（jar には入らない）
```

取り込みは script でやる。手で `spec/` を編集しても、次の取り込みで消える。
Python 3 の標準ライブラリだけで動き、Maven の build には乗せない。

```sh
python3 scripts/spec-sync.py                 # 配信元から取り込み直す
python3 scripts/spec-sync.py --check         # 取り込んだコピーが spec.lock.json と一致するか（network 不要）
python3 scripts/spec-sync.py --check-remote  # さらに配信元が動いていないか
```

起点は配信元の `index.json`。他の全ファイルのパスと sha256、バンドル全体の
`revision` がそこに並んでいるので、**何を取り込むかは配信元が決める**。この
repository は取り込む対象の一覧を持たない。

`revision` はバンドル全体の指紋（各ファイルの `"<sha256>  <path>"` を path の
byte 順に改行で繋いだ文字列の sha256）で、版番号ではないので新旧や大小は読めない。
`--check` はこれを `spec.lock.json` の `files` から再計算するので、`spec/` を
書き換えて lock の digest を揃えただけの改竄も落ちる。

契約テストは `src/test/java/com/accelhack/monica/android/EnvelopeContractTest.java`。
`mvn verify` の一部として走り、spec が見つからないと skip せず失敗する。契約が
変わったときに Android だけ気付けない状態を作らないため。見るのは 3 つ。

1. この SDK が実際に出す envelope が `envelope.json` の必須項目・pattern・enum・上限を
   満たすこと。上限や語彙は schema から読むので、契約が締まればここが落ちる
2. `sdk.name` が配布 registry の package 名（`com.accelhack.monica:monica-android`）で、
   `sdk.version` が `pom.xml` の版と一致すること
3. `HttpUrlConnectionTransport` の endpoint、public key のヘッダと prefix、https を
   免除する host、Retry-After の上限、backoff の定数が `transport.json` と一致すること

CI の `公開契約` job は `--check-remote` で配信元の `revision` を取り込み済みのものと
比べる。落ちたら `python3 scripts/spec-sync.py` で取り込み直し、`mvn verify` を
通してから commit する。schedule でも毎日回すので、契約が動けば PR を待たずに気付く。

### まだ実装していない契約

`transport.json` の `status` のうち、`413`（`split_and_retry`）は分割せず破棄する。
`monica-core` が送信前に envelope を分割しているので、ingest が `413` を返す状況を
作らないことで代えている。黙って取り残されないように、契約テストは
`transport.json` の status の語彙を固定している。MONICA 側が status を増やすと、
「この SDK が考慮していない契約が増えた」として落ちる。

## Release

開発中の POM は `X.Y.Z-SNAPSHOT` にする。`MonicaAndroid.SDK_VERSION` は同じ版にする
（契約テストが `pom.xml` と突き合わせる）。

4 個の repository secret `MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_TOKEN`、
`MAVEN_GPG_PRIVATE_KEY`、`MAVEN_GPG_PASSPHRASE` を設定し、対応する main commit へ
`vX.Y.Z` tag を付けると `.github/workflows/maven-release.yml` が動く。この repository
が出す artifact は `monica-android` 1 つなので、tag に artifact 名の prefix は付けない。

workflow は tag と POM version の対応を検証し、release version へ一時変換してから、
source / Javadoc jar と GPG signature を含む artifact を Maven Central へ公開する。

## License

Apache License 2.0。`LICENSE` を見る。
