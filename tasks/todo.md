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
