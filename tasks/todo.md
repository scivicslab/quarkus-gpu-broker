# 暴走生成をブローカで止める

背景: gemma-4（文脈長 131,072）で 1 リクエストが同じ一文を 143,896 文字繰り返し、30 分 GPU を
占有した。Qwen3.8 でも別セッションで同じことが起きた。クライアントごとに直しても全員は守れない。
28005 を通ることだけが全クライアントに共通なので、ブローカに入れる。

## やること

- [x] `BrokerConfig` にキューごとの生成上限 `generation-limits` を足す
- [x] `LimitedRequestBody` — 本文に `max_tokens` が無ければ入れる／大きければ下げる
- [x] `ProxyResource.submit` で適用（`/queue/{queueName}` と `/v1/chat/completions` の両方が通る）
- [x] `ResponseSink.stopRequested()` と `HttpAiServiceClient.relay` の打ち切り
- [x] `RepetitionStoppingResponseSink` — 流れてくる本文が周期的になったら上流を切る
- [x] `application.yaml` に現行 3 キューの既定値
- [x] 単体テスト 2 本、`rm -rf target && mvn install`

## 決めたこと

- `max_tokens` はキューごと。文脈長がモデルで違う（gemma-4=131072, Qwen2.5-14B=16000）。
- `repetition_penalty` は knob だけ用意し、既定では入れない。vLLM のこの引数は**プロンプト中の
  トークンも罰する**ので、文書をほぼそのまま書き直す用途では逆効果になる。生成側だけを見る
  `frequency_penalty` も knob として用意する。
- 反復の検出は本文（`"content":"` の中身）のバイト列で行う。SSE の枠自体が反復的なので、
  枠込みで見ると必ず誤検出する。

# 生成の速さを測る

- [x] `GenerationMeasuringResponseSink` — 待ち時間・最初のトークンまで・復号時間・イベント数
- [x] `SseContentScanner` — 反復を見るシンクと共有
- [x] `ResponseSink.servedBy` — どの機械が処理したか（シンクを作る時点では未定）
- [x] `GenerationTotals` を `QueueBucket` / `EndpointBucket` に
- [x] `StatusHistoryStore.recordGeneration`、履歴ファイルは後方互換（古い行は 0 で読む）
- [x] `GET /queues` の `generated`、状態ページの tok/s
- [x] テスト 10 本、137 緑

## 決めたこと

- 率ではなく和を持つ。知りたい 2 つの率（1 本あたり / 合計）が同じ和の別の割り算。
- トークン数は content つきイベントの数。近似だが、プロトコルを変えない。正確に取るなら
  `stream_options.include_usage`。切り替える場所は `max_tokens` と同じ 1 箇所。
- 1 件も終わっていない窓には出さない。使われていないキューが 0 tok/s と読めるのを避ける。

- [x] 文字数も数える。トークンはモデルごとに違う単位なので、モデルをまたいで速さを比べるには
      文字が要る。「比べられないのは単位であって、速さではない」

