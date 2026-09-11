# トラブルシューティング

## 警告の見方

SDK は例外をアプリへ投げない。握り潰した失敗と、ingest が envelope を弾いた理由は
logcat の tag `MONICA` に `android.util.Log#w` で 1 行ずつ出る。

```bash
adb logcat -s MONICA
```

ここに出るのは次のもの。

| 場面 | 行 |
| --- | --- |
| options が不正 | `MONICA is disabled because its options are invalid: <問題を "; " で連結>` |
| `install()` に `null` | `install(context, options) was given a null Context; MONICA is disabled` など |
| install 自体が失敗 | `install failed; MONICA is disabled until the next install` |
| API 呼び出しの中で失敗 | `<メソッド名> failed inside MONICA; the call did nothing` |
| Activity 遷移を追えない | `Activity transitions are not tracked` / `the Context has no Application, so Activity transitions are not tracked` |
| ingest が envelope を弾いた | `monica: ingest rejected the envelope with ...`（下記） |

`install()` より前は `AndroidPlatform` がまだ無いので、`MONICA: <message>` が stderr へ
出る（logcat には `System.err` として出る）。

## ingest が envelope を拒否したとき

transport は `429` を除く `4xx` のレスポンス body を `spec/v1/error.json` として読み、
1 envelope につき 1 回だけ報告する（再試行のたびには出さない）。既定で logcat に出るのは
`422` と、送信を止める `401` の 2 つ。

```text
monica: ingest rejected the envelope with 422 (invalid_envelope): 1 issue(s); $.items[0].request.method: Invalid type: Expected string
monica: ingest rejected the envelope with 401 (unauthorized); no further envelopes will be sent
```

括弧の中は ingest の `error.code`。body が無い・壊れている・`error.json` の形をしていない
ときは `(unknown)` になる。行に API key も envelope の中身も含まれない。

### 422（`drop`）

envelope の内容が契約に合わなかった。`issues` の `path` が、どのフィールドが弾かれたかを
そのまま指す（`$.items[0].request.method` など）。`beforeSend` が必須フィールドを落として
いる、という形の障害はここでしか分からない。

行に並ぶ issues は 10 件まで（logcat が 1 行を約 4 KiB で切るため）。超えた分は
`; and N more` に丸める。`MonicaDiagnostic#issues()` からは常に全件取れる。

### 401（`drop_and_stop`）

key そのものが拒否された。envelope を捨てた上で、**その transport は以後 POST しない。**
止まったことは上の 1 行と `HttpUrlConnectionTransport#isStopped()` で分かる。送信を
再開するには `MonicaAndroid.install()` をやり直す。

DSN の key が MONICA 側のプロジェクトのものか、`mpk_` の public key かを確かめる。

### 400 / 413（`drop` / `split_and_retry`）

どちらも既定では logcat に出ない。`onDiagnostic` に listener を渡すと届く。

`413` はこの SDK では分割せず破棄する。envelope は送信前に `batchSize`（既定 `30`）件ずつ
に分割されるが、サイズでは分割しないので、1 envelope が上限（gzip 後 1 MiB / 展開後 8 MiB）
を超えると起きる。`beforeSend` で載せている context や breadcrumb の量を減らすか、
`batchSize` を下げる。

## 送信結果の受け取り

`MonicaAndroidOptions.Builder#onDiagnostic(MonicaDiagnosticListener)` に listener を渡すと、
弾かれた envelope の情報がプログラムから受け取れる。

```java
MonicaAndroidOptions.builder()
    .onDiagnostic(diagnostic -> {
      Log.w("MONICA", diagnostic.describe());          // 既定と同じ 1 行
      for (MonicaDiagnostic.Issue issue : diagnostic.issues()) {
        myOwnMetrics.count("monica.rejected", issue.path());
      }
    })
```

- **渡すと既定の logcat 行は出なくなる。** `onDiagnostic(d -> {})` が無効化にあたる
- 既定が出さない `4xx`（`400` / `413` など）も届く
- 呼ばれるのは envelope が破棄された `4xx`（`429` を除く）だけ。再試行・timeout・`5xx`
  では呼ばれない
- listener は sender thread の上で呼ばれるのでブロックしてはいけない。listener が投げた
  例外は握り潰す
