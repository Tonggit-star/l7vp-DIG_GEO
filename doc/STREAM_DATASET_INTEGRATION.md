# 流式数据集接入方案（WebSocket 集成进 java 后台）

> 本文档记录「流式数据接入」功能的全部改动，便于后续集成到其他工程时按图索骥。
> 方案：Kafka → Flink → HTTP POST 推送到 java 后台 → 后台 WebSocket 广播 → L7VP 前端实时刷新图层。
> 流数据不落库，仅内存环形缓冲。

## 1. 整体架构

```
Kafka → Flink 处理 →  POST /api/projects/{pid}/datasets/{did}/stream/push  (HTTP, 端口 3001)
                                   │ (rows + op)
                                   ▼
                        ┌──────────────────────┐
                        │  java-server (单 jar) │
                        │  HTTP  :3001 (/l7vp, /api)   │
                        │  WS    :3002 (/ws/datasets/{did})
                        │  StreamSessionManager │  内存环形缓冲 + 订阅广播
                        └──────────┬───────────┘
                                   │ snapshot / data 帧
                                   ▼
                     L7VP 前端 (Builder / Share)
                     useStreamDatasets → datasetStore.updateDataset → 图层重渲染
```

- **单 jar 双端口**：前端 `npm run build:website` 产 dist → 复制到 `java-server/src/main/resources/static/` → `mvn package` 打成单 jar。HTTP(l7vp+api) 走 `server.port=3001`，WebSocket 走 `l7vp.ws.port=3002`（同 jar 第二个 Tomcat 连接器，共享 ServletContext）。
- **数据流向**：Flink 是数据生产者，java-server 是中转/广播，前端是消费者。
- **不落库**：流数据只过内存环形缓冲（重启无历史），与既有的快照型数据集（`DATASET_ROWS`）解耦。
- **复用 li-sdk 运行时**：流式数据集在 li-sdk 里仍是 `type:'local'`（内联数据型），通过 `metadata.stream=true` + 顶层 `_stream` 标记区分；运行时用 `datasetStore.updateDataset(id, {data})` 增量刷新，无需 li-sdk 改造。

## 2. 后端改动（java-server）

### 2.1 依赖
`java-server/pom.xml`：
1. 新增 WebSocket：
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```
2. 达梦驱动改相对路径（可移植，勿写死本机路径）：
```xml
<systemPath>${project.basedir}/lib/DmJdbcDriver8.jar</systemPath>
```
驱动 jar 放 `java-server/lib/` 下即可。
3. 覆盖 Lombok 版本（兼容 JDK 17/21/23，避免 `JCTree$JCImport.qualid NoSuchFieldError`）：
```xml
<properties>
  ...
  <lombok.version>1.18.36</lombok.version>
