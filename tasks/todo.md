# quarkus-gpu-broker — 作業計画

## 方針
GPU ノード群（standalone vLLM）の前に立つ OpenAI 互換リバースプロキシ。FG/BG 優先・完了駆動・N=1。
状態機械: `S_init→S_base→S_dispatch→S_health→S_proxy`。
詳細は doc `StateMachineOverview_260605_oo01`（`doc_SCIVICS003/docs/quarkus-gpu-broker/020_specs/`）。

## 設計転換（2026-06）
`ManagedThreadPool` 前提の初版を破棄。broker は I/O 待ちのみ（CPU バウンド無し）なので純アクター設計に転換。
- `QueueActor` 1個: 優先度 deque（FG=前/BG=後）＋ idle 集合 ＋ active 集合
- `NodeActor` ノード毎: 空いたら `requestWork` で pull、`assign` で vLLM へ送り仮想スレッドで応答待ち、完了で再 `requestWork`
- N=1 は「アクターは1度に1メッセージ」で自動保証。detach は `withdraw` で drain 不要

## フェーズ1: 仕様を全部書き切る（先行）— 完了（アクター設計で書き直し済み）
- [x] 全体地図 `StateMachineOverview_260605_oo01`
- [x] `(S_init→S_base)` 優先度 deque `PriorityDeque_260605_oo01`
- [x] `(S_base→S_dispatch)` 完了駆動ディスパッチ `CompletionDrivenDispatch_260605_oo01`
- [x] `(S_dispatch→S_health)` 健全ノード集合・デタッチ `HealthAwareNodeSet_260605_oo01`
- [x] `(S_health→S_proxy)` OpenAI 互換前段・ストリーム中継 `OpenAiCompatibleProxy_260605_oo01`

## フェーズ2: 仕様に従って実装・テスト
- [x] `~/works/quarkus-gpu-broker` Maven プロジェクト scaffold（Quarkus 3.28.3 + pojo-actor 3.5.0、uber-jar、`com.scivicslab.gpubroker`、Java 21）
- [x] `(S_init→S_base)` `QueueActor` 優先度 deque … `mvn test -Dgroups="S_base"` GREEN（QueueActorPriorityTest）
- [x] `(S_base→S_dispatch)` `NodeActor` 完了駆動 pull・N=1 … `mvn test -Dgroups="S_base,S_dispatch"` GREEN（CompletionDrivenDispatchTest 2件＋S_base）
- [x] `(S_dispatch→S_health)` withdraw/attach・失敗再 submit … `mvn test -Dgroups="S_base,S_dispatch,S_health"` GREEN（HealthAwareNodeSetTest 3件＋累積、計6件）
- [x] `(S_health→S_proxy)` OpenAI 互換 HTTP 前段・ストリーム中継 … ユニット `StreamRelayTest`（`mvn test -Dgroups=...,S_proxy` で計7件 GREEN）＋ 統合 `OpenAiCompatibleProxyIT`（`mvn verify` GREEN、JVM 内スタブ上流 vLLM、Docker/DevServices 不使用）

## フェーズ3: 結果を見て仕様を改訂
- [x] 実装で判明した齟齬5点を各遷移文書へ反映（`doc_SCIVICS003/docs/quarkus-gpu-broker/020_specs/`）
  - ①②（同期バッファ中継・String 素通し）→ `OpenAiCompatibleProxy`：How to do it のリソースコードを実態化＋Under the Hood に「再パースせず素通し」「逐次配信でなく同期バッファ」の2節
  - ③（`start()`=attach）→ `CompletionDrivenDispatch`・`HealthAwareNodeSet` の Under the Hood に入場の attach 統一を明記
  - ④（`UpstreamLlmClient` はIF）→ `StateMachineOverview` 用語定義の種別を修正＋`HttpUpstreamLlmClient`/`StreamRelay`/`ResponseSink` を登場人物に追加。`CompletionDrivenDispatch` にインターフェース化の理由（テスト容易性）
  - ⑤（drain 専用テスト省略）→ `HealthAwareNodeSet` のテスト一覧と Under the Hood に、detached テストで実証・厳密タイミングは競合的で非決定的のため非実装、と明記
- [x] 状態の分割・遷移の追加：不要（4状態機械のまま実装が収まったため変更なし）
- [ ] Docusaurus 再ビルドはユーザー側で実施（このセッションではビルドしない）

## レビュー

### フェーズ2 完了（2026-06-26）
全4遷移を「実装 → テスト GREEN」で1つずつ確立した。

- `mvn test -Dgroups="S_base,S_dispatch,S_health,S_proxy"` … ユニット 7 件 GREEN
- `mvn verify` … 統合 `OpenAiCompatibleProxyIT` 1 件 GREEN（JVM 内スタブ上流、Docker 不使用）