## 2026-09-23: broker を W206 k0s の中で 1 つだけ動かす（Deployment 1 replica + NodePort）
背景: local-llm の English Toolkit も MiniPC の各アプリも `192.168.5.12:28005`（MiniPC の手動プロセス）を直書きしている。MiniPC が落ちれば全消費者が LLM を失い、1 個であることは慣習頼み（28010 の残骸が 9/20 から残っていた→停止済）。
- [x] `Dockerfile`（uber-jar を `/app`、`config/application.yaml` はマウント、UID 1000、port 28005）と `.dockerignore`。イメージの中身は今 28005 で動いている `~/works/quarkus-gpu-broker-1.1.0-SNAPSHOT.jar` そのもの（作業ツリーに未コミットの変更があるので再ビルドしない）
- [x] `k8s-pups-private-overlays/overlays/gpu-broker/`: namespace `gpu-broker`、ConfigMap（`application.yaml`＝`~/works/config/application.yaml` の broker 節、`BROKER_NODES=192.168.5.0/26`）、Deployment（replicas 1、readiness `GET /`、history は emptyDir）、Service NodePort 30805
- [x] 5.14 でイメージ `1.1.0-<YYMMDDHHmm>` を焼いて push（タグ未使用を確認）→ apply → Pod の `/queues` が MiniPC の 28005 と同じ 7 キュー・11 endpoint を見ること
- [x] NodePort 到達: MiniPC と 5.14 から `192.168.5.22:30805/queues`、Pod 内から `gpu-broker.gpu-broker.svc:28005`
- [x] 消費者の切替（k8s 側のみ）: local-llm の `K8SPUPS_ENGLISH_TOOLKIT_GPU_BROKER_URL` → ClusterIP、MiniPC 側は NodePort。k8s-pups の共有サービスカード（状態/Open は全員、Launch/Stop は admin）は k8s-pups 側の todo
- 結果: イメージ `1.1.0-2609231817`、Pod は stonefly521、8 キュー 13 endpoint すべて UP、capabilities の上書き（5.14/5.23 = 1）も効いた。NodePort は MiniPC・5.14 から 200、Pod 内から `gpu-broker.gpu-broker.svc:28005` も 200。gemma-4 への completion が NodePort 経由で返った。コミット: broker `Dockerfile`（作業ツリーの未コミット変更 10 ファイルは触っていない）、overlays
- 結果: local-llm の English Toolkit Pod は `gpu-broker.gpu-broker.svc:28005` を使う（controller 0.1.0-2609231829、overlays 0826e61）。MiniPC 側の消費者は未切替（利用者の起動プロセスなので判断待ち）。MiniPC の 28005 は開発用として残る

## 2026-10-25: broker を再起動せずに `max-concurrency` の変更を反映する
背景: 5.16 の GLM 用 `max-concurrency: 4` が、Gemma-4 に入れ替えた後も残った。ConfigMap を書き換えても
(1) Deployment が `subPath` でマウントしているので Pod のファイルが更新されず、(2) broker は
`@ConfigMapping` の起動時の写しを使い続け、(3) `ReconcilePlan` は待ち行列名しか比べないので、
登録済みの台の値が変わっても何もしない。バッチ処理中なので、この計画は**実装とイメージの用意まで**で、
Pod の入れ替え（再起動）はバッチの完了後に利用者が決める。
- [x] `AiServiceEndpoint.resize(int)` — 増やすときは worker を足し、減らすときは worker を待ち行列から外す（走行中の仕事は止めない）
- [x] `CapabilityFile` — 毎分の走査で `config/application.yaml` の `broker.capabilities` を読み直す。壊れたファイルは直前の正しい値を保つ
- [x] `EndpointSurveyor` が `CapabilityFile` を使う。`JobQueueRegistry.reconcile` が登録済みの台へ `resize` を呼ぶ
- [x] 単体テスト（`AiServiceEndpoint` の増減、`CapabilityFile` の読み直し）、`rm -rf target && mvn install`
- [x] `overlays/gpu-broker/deployment.yaml` の `subPath` を外す（手元のファイルだけ。適用しない）
- [x] イメージを焼く（タグ `1.2.0-<YYMMDDHHmm>`）。Deployment には適用しない
- 範囲外: `generation-limits` と `image-input` は起動時の写しのまま。登録済みの台の `max-context-length` の更新も対象外
- 結果: 単体テスト 191 本緑（新規 9 本）。本物の jar を 28555 で別に起動し 5.16 だけを対象に、設定ファイルを書き換えて 4→12→32（行を削除）→3 が再起動なしで約 1 分以内に反映されることを確認（本番の broker には触れていない）
- 結果: イメージ `192.168.5.13:32000/quarkus-gpu-broker:1.2.0-2610251146` を push 済み（6 レイヤーとも実体あり）。`overlays/gpu-broker/` の `deployment.yaml`（ディレクトリのマウント）と `kustomization.yaml`（新タグ）は手元を書き換えただけで**適用していない**。
- 入れ替えの手順（バッチ完了後に利用者が決める。Pod が 1 回作り直される）: `kubectl diff -k overlays/gpu-broker` で差分が上の 2 つだけであることを確認 → `kubectl apply -k overlays/gpu-broker` → `GET /queues` で 5.16 のスロットが 32 になること
- 以後は ConfigMap の `max-concurrency` を書き換えて `kubectl apply -f configmap.yaml` するだけで、約 1〜2 分で反映される（再起動なし）

