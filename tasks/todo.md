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