主要クラス:
- `model`: `Priority`（`fromHeader`）, `Job`（id/priority＋body/sink/completion）, `ResponseSink`
- `actor`: `QueueActor`（優先度 deque＋idle＋active、submit/requestWork/withdraw/attach/pollNext）, `NodeActor`（完了駆動 pull、N=1、detach/attach、失敗再 submit）
- `llm`: `UpstreamLlmClient`（IF）, `StreamRelay`（`[DONE]` 完了検知）, `HttpUpstreamLlmClient`（実 HTTP）, `UpstreamException`
- `boot`: `GatewayActors`（ActorSystem＋ノード起動、`gpu-broker.node-urls`）
- `rest`: `ChatCompletionsResource`（`POST /v1/chat/completions`, `X-Llm-Priority`）

### フェーズ3 で仕様へ反映すべき実装上の判断（齟齬）
1. **応答中継は同期バッファ方式**にした。`StreamingOutput` で出力ストリームへ別スレッド（NodeActor）から書く危険を避け、sink にバッファ→`awaitCompletion` 後にリクエストスレッドで一括返却。live SSE 透過は後続の最適化。`OpenAiCompatibleProxy` の Under the Hood に追記する。
2. **リクエストボディは `ChatCompletionRequest` に再パースせず String で素通し**。リバースプロキシとしてはこの方が正確。仕様の Java 例を更新する。
3. **`NodeActor.start()` は `attach` を送る**（`requestWork` ではなく）。active 集合への参加と idle 入りを1経路に統一。`CompletionDrivenDispatch`/`HealthAwareNodeSet` に反映する。
4. **`UpstreamLlmClient` はクラスでなくインターフェース**（テストでスタブ注入するため）。全体地図の用語定義の「種別」を更新する。
5. `S_health` の「drain 不要」は専用テストではなく `detachedNode_isNeverAssigned`（ジョブが生存ノードへ流れる）で実証。在席ジョブ完了直後デタッチの厳密タイミング検証は本質的に競合的なため置かなかった旨を仕様に注記する。

### 実ノード E2E（フェーズB、2026-06-26）
実 vLLM ノード2台に対して broker 経由の中継を確認した（破壊操作なし、検証後にプロセス停止）。

- 上流: `192.168.5.16:8000` / `192.168.5.17:8000`、いずれも `google/gemma-4-26B-A4B-it`（`/v1/models` で確認）
- 起動: `java --add-opens java.base/java.lang=ALL-UNNAMED -Dquarkus.http.port=28085 -Dgpu-broker.node-urls=http://192.168.5.16:8000,http://192.168.5.17:8000 -jar target/quarkus-gpu-broker-0.1.0.jar`（prod profile）
- 確認した経路:
  - 非ストリーム BG（`X-Llm-Priority: background`）→ `BROKER_BG_OK` を中継
  - 非ストリーム FG（ヘッダ既定）→ `BROKER_FG_OK` を中継
  - `stream=true` → SSE チャンク＋終端 `data: [DONE]` を中継（`StreamRelay` の `[DONE]` 完了検知が実上流で発火）
- 後始末: broker プロセスを停止し残置なしを確認（port down / PID gone）
- 注記: ノード分散・N=1 の挙動はユニット/IT で検証済み。実機での同時負荷下の分散観測は未実施（必要なら別途）。

### フェーズC：live SSE 透過 ＋ 実機同時負荷観測（2026-06-27）

**live SSE 透過（バッファ方式を置換）**
- `StreamingResponseBridge`（スレッド安全キュー）を `ResponseSink` 実装として追加。`NodeActor` はチャンクをキューへ入れるだけ、HTTP 出力ストリームへは リクエストスレッドが `StreamingOutput` 内で drain して書く（出力の書き手を1つに保ち、別スレッド書き込みの危険を回避）。
- `Job` に上流 `Content-Type` 受け渡し用 `CompletableFuture<String>` を追加。`HttpUpstreamLlmClient` は応答ヘッダ受信時に伝え、`ChatCompletionsResource` は上流 `Content-Type` をそのままクライアントへ写す（`application/json` / `text/event-stream` 自動追従）。5xx 時は伝える前に投げ、再 submit で健全ノードが `Content-Type` を確定。
- `mvn verify` GREEN（ユニット7件＋IT 1件）。仕様（`OpenAiCompatibleProxy` の How to do it ＋ Under the Hood、`StateMachineOverview` 用語定義）を live 透過版へ更新済み。フェーズ3で記した「同期バッファ」節はこれに差し替え。

**実機 live 透過 実証（5.16/5.17）**
- `stream=true` で TTFB=0.20s / total=4.65s（最初のバイトが完了の約22倍速く到達＝逐次）。`content_type=text/event-stream; charset=utf-8` を上流から透過。本体は `data:{…}`…`data: [DONE]`。