## 2026-10-25: 設定を再起動なしで効かせる（LiveConfig）と、呼び出し側の名前で背景に固定する
背景: 設定の変更に broker の再起動が要るのは実用にならない、というユーザーの指摘。`CapabilityFile`（capabilities だけ）を `LiveConfig` に置き換え、`generation-limits`、`image-input`、`nodes`、新設の `background-submitters` も、ファイルを書き換えるだけで効くようにした。
- [x] `LiveConfig` — 設定ファイルの更新時刻と大きさを毎回見て、変わったときだけ読み直す。壊れたファイルは直前の正しい値を保つ。ファイルが無ければ起動時の写し（`BrokerConfig`）を使う
- [x] `background-submitters` — `X-Submitter-Id` がこの表にある呼び出し側は、`X-Job-Priority` に関係なく background で走る。`ProxyResource`、`OpenAiCompatResource`、`AsyncJobResource` の3つの入口が使う
- [x] `EndpointSurveyor`（nodes と capabilities）、`ProxyResource`（generation-limits）、`OpenAiCompatResource`（image-input）、`StatusResource` を `LiveConfig` に切り替え
- [x] 単体テスト 195 本緑。本物の jar を 28555 で別に起動し、設定ファイルの書き換えで、`image-input` は次の依頼から、ノード 5.17 は次の走査（約 37 秒）で反映されることを確認
- [x] 罠: 新しいキーは `BrokerConfig` に宣言しないと、起動時の設定の検査（SRCFG00050）で拒否される（`broker.background-submitters` で実際に起きた）。キーを足すときは必ず `BrokerConfig` に宣言する
- [x] イメージ `192.168.5.13:32000/quarkus-gpu-broker:1.2.0-2610251432` を push（6 レイヤーとも実体あり）。`overlays/gpu-broker/` の `kustomization.yaml`（新タグ）と `configmap.yaml`（`background-submitters: [rst-frame-import]`）は書き換えただけで**適用していない**
- [x] 適用（2026-10-25 14:47、Pod を1回作り直した。像 1.2.0-2610251432）。本番の Pod で、コメントだけの ConfigMap を `kubectl apply -f configmap.yaml` したところ、同じ Pod（再起動 0）が 7 秒後に `LiveConfig reloaded` をログに出した。以後、設定の変更は ConfigMap の適用だけで済む
- [ ] 呼び出し側（`plugin-rst-frame` の `ChatEndpoint`、`SimilarConcepts`）が `X-Submitter-Id: rst-frame-import` を付ける