- `transport` を自前のものに差し替えると呼ばれない。body を読むのは組み込みの
  `HttpUrlConnectionTransport` だけ

`MonicaDiagnostic` の field。

| メソッド | 内容 |
| --- | --- |
| `status()` | ingest が返した HTTP status。分岐はこれで行う |
| `code()` | `error.code`。body が無い・読めないときは `null`。人が読む用 |
| `message()` | `error.message`。無ければ `null` |
| `issues()` | `MonicaDiagnostic.Issue` の一覧。`null` にはならない。`422` 以外では空 |
| `stopped()` | この応答が送信を止めたか（`401` のとき `true`） |
| `describe()` | 既定で logcat に出る 1 行。key も envelope の中身も含まない |

`MonicaDiagnostic.Issue` は `path()` と `message()` を持つ。どちらも `null` にならない。

body は 64 KiB まで読む。fatal を含む envelope では `shutdownTimeout` の残り時間で
打ち切る。どちらに掛かっても例外にはならず、issues 無し（status だけ）の報告になる。

## 再送・queue の挙動

| 応答 | 挙動 |
| --- | --- |
| `2xx` | 受理 |
| `429` | `Retry-After`（整数秒のみ、0〜60 秒に丸める）だけ待って再試行。数値でなければ backoff |
| `5xx` / I/O 失敗 / status が読めない | backoff して再試行 |
| `3xx` | 追わない（key を別 host へ送らないため）。破棄し、`onDiagnostic` にも出ない |
| `4xx`（`429` 以外） | 破棄。再試行しない |

backoff は 1 秒から倍々で 30 秒まで。実際の待ち時間はその半分〜満額のランダム値。
再試行は `maxRetries`（既定 `2`）回まで。

queue は `maxQueueSize`（既定 `100`）まで。溢れた分は捨て、`MonicaAndroid#stats()` の
`getDiscarded()` に数が出る。`getQueued()` は未送信の件数。**ディスクへの永続キューは
無い**ので、プロセスが終わると未送信分は失われる。

fatal（未捕捉例外）を含む envelope は `shutdownTimeout`（既定 5 秒）を締切として送る。
`requestTimeout` は残り時間に切り詰められ、backoff を挟む余裕が無ければ再試行しない。
初回リクエストは TLS 確立込みで数秒かかることがあるため、`shutdownTimeout` を短くすると
その分クラッシュを取りこぼしやすくなる。

## よくある原因と対処

| 症状 | 見るところ |
| --- | --- |
| 何も届かない | `MonicaAndroid.current().isInstalled()`。`false` なら logcat に `MONICA is disabled ...` が出ている |
| `install()` が黙って無効になる | `MonicaAndroidOptions#problems()`。空でなければその内容がそのまま理由 |
| DSN を入れたのに無効 | key が `mpk_` か。`msk_` は APK に載る時点で漏れた secret なので拒否する。scheme が `https` か（例外は `localhost` / `127.0.0.1`） |
| 一度は届いたが止まった | logcat に `401 ...; no further envelopes will be sent` が無いか。再開には `install()` のやり直しが要る |
| 特定のフィールドだけ欠ける / 全部弾かれる | `422` の `issues` の `path`。`beforeSend` が必須フィールドを落としていないか |
| スタックの frame が全部フレームワーク扱い | `proguard-rules.pro` に `-keepnames class <アプリの package>.** { *; }` があるか。`inAppPackage()` に渡した package と一致しているか |
| Activity の breadcrumb が出ない | `trackScreens` が `true` か。`install()` に渡した `Context` から `Application` が取れているか（logcat に `the Context has no Application ...`） |
| クラッシュが届かない | `captureUncaughtExceptions` が `true` か。`shutdownTimeout` が短すぎないか |
| `install()` 後にアプリが ANR | `flush(Duration)` / `close()` を main thread で呼んでいないか |
| Gradle が `Cannot query the value of this provider` で落ちる | `MONICA_PACKAGES_ACTOR` / `MONICA_PACKAGES_TOKEN` が未設定 |
| 依存解決が 401 | token に `read:packages` が無い。`gh auth refresh -s read:packages` |