**実機 同時負荷でノード分散・N=1（6本同時）**
- `NodeActor` に INFO ログ（`assign begin/done`：ノード URL・job・優先度）を追加して観測。
- 分散：5.16 が3件・5.17 が3件で均等。
- N=1：各ノードで `begin`→同 job の `done`→次の `begin`。同一ノードの同時2件は皆無。
- 完了駆動：各 `done` と同一ノードの次 `begin` がほぼ同一ミリ秒＝遊びなし。
- 優先度：即時2件の後、待ち行列からは FG が BG より先に発射＝実負荷でも FG が BG を追い越し。
- broker プロセスは検証後に停止し残置なしを確認。

**未対応（既知の限界）**
- 中継開始後（ストリーミング途中）の上流失敗は再 submit 対象外。接続前失敗のみ再 submit（部分送信済みのため途中再開は不可）。必要になれば別途設計。

### 既知の警告（無害）
- `mvn verify` 実行 JVM が Java 24 のため、テスト合格後のシャットダウン中に jboss-threads の `IllegalAccessError: ... java.base does not open java.lang`（thread-local reset）が出る。テスト結果には影響しない。消すなら failsafe の argLine に `--add-opens java.base/java.lang=ALL-UNNAMED` を追加するか Java 21 で実行する。

## フェーズD: 全面再設計（2026-08-10、`doc_SCIVICS003/docs/quarkus-gpu-broker`準拠）

単一vLLM前提のプロトタイプ（`QueueActor`/`NodeActor`/`GatewayActors`/`ChatCompletionsResource`）を、複数AIサービス種別（vLLM chat・YomiToku OCR・Marker OCR・embedding）・優先度予約・再試行上限・非同期submit-then-poll・graceful drainに対応した設計へ全面書き換え。着手前に`git init`しコミット（`297759a`）してから実施。設計文書は`doc_SCIVICS003/docs/quarkus-gpu-broker/030_development/`配下の各ディレクトリに対応。

- [x] `model`: `Job`（`RequestBody`/`Priority`/`ResponseSink`/`attempt`を持つ`record`、`nextAttempt()`）、`ResponseSink`（`start`/`emit`/`complete`/`fail`）、`RequestBody`新設
- [x] `actor`: `QueueActor`→`JobQueue`（`ActorRef`フィールド無し、FG予約`reservedUntil`、`drainPending`/`isIdle`）、`ROOT`新設、`NodeActor`→`AiServiceEndpoint`抽象＋`VllmChatEndpoint`/`YomiTokuOcrEndpoint`/`MarkerOcrEndpoint`/`EmbeddingEndpoint`
- [x] `config`（新設）: `EndpointKind`（定数ごとの本体で`createEndpoint`/`deriveQueueName`）
- [x] `llm`: `UpstreamLlmClient`系→`AiServiceClient`系に改名、`send(address, path, job)`が`ResponseSink`の`start`/`emit`/`complete`を呼ぶ形に
- [x] `response`（新設）: `StreamStart`、`StreamingResponseSink`（`UnicastProcessor`＋`UniEmitter`）、`PolledResponseSink`、`JobResult`（`contentType`付き）、`JobResultStore`（`@Scheduled`でTTL掃除）
- [x] `boot`: `GatewayActors`→`ActorSystemProducer`＋`JobQueueRegistry`（ノード×`EndpointKind`探査、`draining`フラグ、`ShutdownEvent`での排出）
- [x] `rest`: `ChatCompletionsResource`→`ProxyResource`（`RestMulti.fromUniResponse`で`Content-Type`確定後にストリーミング開始）、`AsyncJobResource`新設
- [x] `pom.xml`: `quarkus-scheduler`追加（`quarkus-config-yaml`は`broker.nodes`が単純リストのため不要と判断し追加せず）
- [x] テスト全面書き換え（`mvn install` GREEN、20件）: `JobQueuePriorityTest`（優先度・予約・drain）、`CompletionDrivenDispatchTest`（N=1・完了駆動）、`HealthAwareNodeSetTest`（requeue・再試行上限）、`EndpointKindTest`（`queueName`導出）、`JobResultStoreTest`（TTL掃除）
- [x] `mvn install`・実起動・`curl`スモークテスト（`/queue/{存在しない}`・`/jobs/{存在しない}`が`404`）で確認