## 2026-10-25: broker を再起動しても `/jobs` の仕事を失わない（ジョブの保存）
背景: 14:47 の Pod 入れ替えで、メモリ上の仕事が全部消えた。portal(28001)の `GpuBrokerClient` は
`GET /jobs/{queue}/{id}` の 404 を「まだ見つからない」として待ち続け、未完了の枠が戻らず、PDF 取り込み 35 本と
新しい送信が止まった。ユーザーの要求: broker は再起動しても仕事を失ってはいけない。
`JobResultStoreLifecycle_260810_oo01` は「再起動をまたぐ永続化は設けない」と決めていた。この判断を覆す。
- 範囲: 非同期（submit-then-poll）の `/jobs/{queue}` だけ。ストリーミングの `/queue`・`/v1/*` は接続が切れるので対象外
- 保存先: PVC（`nfs-k8s`、1000:1000）の `/data/jobs/`。DB は使わない。1 仕事 = 受け付け記録（queue 名、priority、submitter、content-type、本体）と結果の 2 ファイル。書き込みは一時ファイル→rename
- 再起動後: 起動時に全部読み込む。DONE/FAILED は `GET` に答える。PENDING は、その queue が `reconcile` で登録され次第、最初から実行し直す
- 期限: 既存の TTL（1 時間）を保存側にも適用する
- 呼び出し側の保険: `gpu-broker-client` は、404 が一定時間続いたジョブを FAILED にして枠を返す
- [ ] 設計文書 `JobResultStoreLifecycle_260810_oo01` の「永続化しない」を改める（またはその続きの文書を書く）— 実装後に書く
- [x] `JobJournal`（ファイル保存）、`JobResultStore` の保存・読み込み、単体テスト 9 本（`JobResultStoreRestartTest`）
- [x] PENDING の再実行: `AsyncJobDispatcher`（`AsyncJobResource` と共有）と `JobRecovery`（5 秒ごとに queue を見て投入、admission も取る）。保存先は `gpu.broker.jobs-dir`（未設定ならメモリのみ）
- [x] 本物の jar を 28555 で起動し、Marker に 3 件投げて `kill -9`。結果済み 1 件は結果ごと、未完了 2 件は PENDING として残り、起動後に自動で再実行されて 3 件とも DONE（本番の broker には触れていない）
- [x] `gpu-broker-client`: 404 が 2 分続いたジョブを FAILED にして枠を返す。broker に届かない間（接続失敗）は失敗にしない。単体テスト 20 本緑（新規 5 本）。`mvn install` で `.m2` の 0.2.0 を上書き済み。portal への取り込みとバージョン番号は利用者が決める
- [x] overlays（手元のみ・未適用）: `pvc.yaml`（longhorn 5Gi、RWO）、`deployment.yaml`（`/data` を PVC に、`GPU_BROKER_JOBS_DIR=/data/jobs`、`fsGroup: 1000`）。履歴（`/data/history.jsonl`）も入れ替えで消えなくなる
- [x] 像 `192.168.5.13:32000/quarkus-gpu-broker:1.2.0-2610251537` を 5.14 で焼いて push（6 ブロブとも実体あり）。`kubectl diff -k` の差分は、像のタグ、`GPU_BROKER_JOBS_DIR`、`fsGroup`、`data` を PVC に、PVC の新設、の 5 つだけ。**適用していない**
- [x] 適用（2026-10-25 16:00、Pod を最後に 1 回作り直した）。PVC `gpu-broker-data`（longhorn 5Gi）が Bound、`/data/jobs` ができ、6 queue が見えた。本番の Marker に 1 件投げて `.res` が PVC に書かれ、DONE を確認。以後の Pod の入れ替えで `/jobs` の仕事は消えない
- 残り: portal に新しい `gpu-broker-client`（404 打ち切り）を取り込む（バージョン番号は利用者が決める）。エンドポイントの health が再入れ替え直後は UNKNOWN で始まる（履歴ファイルが空のため）

## 後で: インタラクティブな仕事が使うスロット数の上限を設定できるようにする（ユーザー要望 2026-10-25）
- 今の `JobQueue` は、急ぎ(FOREGROUND)の仕事を処理したスロットを、同じ会話の次の頼みのために数分間「予約」し、その間は後回し(BACKGROUND)の仕事を渡さない。予約できるスロット数に上限が無い。
- やること: queue ごとに「急ぎの仕事が同時に予約・使用できるスロット数の最大」を `LiveConfig` の表(`broker.…`)で設定できるようにする（再起動なしで効く。`BrokerConfig` への宣言を忘れない）。上限を超えた急ぎの仕事は、予約を使わず空きスロットを通常どおり取る。
- 範囲外: 予約の長さ(数分)の変更は別の話。
- 注意: 設定のキーを足すので、既存の2回目の配備ではなく、次のコード変更と一緒に入れる。配備前に呼び出し側の走行中ジョブが無いことを確かめる（broker は今は再起動しても `/jobs` の仕事を失わないが、`/v1/chat/completions` の頼みは接続が切れる）。