</properties>
```

### 2.2 配置
在 `java-server/src/main/resources/application.properties` 末尾追加：
```properties
l7vp.ws.port=${L7VP_WS_PORT:3002}                     # WS 端口；默认 3002，空=与 HTTP 同端口(3001)
l7vp.stream.max-window=${L7VP_STREAM_MAX_WINDOW:1000} # 环形缓冲行数
l7vp.stream.push-token=${L7VP_STREAM_PUSH_TOKEN:}     # Flink 推送鉴权令牌；空=不强制
```
环境变量（部署不改代码）：
- `L7VP_WS_PORT` — WebSocket 端口，默认 3002；留空则复用 3001。
- `L7VP_STREAM_MAX_WINDOW` — 默认 1000。
- `L7VP_STREAM_PUSH_TOKEN` — 留空=不鉴权（仅受控内网环境）。

### 2.3 新增类

| 文件 | 作用 |
|---|---|
| `config/StreamProperties.java` | `@ConfigurationProperties("l7vp")` 绑定 ws.port / stream.max-window / stream.push-token |
| `config/WebSocketConfig.java` | `@EnableWebSocket`，注册端点 `/ws/datasets/*`，握手拦截器；`l7vp.ws.port` 非空时追加 Tomcat 连接器 |
| `websocket/StreamHandshakeInterceptor.java` | 从 URI 解析 `datasetId` 放入握手属性；读 HttpSession（同源 Cookie，不强制拒绝） |
| `websocket/StreamWebSocketHandler.java` | `TextWebSocketHandler`：建立连接→订阅+下发 snapshot；关闭→注销 |
| `service/StreamSessionManager.java` | 核心：`Map<datasetId, {subscribers, ringBuffer, maxWindow}>`；`subscribe` / `unsubscribe` / `push`；广播 JSON 帧 |
| `dto/StreamPushRequest.java` | push 入参 `{ rows: [...], op: "append"\|"replace" }` |

### 2.4 修改类

- `controller/DatasetController.java`：新增
  ```
  POST /api/projects/{projectId}/datasets/{datasetId}/stream/push
  Header: X-Stream-Token（push-token 非空时必填）
  Body: { "rows": [{...}], "op": "append"|"replace" }
  返回: { datasetId, pushed, op, subscribers }
  ```
  鉴权：仅当 `l7vp.stream.push-token` 配置非空时校验 `X-Stream-Token`。数据集不存在返回 404。
- `service/DatasetService.java`：新增 `boolean exists(String datasetId)`。
- `service/ApplicationAssembler.java`：`assemble()` 中，`type='local'` 数据集若 `metadata.stream=true`，则输出 `data:[]` + `_stream:true`，**不**输出 `_lazy`/`_rowCount`（前端据此走 WS 而非分页懒加载）。`disassemble()` 无需改动（`metadata.stream` 经 `fillDatasetFields` 的 metadata 合并自动持久化）。

### 2.5 数据库
**无表结构变更**。流式数据集复用 `DATASETS` 表：
- `TYPE = 'local'`
- `METADATA`（CLOB JSON）含 `"stream": true, "maxWindow": N, "name": ...`
- `DATASET_COLUMNS` 正常存列定义（供前端展示字段、图层 parser 使用）
- `DATASET_ROWS` **不写入**（流数据不入库）

## 3. 前端改动（website）

### 3.1 新增文件

| 文件 | 作用 |
|---|---|
| `src/pages/Builder/widgets/StreamDataset/index.ts` | `implementEditorWidget` 注册，`container: {type:'Datasets', slot:'addDataset'}` |
| `src/pages/Builder/widgets/StreamDataset/StreamDataset.tsx` | 创建组件：数据集名 + 字段定义（name/type/comment）+ 滑动窗口。提交 `{ id, type:'local', metadata:{name, stream:true, maxWindow}, columns, data:[] }` |
| `src/hooks/useStreamDatasets.ts` | 核心 hook：为每个流式数据集建 `ws://host/ws/datasets/{id}` 连接，维护前端滑动窗口，300ms 节流后 `datasetStore.updateDataset(id,{data})` |

### 3.2 修改文件

- `src/hooks/index.ts`：导出 `useStreamDatasets`。
- `src/pages/Builder/editor-widgets.ts`：import + 注册 `StreamDataset` 到 `editorWidgetsWithBuilder`。
- `src/pages/Builder/index.tsx`：从 `defaultApplication.datasets` 过滤 `_stream===true`，调 `useStreamDatasets(liEditor.runtimeApp, ids, maxWindowMap, loaded)`。
- `src/pages/Share/index.tsx`：同上，用 `liRuntimeApp`，`enabled = loaded`。
- `config/config.ts`：UMI proxy 增加 `/ws`（`ws:true`）→ `ws://localhost:3001`（仅 wsPort/wsBaseUrl 均空时走代理；默认 wsPort=3002 直连后端）。
- `public/config.js`：新增 `wsBaseUrl`（空=按 wsPort 拼）、`wsPort`（默认 3002）、`streamMaxWindow`（默认 1000）。

## 4. 通信协议

### Flink → java-server（HTTP POST）
```
POST /api/projects/{projectId}/datasets/{datasetId}/stream/push
Content-Type: application/json
X-Stream-Token: <push-token，若配置>

{ "rows": [ {"lng":116.4,"lat":39.9,"value":12}, ... ], "op": "append" }
```
- `op: "append"`（默认）追加到缓冲并裁剪；`"replace"` 清空后写入。
- `rows` 每行是 `列名→值` 的对象，字段须与数据集列定义一致（Flink 侧按列名产出）。

### java-server → 前端（WebSocket）
端点：`ws://<host>:3002/ws/datasets/{datasetId}`（默认双端口；同站请求自动携带 Cookie 鉴权）。
帧：
```json
{ "type": "snapshot", "datasetId": "...", "op": "replace", "rows": [...] }   // 连接首帧：当前缓冲全量
{ "type": "data",     "datasetId": "...", "op": "append", "rows": [...] }     // 增量
{ "type": "data",     "datasetId": "...", "op": "replace", "rows": [...] }     // 全量替换
```

## 5. 端到端使用步骤

### 单 jar 双端口构建与运行

```bash
# 仓库根目录（本仓库为 lerna monorepo，脚本用 npm run 或 yarn run 均可执行同一脚本）
npm run build:website                      # 前端构建 → website/dist（yarn 等价：yarn run build:website）
rm -rf java-server/src/main/resources/static/*
cp -r website/dist/* java-server/src/main/resources/static/   # 复制到后端 static

cd java-server
mvn clean package -DskipTests             # 打单 jar（含前端）→ target/l7vp-server-1.0.0.jar
java -jar target/l7vp-server-1.0.0.jar    # 启动：HTTP 3001 + WS 3002
# 可选覆盖：
# L7VP_WS_PORT=3002 L7VP_STREAM_PUSH_TOKEN=xxx java -jar target/l7vp-server-1.0.0.jar
```

- 访问应用：`http://<host>:3001/l7vp/`（hash 路由）。
- WS 端点：`ws://<host>:3002/ws/datasets/{datasetId}`。

### 开发模式（前后端分离调试）

```bash
cd java-server && mvn spring-boot:run     # 后端 HTTP 3001 + WS 3002
# 另一个终端，仓库根
npm run start:website                     # UMI dev 8000，/api→3001（yarn 等价：yarn run start:website）
```
开发时前端 `config.js` 的 `wsPort=3002`，hook 直连 `ws://localhost:3002`（不走 UMI 代理）。

> **yarn vs npm**：本仓库用 lerna + yarn workspaces（根目录有 `yarn.lock`）。`yarn run xxx` 与 `npm run xxx` 跑的是 `package.json` 里同一个脚本，二者等价；建议与仓库一致用 yarn（`npm install -g yarn` 后首次 `yarn install`）。若只用 npm，首次 `npm install` 亦可，但 monorepo workspace 解析建议以 yarn 为准。

### 构建排坑

| 报错 | 原因 | 修复 |
|---|---|---|
| `Could not resolve dependencies ... DmJdbcDriver8 ... at specified path D:\...` | pom 里达梦驱动 systemPath 写死本机路径 | 把驱动 jar 放 `java-server/lib/`，systemPath 改 `${project.basedir}/lib/DmJdbcDriver8.jar` |
| `NoSuchFieldError: JCTree$JCImport does not have member field qualid` | 新版 JDK(17/21/23) 与 Spring Boot 2.7 自带旧 Lombok 不兼容 | pom `<properties>` 里加 `<lombok.version>1.18.36</lombok.version>` |

### 使用流程

1. **建表**：`DATASETS` 等表已存在则无需；流式数据集不改表结构。
2. **配置推送令牌**（生产建议）：`export L7VP_STREAM_PUSH_TOKEN=xxx`。
3. **创建流式数据集**：Builder → 数据集面板 → 添加 → 选「流式数据」→ 配字段 + 窗口 → 添加 → 保存项目。
4. **创建图层**：基于该数据集建点/线图层，配置 parser（如 x=lng, y=lat）。
5. **Flink 推数据**：对 `POST /api/projects/{pid}/datasets/{did}/stream/push` 持续推送，地图实时刷新。
6. **Share 页**：`/share/:id` 同样自动订阅 WS（DE 大屏 iframe 嵌入即可实时联动）。

## 6. 部署（nginx 可选，仅前置反代时）

单 jar 直连：HTTP 3001、WS 3002，无需 nginx。若用 nginx 反代：

```nginx
location /l7vp/ { proxy_pass http://后端:3001/l7vp/; }
location /api/  { proxy_pass http://后端:3001; }
location /ws/ {
    proxy_pass http://后端:3002;          # WS 独立端口
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;
}
```
此时把前端 `config.js` 的 `wsBaseUrl` 设为对外地址（如 `https://<域名>`），`wsPort` 置空，nginx 的 `/ws/` 统一转发到 3002。

## 7. 关键约定

- 流式数据集 = `DATASETS.TYPE='local'` + `METADATA.stream=true`；`DATASET_ROWS` 不写。
- 前端靠顶层 `_stream` 标记区分（由后端 assemble 注入，非持久化字段）。
- WS 鉴权同源走 Cookie/Session；Flink 推送走 `X-Stream-Token`。
- 前端滑动窗口节流 300ms，避免高频帧导致重渲染风暴。
- `maxWindow` 优先级：数据集 `metadata.maxWindow` > `L7VP_CONFIG.streamMaxWindow` > 后端 `l7vp.stream.max-window` > 1000。

## 8. 集成到其他工程 Checklist

- [ ] 后端：pom 加 `spring-boot-starter-websocket`
- [ ] 后端：复制 `config/StreamProperties`、`config/WebSocketConfig`、`websocket/*`、`service/StreamSessionManager`、`dto/StreamPushRequest`
- [ ] 后端：在数据集/资源 Controller 加 `push` 端点（依赖 `StreamSessionManager` + `StreamProperties` + 数据集存在性校验）
- [ ] 后端：`ApplicationAssembler`（或等价的装配器）对流式数据集输出 `data:[] + _stream:true`，跳过懒加载标记与行存储
- [ ] 后端：`application.properties` 加 `l7vp.ws.port` / `l7vp.stream.max-window` / `l7vp.stream.push-token`
- [ ] 前端：复制 `widgets/StreamDataset/*`、`hooks/useStreamDatasets.ts`，注册进编辑器 addDataset
- [ ] 前端：在 Builder / 运行时页注入 `useStreamDatasets(runtimeApp, ids, maxWindowMap, enabled)`
- [ ] 前端：dev 代理加 `/ws`（`ws:true`）；运行时配置加 `wsBaseUrl` / `wsPort` / `streamMaxWindow`
- [ ] 部署：单 jar 双端口（HTTP 3001 + WS 3002）；nginx 反代时 `/ws/` 转发到 3002 并升级协议
- [ ] 部署：`npm run build:website` → 复制 dist 到 `java-server/src/main/resources/static/` → `mvn package` 单 jar
- [ ] 部署：nginx `/ws` WebSocket 升级；Docker 端口暴露
- [ ] Flink：实现 `POST .../stream/push` 推送（带 `X-Stream-Token`），行字段与列定义对齐
- [ ] （可选，推荐）Flink 作业内嵌集成：新建 `java-server/flink-job/` 独立工程，antrun 自动打进 server jar，见「9. Flink 集群作业接入」
- [ ] 后端配置：`flink.cluster.url` / `flink.push.base-url` / `flink.push.token` / `flink.kafka.bootstrap-servers` 等（见 9.4）
- [ ] 数据契约：Kafka 每条消息 = 一个「键=数据集列名」的 JSON 行对象

## 9. Flink 集群作业接入（Kafka → Flink → 回推，作业内嵌进 server jar）

> 大流量、需要真实窗口/聚合处理时，在 **独立 Flink 集群** 上跑流式作业，作业把结果**批量回推**
> 本服务的 `/stream/push`。为保证**部署仍只有一个 jar**：作业源码放在 `java-server/flink-job/`，
> 由 java-server 构建期（maven-antrun）自动打成「类平铺作业 jar」并内嵌进 classpath；运行期
> java-server 把内嵌 jar 上传到 Flink 集群并提交。

### 9.1 形态与打包

```
java-server/
├── pom.xml                  # 单模块不动；generate-resources 阶段自动 mvn -f flink-job 打包并拷入
├── flink-job/               # 新独立 Maven 工程（独立打包，被上面自动构建）
│   ├── pom.xml              #   shade 平铺：flink 核心 provided；connector-kafka/jackson/httpclient 打进 jar
│   └── src/main/java/com/antv/l7vp/flink/StreamDatasetJob.java
└── src/main/resources/flink-job/l7vp-stream-job.jar   # 构建产物（内嵌资源，勿手改）
```

- 用户仍只执行 `cd java-server && mvn clean package` → **单 jar**（前端 static + 内嵌作业 jar）。
- 跳过自动重建：`mvn clean package -Dflink.job.skip=true`（需已有内嵌 jar）。
- 也可配置 `flink.job.jar-file-path` 指向外部作业 jar（改作业不重打 server）。

### 9.2 后端新增/修改（java-server，单模块）

| 文件 | 改动 |
|---|---|
| `config/FlinkJobProperties.java`（新） | `@ConfigurationProperties("flink")`：cluster/job/push/kafka + `autoStart` |
| `service/FlinkJobService.java`（新） | Flink REST 客户端：`startJob/status/stop/stopIfStreaming/stopProjectStreamJobs/startProjectStreamJobsIfAuto`；按作业名 `l7vp-stream-{datasetId}` 幂等去重 |
| `controller/DatasetController.java` | 新增 3 端点（见 9.3） |
| `service/DatasetService.java` | `deleteDataset` 前置 `stopIfStreaming`（删数据集→取消作业） |
| `service/ProjectService.java` | `deleteProject` 前置 `stopProjectStreamJobs`；`create/update` 后 `startProjectStreamJobsIfAuto`（自动提交） |
| `service/ApplicationAssembler.java` | disassemble 清理消失数据集时 `stopIfStreaming` |
| `pom.xml` | maven-antrun-plugin 自动构建并内嵌作业 jar |

### 9.3 REST

| 方法/路径 | 语义 |
|---|---|
| `POST /api/projects/{pid}/datasets/{did}/stream/job/start` | 幂等提交/复用作业。Body(可选)`{topic,bootstrapServers,parallelism,batchSize,batchMs}`；未提供读数据集 `metadata.kafka` → `flink.kafka.*`。已在运行则返回 `reused:true` |
| `GET  .../stream/job/status` | `RUNNING`(带 jobId)/`NOT_RUNNING`/`CLUSTER_UNREACHABLE` |
| `DELETE .../stream/job/stop` | 取消作业（未运行/集群不可达视为成功） |

```bash
curl -X POST localhost:3001/api/projects/P/datasets/D/stream/job/start \
  -H 'Content-Type: application/json' -d '{"topic":"vehicle-track"}'
curl  localhost:3001/api/projects/P/datasets/D/stream/job/status
curl -X DELETE localhost:3001/api/projects/P/datasets/D/stream/job/stop
```

### 9.4 配置（`application.properties`，环境变量覆盖）

```properties
flink.auto-start=${FLINK_AUTO_START:false}                        # true: 保存项目自动为含 kafka 的流式数据集提交作业
flink.cluster.url=${FLINK_CLUSTER_URL:http://localhost:8081}      # Flink REST
flink.job.entry-class=${FLINK_JOB_ENTRY_CLASS:com.antv.l7vp.flink.StreamDatasetJob}
flink.job.parallelism=${FLINK_JOB_PARALLELISM:1}
flink.job.batch-size=${FLINK_JOB_BATCH_SIZE:200}                  # 单批回推行数
flink.job.batch-ms=${FLINK_JOB_BATCH_MS:500}                      # 攒批窗口
flink.job.jar-file-path=${FLINK_JOB_JAR_FILE_PATH:}               # 空=用内嵌
flink.push.base-url=${FLINK_PUSH_BASE_URL:http://localhost:3001}  # 集群能访问到本服务，生产必改
flink.push.token=${FLINK_PUSH_TOKEN:${L7VP_STREAM_PUSH_TOKEN:}}   # 回推鉴权，空=不带
flink.kafka.bootstrap-servers=${FLINK_KAFKA_BOOTSTRAP_SERVERS:}
flink.kafka.group-id-prefix=${FLINK_KAFKA_GROUP_ID_PREFIX:l7vp-stream}
```
> ⚠️ 生产必须把 `FLINK_PUSH_BASE_URL` 设为 Flink 集群**能访问到**的地址（如内网 `http://l7vp:3001`），
> 并把 `FLINK_AUTO_START`、`FLINK_CLUSTER_URL` 一起配置，否则作业无法回推/不会自动拉起。

### 9.5 数据契约 & 提交参数

- 每条 Kafka 消息 = 一个 JSON 行对象，**键 = 数据集列名**（与前端 parser 列对应）。
- Flink 端 EnrichFunction 只做合法性校验 + 透传（占位），真实加工写在这里。
- 回推 body：`{"rows":[{...},...],"op":"append"}`，加 `X-Stream-Token`（token 非空时）。
- 批量异步：invoke 只入队，攒满 `batchSize` 或每 `batchMs` 触发一次 POST（HttpClient keep-alive），
  高吞吐不阻塞算子；回推失败记日志丢弃（生产可按需加重试/落盘）。
- 作业名 `l7vp-stream-{datasetId}` → java-server submit 前按名查 overview 幂等去重。
- Flink REST 调用链：`POST /jars/upload`(multipart part=jarfile) → `POST /jars/{id}/run`
  (`entryClass/programArgs/parallelism`) → `GET /jobs/overview`(按 name) → `PATCH /jobs/{id}?mode=cancel`。

### 9.6 生命周期

- 自动：数据集带 `metadata.kafka.topic`（前端「流式数据」创建组件录入）→ 保存项目（`FLINK_AUTO_START=true`）自动提交；
  删数据集 / 删项目 / 数据集从项目配置移除 → 自动取消。
- 手动：上述 3 个 REST 端点控制（无需动前端）。
- 并行度>1 时多任务回推可能交错（地图点场景无碍）；需严格按 key 保序再叠加 KeyedStream。

### 9.7 构建验证

```bash
cd java-server && mvn clean package -DskipTests
# 单 jar 应含 BOOT-INF/classes/flink-job/l7vp-stream-job.jar
unzip -l target/l7vp-server-1.0.0.jar | grep flink-job

# flink-job 单独手工打包（诊断用）：
cd java-server/flink-job && mvn clean package
# → target/l7vp-stream-job.jar
```
验证链路：前端建流式数据集并填 Kafka topic → `FLINK_AUTO_START=true FLINK_CLUSTER_URL=...` 起后端 →
保存项目 → Flink UI 出现 `l7vp-stream-{datasetId}` 作业 → 向 topic 灌数据 → 地图实时刷新 →
删数据集/`DELETE .../stop` 后作业消失。