### 実装で見つかった設計文書との齟齬（要フィードバック）
1. **`system.getActor(name)`への直接キャストはコンパイルエラー**。`<T> ActorRef<T> getActor(String)`はジェネリックメソッドであり、`(ActorRef<X>) system.getActor(name)`は`ActorRef<Object>`→`ActorRef<X>`の不正な変換になる。正しくは代入先の型でinferさせる（`ActorRef<X> ref = system.getActor(name);`、キャスト・`@SuppressWarnings`とも不要）。設計文書側のコード例（`AiServiceEndpoint`・`ProxyResource`等の`wake`/`dispatch`）を要修正。
2. **`ProxyResource`の`Uni.createFrom().emitter`内で`.join()`はブロッキングバグ**。emitterのconsumerはI/Oスレッド（Vert.xイベントループ）で動く可能性があり、`queue.ask(...).join()`はイベントループを止めうる。`queue.ask(q -> q.submit(job)).thenAccept(endpointId -> {...})`という非ブロッキング合成に変更。設計文書側も要修正。
3. **`broker.nodes`は`Optional<List<String>>`で受ける**。素の`List<String>`のまま値が無いと起動時に例外（`Failed to load config value`）。既存の`gpu-broker.node-urls`（フェーズA〜C）と同じ`Optional`パターンに合わせた。
4. `quarkus-config-yaml`は追加しなかった——`broker.nodes`は単純なカンマ区切りリストであり`application.properties`の既存の慣行（旧`gpu-broker.node-urls`と同じ）で足りるため。

### 未実装（既知）
- `ProxyResource`/`AsyncJobResource`の`X-Job-Priority`ヘッダー、`RestMulti`によるストリーミング応答実配線を、実際のAIサービス（vLLM等）またはstubに対するIT（`*IT.java`、k8s-dev環境）でまだ検証していない——プロジェクト方針上Docker/DevServices不使用のため、k8s-devへのデプロイが要る（`doc_SCIVICS003`の`e2e_tests`計画を参照）。
- `PolledResponseSink`が保持する`Content-Type`を`GET`応答へどう反映するかは、`JobResult`のフィールドとしてJSONに含める設計のみで、実際の`AsyncJobResource.fetch`はJackson既定シリアライズに任せている（`JobResult`が`record`なので自動的にフィールドが出力されるが、専用の検証はしていない）。

## フェーズE: CIDR表記対応 ＋ 実機E2E検証（2026-08-10）

`ChatCompletionsResource`削除後、フェーズDの実装が実際のHTTP通信を一度も経ていなかったため、実ネットワーク（192.168.5.0/26）に対して実際にリクエストを通した。あわせて、物理ノードをコンマ区切りIPで1台ずつ列挙する運用は5〜7台規模で辛いという指摘を受け、CIDR表記対応を追加した。

- [x] `boot/CidrRange.java`新設: `broker.nodes`の各エントリをCIDR表記なら展開、プレーンIPならそのまま返す。`/24`より大きい範囲（タイプミス対策）は`IllegalArgumentException`で拒否
- [x] `boot/JobQueueRegistry.onStart`: 逐次二重forループを`Executors.newVirtualThreadPerTaskExecutor()`による並列プローブへ変更（CIDR展開で候補数が数十〜256に増えるため）。並列化の安全性は`ActorRef.createChild`の`CopyOnWriteArraySet`と`queues`マップの`ConcurrentHashMap#computeIfAbsent`で担保されることをPOJO-actorのソースで確認済み
- [x] `mvn install`（27件GREEN）後、`-Dbroker.nodes=192.168.5.0/26`で実起動 → 実ノード3台（192.168.5.14/.16/.17）を3.365秒の起動時間内に発見
- [x] 実チャット補完リクエストで**実バグを発見**: `VLLM_CHAT`の`queueName`が`"vllm-" + モデル名`をそのまま連結しており、実際のモデルid`google/gemma-4-26B-A4B-it`（`/`を含む）で`/queue/{queueName}`ルーティングが壊れる（URLエンコード無しだと`404`）。ユニットテストはスラッシュを含まない合成モデル名しか使っておらず、この不具合を検出できていなかった
- [x] `config/EndpointKind.sanitizeForPathSegment(String)`追加（`[^A-Za-z0-9._-]`を`-`に置換）、`VLLM_CHAT.deriveQueueName`へ適用。`EndpointKindTest`に検証テスト追加、`mvn install`で28件GREEN
- [x] 修正後、実ネットワークへ再デプロイし`POST /queue/vllm-google-gemma-4-26B-A4B-it`（URLエンコード無し）で実vLLMから正常なchat completion応答を確認（`ProxyResource`→`JobQueue`→`AiServiceEndpoint`→`HttpAiServiceClient`→実vLLM→`StreamingResponseSink`/`RestMulti`→クライアント、という設計上の経路全体が実際に動くことを実証）
- [x] 設計文書（`012_configuration`・`016_endpoint_kinds`・`015_initialization`・`e2e_tests`）へCIDR対応とqueueName安全化を反映
- [x] 検証後、テスト用brokerプロセスは停止済み