### 仕様（2026-11-07、ユーザー「前景用と後回し用に別々の上限」→「foreground-background-slot-ratio にして作り直しましょう」）
きっかけ: a007 の B200 の Qwen 3.8（172.19.17.182:8000）は、256K の会話を前提に枠 32。読み付け（短い頼み、後回し）は同時 64 件が最速（測定: 64 件で約 60 件処理・待ち 0・KV 6 割、192 件では処理約 100 件で頭打ち・KV 97%・行数は 3 割減）。枠 32 のままでは読み付けが半分の速さになる。
最初の案（前景の上限 `foreground-max-concurrency`、595179a）は、前景 32 件と後回し 32 件が同時に走るのを許し、長い会話のメモリを見込めない（ユーザーの指摘）。重みで数える形に改める。
- 設定: `broker.capabilities."<host:port>".foreground-background-slot-ratio`（数、任意、1 以上）。前景の頼み 1 件を何枠と数えるか。`max-concurrency` はそのアドレスの枠の総数（メモリの予算）のまま。`EndpointCapability` に宣言し、`LiveConfig` で読む（再起動なしで効く）。
- 使用量: アドレスごとに、前景を処理中のスロットは比の値、後回しを処理中のスロットは 1、前景の予約で空けてあるスロットは 1（スロットそのものを空けているだけで、メモリは使っていない）を足す。スロットの名前 `<host:port>#N` からアドレスを取る。
- 規則: 仕事は、そのスロットのアドレスの使用量に自分の分（前景は比、後回しは 1）を足して総数を超えないときだけ取る。
- 前景の仕事が先頭で待っている間、後回しの仕事は、取ったあとも前景 1 件分の余り（比）が残るときだけ取る。残らないなら、後回しが終わって空くのを待ち、空いた分を前景に回す（後回しが次々に埋めて前景が飢えるのを防ぐ）。走っている後回しを打ち切ることはしない。後回しは 1 件数秒なので、前景が待つのは数秒。
- 比を書かないアドレスは今と同じ動き（比 1 なら、使用量はスロットの数を超えない）。
- 例: b200-04 は `max-concurrency: 64`、`foreground-background-slot-ratio: 2`。前景だけなら 32 件、後回しだけなら 64 件、混ざれば 前景×2＋後回し ≤ 64。比はまず 2（測定では短い頼みで約 100 件、256K 前提で 32 件、比はおよそ 3）。KV キャッシュの使用率を見て直す。
- 試験: `JobQueue` の単体試験（前景だけなら総数÷比まで、後回しだけなら総数まで、混ざれば合計が総数以内、前景が待つ間は後回しが前景の分を残す、前景の分が空けば前景が先、予約は 1 と数える、比なしは今と同じ、別のアドレスに影響しない）。既存の試験がすべて通ること。比の判定を外すと新しい試験が赤になること。
- 配備: a007 の broker。像を焼いて入れ替える前に、ユーザーの承認と走行中の頼みが無いことを確かめる。
- [x] 仕様の確認（ユーザー 2026-11-07「作り直しましょう」）
- [x] 実装と単体試験（595179a の上限の形を置き換えた）: `JobQueue` がアドレスごとの使用量（前景×比＋後回し＋予約中の空き 1）で配る。`foreground-background-slot-ratio`（小数可、1 未満は 1）。新しい試験 `JobQueueSlotRatioTest` 9 本と `LiveConfigTest` 1 本、全 230 本緑（pod train-dev）。赤の確認: 比を 1 に固定すると新しい試験 9 本中 5 本が赤（残る 4 本のうち 3 本は比に関わらず通るのが正しい）。予約中の空きを比の分で数えると「予約は 1 と数える」の 1 本が赤
- [ ] 手元で本物の jar を起動して確かめる（本番の broker には触れない）
- [ ] a007 へ配備（承認後）、`max-concurrency: 64`・`foreground-background-slot-ratio: 2` を設定
