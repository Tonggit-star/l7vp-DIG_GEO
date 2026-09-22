# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

基于 AntV L7VP（LocationInsight）改造的地理可视化平台，面向内网/政务部署。两个关键组成：

- **`website/`** — React + UMI 4 前端（`@antv/li-website`），即产品本体：项目管理、地图编辑器(Builder)、纯地图嵌入页(Share)、预览页。
- **`java-server/`** — Spring Boot 2.7 (Java 8) 后端，端口 3001，达梦 DM8 存储（schema `DIG_GEO`），替代早期 Node.js 方案（`archive/legacy-node-server` 已废弃清理）。

当前活跃分支 `feat/function-quanxianrenzheng`。最近的工作重点是：中台数据集成、SSO 单点登录、数据资源两级选择（库列表 + 表列表）、DE-L7 跨项目联动、流式目标历史轨迹查询。

## 常用命令

### 后端（java-server/）

```bash
cd java-server
mvn spring-boot:run        # 启动，监听 3001
mvn test                   # 运行全部测试（JUnit）
mvn test -Dtest=DatasetServiceTest    # 运行单个测试类
mvn -DskipTests package        # 打包单 jar（跳过单元测试）
mvn clean package -DskipTests  # 打包（clean + 跳过测试）
```

打包注意：`mvn package` 会经 maven-antrun 自动先构建并内嵌 `flink-job/l7vp-stream-job.jar`（`-Dflink.job.skip=true` 可跳过）。跳过单测是因为部分测试需连接达梦/外部依赖，仅打包时推荐加 `-DskipTests`。

### 前端（website/）

```bash
# 从仓库根目录（lerna monorepo，npm run 与 yarn run 跑的是同一份 package.json 脚本）
npm run start:website      # UMI dev，默认 8000，/api 代理到 3001
npm run start:editor       # 编辑器资产研发（@antv/li-editor）

# 或直接进目录
cd website && npm run dev
cd website && npm run build    # 前端打包（产物在 website/dist）
```

### Monorepo 根脚本

```bash
npm run start:sdk             # @antv/li-sdk dev
npm run start:core-assets     # @antv/li-core-assets dev
npm run start:analysis-assets # @antv/li-analysis-assets dev
npm run lint              # eslint + stylelint
npm run lint-fix
npm run build:website     # 构建站点（@antv/li-website → website/dist）
```

依赖安装：根依赖用 `npm install <pkg>`（workspace 根），子包依赖用 `npm install <pkg> --workspace <pkg>`（如 `--workspace @antv/li-sdk`）。

### 打包发布（单 jar，含前端）

改动涉及前端（li-sdk / li-editor / 资产包 / website）时，**必须先构建前端并复制进后端静态目录，再打 jar**，否则 jar 内仍是旧前端：

```bash
# 0) 若改了子包源码（@antv/li-editor 等），先重建该包，让 website 用上新 dist
npm run build:editor         # 按需：build:sdk / build:core-assets / build:analysis-assets

# 1) 构建前端 → website/dist
npm run build:website        # 或 cd website && npm run build

# 2) 复制前端产物到后端静态目录
rm -rf java-server/src/main/resources/static/*
cp -r website/dist/* java-server/src/main/resources/static/

# 3) 打单 jar（含前端静态 + 自动内嵌 flink-job jar；跳过单测）
cd java-server && mvn clean package -DskipTests
```

## 架构

### 前后端通信

前端所有 API 走相对路径 `/api`（见 `website/src/services/`，原生 `fetch`，无 axios 封装）。UMI dev 代理 `/api`、`/thumbnails` → `http://localhost:3001`（`website/config/config.ts`）。构建产物部署时由 nginx 转发。

### 后端分层（java-server，包 `com.antv.l7vp`）

`controller` → `service` → `repository`（JdbcTemplate 直接拼 SQL，非 MyBatis/JPA），`model` 为实体，`dto` 为请求/响应。核心接口：

- `/api/auth/*` — SSO OAuth2 认证（Authorization Code + password grant 兜底），`UserSession` 存 HttpSession，各接口从 `AuthController.getSession(request)` 取登录态，未登录返回 401。
- `/api/zhongtai/*` — 中台数据代理（`ZhongtaiApiController`）：库列表、数据资源列表、资源预览/全量查询。携带当前用户的 `Authorization: Bearer <token>`、`orgId`、`spaceId`、`scopeType` 请求中台，用 `connect/info` 拿连接信息后通过 JDBC 直连查 MySQL/Doris 表数据。
- `/api/projects|datasets|layers|tile-configs|icons|db-connections|thumbnails` — 本地业务资源。

### 数据库（达梦 DM8，schema `DIG_GEO`）

10 张表（`java-server/src/main/resources/schema.sql`）：PROJECTS、DATASETS、DATASET_COLUMNS、DATASET_ROWS、LAYERS、WIDGETS、TILE_CONFIG、ICON_CATEGORIES、ICONS、**DB_CONNECTIONS**（数据源连接配置，2026-09 从 `doc/sql/v2-new-tables.sql` 并进来——此前新环境照 `schema.sql` 建库会漏掉它，`/api/db-connections/*` 全报「无效的表或视图名」）。项目/数据集/图层是自建的核心业务表；数据集数据行存 `DATASET_ROWS.ROW_DATA`（JSON 数组，按下标对应列）。

### 前端页面（website/src/pages）

- `/project` — 项目管理主页（含数据资源两级选择：库列表 → 表列表）
- `/builder/:id` — 地图编辑器
- `/app/:id`、`/template/:id` — 预览页
- `/share/:id` — 纯地图嵌入页，**DE-L7 联动入口**：监听 `window.postMessage`（`highlight` / `highlightLines` / `filter` / `clearHighlight` / `reset`），用 `de_highlight_*` 前缀的 dataset/layer 动态加点/线高亮（见 `Share/index.tsx`）。
- `/asset-market` — 资产市场

运行时配置：`website/public/config.js` 写入 `window.L7VP_CONFIG`（底图、`zhongtaiBaseUrl` 等），部署时改该文件即可，无需重新构建。

### DE-L7 跨项目联动

DataEase 大屏以 iframe 嵌入 `/share/:id` 页面，通过 postMessage 通信。完整方案见 `doc/L7_DE_LINKAGE_PLAN.md`、`doc/DE_L7_INTEGRATION.md`。所有联动相关代码注释带 `L7_INTEGRATION:` 标记。

## 关键约定与注意

- **达梦 JDBC 驱动是 system-scope 本地依赖**：`java-server/pom.xml` 里 `<systemPath>` 用相对路径 `${project.basedir}/lib/DmJdbcDriver8.jar`，驱动 jar 放在 `java-server/lib/` 下即可（部署时把驱动 jar 放到该目录）。
- **schema 不自动建表**：`spring.sql.init.mode=never`，需在达梦中手动执行 `schema.sql`。
- **达梦 schema 切换**：`application.properties` 用 `spring.datasource.hikari.connection-init-sql=SET SCHEMA DIG_GEO`，否则未加前缀的表名会报「无效的表或视图名」。
- **后端配置优先用环境变量覆盖**（部署不改代码）：`ZHONGTAI_BASE_URL`、`APP_FRONTEND_URL`、`ZHONGTAI_CLIENT_ID/SECRET`、`ZHONGTAI_SPACE_ID`（中台空间 ID 现在由前端从 URL 动态读取传给后端，配置值是兜底）。详见 `java-server/src/main/resources/application.properties` 顶部注释。
- **登录模式 `l7vp.auth.mode=sso|local`**（`application.properties`，默认 `sso`，改后重新打包）：`sso`=中台 SSO 集成登录（未登录跳 SSO）；`local`=本地开放访问，后端 `LocalUserFilter` 自动以默认本地用户建会话，免登录直接进首页。local 模式下业务接口可正常使用，中台数据资源 `/api/zhongtai/*` 因会话无 accessToken 返回 401；前端据此隐藏「退出登录」（`/api/auth/status` 返回 `authMode`）。
- **中台数据预览/查询是 JDBC 直连**，`resourceName` 是物理表名（非显示名），不可把 `resourceName`（可能为中文显示名）当 SQL 表名用。
- 前端 `OFFLINE_MODE=true`（`website/config/config.ts`）：禁用外部 CDN 与统计脚本，`history` 为 hash 模式。
- **改 `packages/li-*` 后重建：必须 `cd packages/<pkg> && npm run build`，且要看 dist 产物确认**（三个坑，2026-09 实测）：
  1. 根 `lerna run build` 会**命中 Nx 缓存假成功**——exit 0、打印 "Successfully ran target build"，但 `dist` 时间戳没变、新文件根本没生成（伴随 "Invalid Cache Directory ... was not generated on this machine" 警告）。加 `--skip-nx-cache` 又会因依赖包（li-p2/li-sdk）单独构建失败而中断，**所以用包内直连 `cd packages/<pkg> && npm run build` 最稳**。
  2. `.fatherrc.base.ts` 里 `cjs` 只在 `NODE_ENV=production` 时才输出；普通 `npm run build` **只出 `dist/esm`**。website 走 `module` 字段（esm），但 `node_modules` 里的旧 `cjs` 会残留成僵尸——要两种格式都新就 `NODE_ENV=production npm run build`。
  3. ~~`node_modules/@antv/li-*` 是**独立拷贝（非 symlink）**，构建完必须 `cp -r packages/<pkg>/dist/. node_modules/@antv/<pkg>/dist/`~~
     **【2026-09 更正】这条结论是错的，不要再照着做**：干净 `npm install` 之后，`node_modules/@antv/li-*`
     是**指向 `packages/*` 的 symlink**（`ls -la node_modules/@antv/ | grep li-` 一眼可见），
     改包内源码重新 build 后 website 立刻用到新 dist，**中间不需要任何拷贝**。
     旧结论应来自某个「装了 registry 发布版、又残留另一份拷贝」的环境。
     真正需要拷贝的只有 `@antv/l7*`（L7 运行时本身），那才是独立安装包。
     仍然成立的是：**验收看 dist 时间戳/新文件，别只看 exit code**。
  4. **装依赖必须带 `--legacy-peer-deps`**：`@difizen/weave` 声明 `peerDependencies: antd@3`，
     而 website 用 antd 5，不带这个参数 npm 10 会直接 ERESOLVE 失败。
     `~/.npm` 不可写时（沙箱/CI）加 `npm_config_cache=<仓库内目录>`。
     *（2026-09 实测三步跑通：`npm install --legacy-peer-deps` → `npm run build:package` → `npm run build:website`；
     build:package 会同时产出 esm 与 cjs（带 declaration），当场没有再出现上面第 1 条的 Nx 假成功。）*
- 部署见 `offline-deploy/README.md`（单容器：Java 内置、前端静态内嵌在 jar 里，不需要独立 nginx）。
  `DEPLOY.md`（老的 Docker 双容器方案，对应已删除的 `deploy/`）已废弃，只作历史参考。

## 流式数据集（WebSocket 实时接入）

流式数据通过 Kafka → Flink 处理后，经 `POST /api/projects/{projectId}/datasets/{datasetId}/stream/push` 推送到 java 后台，后端再经 WebSocket `/ws/datasets/{datasetId}` 广播给前端，实时刷新图层。**流数据不落库**，仅内存环形缓冲。完整方案见 `doc/STREAM_DATASET_INTEGRATION.md`。

- **单 jar 双端口**：前端 `build:website` → dist 复制到 `java-server/src/main/resources/static/` → `mvn package` 单 jar。HTTP（l7vp + /api）走 `server.port=3001`，WebSocket 走 `l7vp.ws.port=3002`（同 jar 第二个 Tomcat 连接器）。
- 流式数据集 = `DATASETS.TYPE='local'` + `METADATA.stream=true`；`DATASET_ROWS` 不写入。后端 `ApplicationAssembler.assemble()` 对其输出 `data:[] + _stream:true`，跳过 `_lazy`。
- **流式数据不落编辑器侧静态 `data`**（assemble 恒返回 `data:[]`、不写 `DATASET_ROWS`），图层上画的点来自运行时 `datasetStore`。数据集面板的「共 N 行」对流式数据集显示的是**实时窗口行数**：`useStreamDatasets` 每次节流 flush 后把 `data.length` 写入 `@antv/li-sdk` 的 `streamDatasetStats`（`utils/stream-stats.ts`），`DatasetItem` 用 `useDatasetStreamCount` 订阅展示并带「（实时）」标识；流式数据不入库，故后端/静态层永远无持久化行是正常的。
- 前端创建入口：Builder 数据集面板「流式数据」组件（`website/src/pages/Builder/widgets/StreamDataset`）；运行时 hook `useStreamDatasets`（`website/src/hooks/useStreamDatasets.ts`）建 WS 连接、维护滑动窗口、节流写入 `datasetStore.updateDataset`。前端经 `config.js` 的 `wsPort`（默认 3002）连到 WS 端口。
- **Builder 的 WS 订阅跟随「实时 application」而非页面加载快照**：`Builder/index.tsx` 用 `getStreamDatasetIds`（同时认 `_stream` 与 `metadata.stream`）从 liEditor `change` 携带的最新 application 推导订阅列表（首个 change 前回退 `defaultApplication`），因此**新加/删除流式数据集无需刷新页面**即可自动连上/断开 WS。Share 页为只读视图，固定用加载快照订阅。
- 后端核心：`config/WebSocketConfig`（端点 + 第二端口连接器）、`websocket/StreamWebSocketHandler` + `StreamHandshakeInterceptor`、`service/StreamSessionManager`（环形缓冲/订阅广播）。
- 配置（`application.properties` 末尾，环境变量覆盖）：`L7VP_WS_PORT`（默认 3002，空=与 HTTP 同端口 3001）、`L7VP_STREAM_MAX_WINDOW`（默认 1000）、`L7VP_STREAM_PUSH_TOKEN`（空=不强制推送鉴权）。
- nginx 反代时 `/ws/` 转发到 3002 并做 WebSocket 升级（配置样例见 `offline-deploy/README.md` §五）；直连则无需 nginx。
- Flink 推送带 `X-Stream-Token` 头；行字段须与数据集列定义对齐。

## 数据集面板与「编辑数据集」（li-editor）

数据集注册走 Builder 数据集面板的「新增数据集」弹窗（`DatasetsPanel/AddDataset`）：弹窗按 `Datasets/addDataset` 容器槽位渲染各注册组件（示例数据 / 流式数据 / 数据库连接 / 中台 API / 中台数据表 / 文件上传），组件统一收 `{onSubmit, onCancel}`，各自产出 `DatasetSchema` 后 `updateState` push 进 `state.datasets`。

- **注册后可就地编辑**：「编辑数据集」弹窗（`DatasetsPanel/EditDataset`）——入口在数据集卡片上（`⋯` 菜单的「编辑数据集」+ 悬停编辑图标 `EditOutlined`），由 `DatasetsPanel` 持有 `editDatasetId` 状态渲染。可改：名称/描述（所有数据集）、列定义（仅流式与 `type==='remote'`；本地非流式的列由数据行推导故只读）、流式参数（`maxWindow`、`streamKey`、`kafka.topic/bootstrapServers`）、`refreshInterval`（remote）。提交按 **id 原地替换** `state.datasets[i]`，图层绑定（`sourceConfig.datasetId`/顶层 `dataset`）不失效。
- **「替换数据集」（`ReplaceDataset`）是换数据源**（重开新增表单、复用同 id 覆盖）；「编辑数据集」只改配置。两者别混。
- **改 Kafka topic / `streamKey` 后必须点卡片上的「重新加载流式数据」**：`FlinkJobService.startJob` 按作业名 `l7vp-stream-{datasetId}` 幂等，**发现同名运行中作业直接复用返回**（`FlinkJobService.java:148`），保存项目不会自动重启作业，新参数不生效。`POST .../stream/reload` 才会停旧作业→清缓冲→重新提交。
- **卡片操作区图标的显隐机制**：`.actionsItem` 默认 `opacity:0`，仅当悬停操作区且命中 `data-comp="dataset-actions-item_hover-show"` 时才置 1（`DatasetItem/style.ts`）。**新增图标必须用这个 `data-comp` 值**，用别的自定义值会永远不显示（曾把「重新加载」写成 `_stream-reload`，表现为按钮空白）。

## 图层面板与「图层备注」（li-editor）

图层编辑面板（点图层卡片进入）的「基础」折叠区在 **数据来源 / 可视化类型** 之后还有一行 **图层备注**（多行输入框，≤200 字），供用户记录该图层的用途、数据口径等说明。

- **存进 `layer.metadata.description`**，纯说明信息、不参与渲染、智能体侧也读不到（那套接口返回的是 `visConfig`/`sourceConfig`）。表单骨架在 `LayersPanel/LayerAttribute/BaseFormSchemaField/schema.tsx`（`'x-component': 'TextArea'` 需在 `BaseFormSchemaField/index.tsx` 的 `createSchemaField` 里注册 `TextArea: Input.TextArea`）；值经 `LayerForm` 的 `onDescriptionChange` 回调（`onFieldValueChange('description')`）回写到 `LayerAttribute/index.tsx` 的 `updateState`。
- **无需后端改动**：`LAYERS` 表没有 metadata 列，但 `ApplicationAssembler.fillLayerFields` 会把 `{id,name,type,dataset,order,createTime}` 之外的键整体塞进 `VIS_CONFIG` JSON，`assemble()` 再摊平回顶层，所以 `metadata` 与 `description` 自动往返持久化。保存链路也不剥字段（`validateLayers` 只按 `isValidLayer` 过滤，不裁字段）。
- 图层编辑面板与图层列表**互斥显示**（打开面板时列表加 `panelContent_hidden`），所以切换图层必然先返回、`LayerForm` 必然重新挂载——`useMemo(() => createForm(...), [])` 里读 `config.metadata?.description` 当初始值不会串层。

## 告警通知组件 AlertNotify（li-analysis-assets）

`packages/li-analysis-assets/src/widgets/AlertNotify/`（组件资产「告警通知」）：地图上的滚动告警面板，默认在**右上角**，同时显示 10 条，紧急置顶、告警靠前、通知垫底，紧急红 / 告警淡黄 / 通知常规色。`type: 'Auto'` + larkmap `<CustomControl position="topright">`（L7 控件按方位自动堆叠，不用自己算偏移）。加进 `widgets/index.ts` 的导出即生效——资产包在 website 侧是 `BUILTIN_ASSET_PACKAGES` 整包内置，**不用改 website**；配置存 `WIDGETS.PROPERTIES`（CLOB JSON），**后端零改动**。

- **行内文字色跟着底色反着选**（`contrast.ts`，2026-09 修）：告警行会内联一个**浅色**级别底色（紧急 `#FFF1F0` / 告警 `#FFF7CC`），而承载它的 Builder 页是 antd **深色**主题（`website/src/constants/theme.ts`）——标题取 `colorText`(rgba(255,255,255,.85))、描述与时间取 `colorTextDescription`(rgba(255,255,255,.45))，**近白字叠在近白底上，描述那行根本看不见**（用户报的问题）。故 `pickRowTextColors(bg)` 按底色感知亮度（0.299R+0.587G+0.114B）判断：偏亮 → 标题 `rgba(0,0,0,.88)` / 描述·时间 `rgba(0,0,0,.6)` 的**固定黑系**（深色主题下没有「深色文字」token）；透明（通知级）、认不出、本身偏暗 → 返回 `null` = 沿用主题色。属性面板可自定义各级底色，所以按**实际颜色**算，不写死级别。改动落在 `AlertList.tsx`（行内 style 覆盖 class 的 color）+ `Component.tsx` 的 `textColorsOf`。
- **排序与到达策略**（`alertQueue.ts`，纯函数无 React 依赖）：级别权重升序（紧急 0/告警 1/通知 2）→ 同级时间倒序；入队后超 `queueSize` 时**从队尾挤出**，队尾正是「级别最低且最旧」的，所以紧急/告警不会被通知洪流挤掉。新条目到达：**紧急强制回顶**（`urgentJumpTop` 可关）、**告警拉回顶部**、**通知不打断当前视图**。只有「刚到的」紧急/告警才闪一下（`FRESH_HIGHLIGHT_MS=30s`，首屏灌进来的历史数据不闪）。
- **轮播**（`useAlertQueue.ts`）：游标在 `[0, total-1]`，窗口按 `total` 取模循环，所以 10 条以外的信息也能滚到；队列不足一屏时按序返回不循环（避免同一条重复出现）。悬停暂停**只停滚动、不停收数**；收数/定时器用 ref 镜像最新值，避免把定时器写进 effect 依赖反复重建。
- **数据源是抽象出来的**（`source.ts`）：`AlertSource = (onItem) => 退订函数`，本期只有 `createMockSource`（20 条种子：3 紧急/6 告警/11 通知，时间在最近 40 分钟内 → 可选按 `simulateIntervalSec` 持续产生新告警）。以后接真实数据**只改 `source.ts`**：① HTTP 轮询（后端加 `GET /api/projects/{pid}/alerts?since=&limit=`，与现有 `/api/projects/*` 同形态，推荐先做）；② WS 推送（复用 3002 端口的 `/ws/alerts/{projectId}`）。`AlertItem` 就是契约，渲染层不用动。
- **本包没有前端测试框架**，所以排序/入队/窗口/到达策略/种子数据这些纯逻辑放在 `alertQueue.ts`，用一次性 node 脚本（`esbuild --bundle` + react stub）跑断言（38 项，含「上限挤出通知不动紧急」「紧急回顶」「通知不打断」「首屏历史不闪」），脚本不入库、不进 CI。
- **坑（写 widget 属性面板时必看）**：① widget 的 `registerForm.schema` 是「**字段名 → schema 的平铺映射**」，外层**不要**包 `type: 'object'` / `properties`——`WidgetForm` 会把它整体摊进 `properties`（这点与图层样式面板 `registerForm` 的用法相反，后者给的是完整 schema）；② 属性面板基础组件里**没有 `ColorPicker`**，要用必须随 `registerForm` 一起给 `components: { ColorPicker }`（`WidgetSchemaField` 把 `registerForm.components` 透传给 `SchemaField`，merge 覆盖基础组件）——`AgentBridge`（高亮颜色）、`RightClickMenu`（轨迹颜色）、`AlertNotify`（各级配色）三处都要带上，**漏了会是「颜色项渲染为空」而不是报错**。

## Flink 集群作业接入（大流量 Kafka→Flink→回推）

单 jar 形态：作业源码在 `java-server/flink-job/`（独立 Maven 工程，shade 平铺），java-server 构建期由 maven-antrun 自动打成 `l7vp-stream-job.jar` 内嵌进 classpath，运行期 `FlinkJobService` 经 Flink REST（`/jars/upload`→`/jars/{id}/run`→按名 `l7vp-stream-{datasetId}` 幂等去重/查询/`PATCH cancel`）上传提交。

- 配置（`application.properties`，`flink.*`，全环境变量覆盖）：`FLINK_CLUSTER_URL`、`FLINK_AUTO_START`、`FLINK_JOB_*`(parallelism/batch-size/batch-ms/entry-class/jar-file-path)、`FLINK_PUSH_BASE_URL`(**集群侧须能访问本服务，生产必配**)、`FLINK_PUSH_TOKEN`、`FLINK_KAFKA_BOOTSTRAP_SERVERS`。见 `java-server/src/main/resources/application.properties` 顶部注释。
- REST（DatasetController）：`POST/GET/DELETE /api/projects/{pid}/datasets/{did}/stream/job/start|status|stop`；另有 `POST .../stream/reload`（清空并重新加载，见下）。
- **清空 / 重新加载流式数据**：`POST /api/projects/{pid}/datasets/{did}/stream/reload`（`DatasetController.reloadStreamData`）按「停当前 Flink 作业并短轮询确认停止 → `StreamSessionManager.clear()` 清内存环形缓冲并向所有在线 WS 订阅者广播空帧（已打开页面立即清屏）→ 重新提交作业」执行；先经 `FlinkJobService.isStreamingKafkaDataset` 校验，非流式/无 kafka.topic 返回 400 不清缓冲。因作业未开 checkpoint、消费组未提交 offset，重启即从 Kafka earliest 重读 topic 完整重放；**若日后作业开启 checkpoint/组提交 offset，重放需另做消费组 offset 重置**。前端入口：流式数据集卡片上的刷新按钮（`packages/li-editor/src/widgets/DatasetsPanel/DatasetList/DatasetItem`，仅 `metadata.stream`/`_stream` 数据集显示；项目 ID 取 Builder 写入的 `window.__L7VP_PROJECT_ID__`）。
- 生命周期：数据集带 `metadata.kafka.topic`（前端「流式数据」组件录入，存 DATASETS.METADATA）→ `FLINK_AUTO_START=true` 时保存项目自动提交；删除数据集/项目/disassemble 移除时自动取消（DatasetService/ProjectService/ApplicationAssembler 联动）。
- **键化模式（每船/每目标一个实时点）**：前端「流式数据」表单可选「目标标识字段」→ 存 `metadata.streamKey`（列名，如船=mmsi、飞机=flight/hex）。`FlinkJobService.startJob` 读到 `streamKey` 非空时给作业注入 `streamKey=`、`maxWindow=`（与后端缓冲上限同口径：`metadata.maxWindow` 兜底 `l7vp.stream.max-window`）。作业内 `StreamDatasetJob` 走 keyBy 聚合：`keyBy(streamKey)` → `LatestPerKeyFn`（每键最新、仅内容变化下行）→ `FleetReplaceSink`（parallelism=1，access-order LRU 全量船表 ≤maxWindow，每 `batchMs` 有变化则 `{"rows":[全量船表],"op":"replace"}` 整表回推）→ 后端 push `replace` 清缓冲整表写入 → WS → 前端 replace。语义：每船一行、停报保留最后位置、超过 `maxWindow` 挤出最久未更新键（无时间剔除）；面板"共 N 行（实时）"= 在航目标数。未配 `streamKey` 维持原 append 最近 N 条。
- 数据契约：每条 Kafka 消息 =「键=数据集列名」的 JSON 行对象；作业批量回推本服务 `/stream/push`。完整方案与改动清单见 `doc/STREAM_DATASET_INTEGRATION.md` 第 9 节。

## 历史轨迹（右键目标 → 查时序库 → 画轨迹）

在 **Builder 预览页**（`/builder/:id`，不含 Share 嵌入页）右键某个流式目标图标 → 菜单「历史轨迹」→ 弹时间范围选择框 → 从时序库取该目标的全部历史点 → 地图上画**轨迹折线 + 轨迹点**（绿起点 / 红终点，附图例）。不做回放动画、点详情弹窗、多轨迹叠加。

- **数据形态**：时序表由 Flink 作业自建，`(dataset_id text, event_time timestamptz, payload jsonb)`；**目标标识不是独立列**，而是 payload 里的一个键（键名 = 数据集 `metadata.streamKey`，船=mmsi）。查询 = `WHERE dataset_id=? AND payload->>'<streamKey>'=?`，按 `event_time` 升序。
- **后端** `service/StreamHistoryService`：校验「数据集属于该项目 + `metadata.stream==true` + `streamKey` 非空且过 `^[a-zA-Z0-9_]+$`」后，`DriverManager` 直连 TSDB（复用 `flink.tsdb.url/user/password/table`，**只要求 url 非空**，`flink.tsdb.enabled` 只管写入）。逐行 `payload` 解析成 Map，丢弃 `lng/lat` 非数值的行，每行注入 `_eventTime`（ISO-8601）。表名与键名拼接前必须过白名单。
- **`payload ->> ?` 不能用占位符**：PG 会在 `->> text` 与 `->> integer` 之间判定不出唯一算子，报 *operator is not unique*。键名须**内联为字面量**（`payload ->> 'mmsi'`），安全性由白名单保证。
- **接口**（`DatasetController`，错误体沿用 `{"error": "..."}`）：`GET /api/projects/{pid}/datasets/{did}/stream/history?key=&start=&end=&limit=`（start/end 为 **epoch 毫秒**；返回 `{key, keyField, rows, rowCount, truncated}`，多取一行判断截断）；`GET .../stream/history/range` 返回该数据集在库里的 `{minTime, maxTime}`（走 `(dataset_id, event_time)` 索引，毫秒级，用于弹窗提示）。数据集不属于项目→404，非流式/无 streamKey→400，未配 TSDB→503。
- **`java-server/pom.xml` 必须有 PG 驱动**：`flink-job` 子工程那份是交给 Flink 集群的，**不进本进程 classpath**，不加则 `DriverManager` 加载不到。
- 上限：`flink.tsdb.history-max-rows`（默认 20000，硬顶 `ABSOLUTE_MAX_ROWS=50000`）；请求 `limit` 会被钳制。
- **建议的 DDL**（实测无索引时单次查询 0.7~1.6s，瓶颈是按 payload 字段过滤）：`CREATE INDEX IF NOT EXISTS idx_stream_events_ds_key_time ON stream_events (dataset_id, (payload ->> 'mmsi'), event_time);`（键名随各数据集 `streamKey` 而定）。
- **前端** 全在 `packages/li-analysis-assets/src/widgets/RightClickMenu/`（`Component.tsx` + `HistoryTrackModal.tsx`），组件 `type: 'Auto'`，由 MapContainer 渲染在 `<LarkMap>` 内，故可直接用 larkmap 组件。
  - **菜单自绘，不用 larkmap 的 `<ContextMenu>`**：larkmap 1.5.1 的 `ContextMenu` 读 `e.lnglat`，而 L7 事件上是 `lngLat`，取值 undefined 被 BaseMapService 的 try/catch 吞掉 → 菜单永不渲染，且 `preventDefault` 又屏蔽了浏览器原生菜单，表现为「右键毫无反应」。改用自绘面板 + `<Marker lngLat={...} anchor="top-left">` 定位。
  - **scene 级与 layer 级对同一次右键都会触发**（scene 的 payload 只有 `{x,y,lngLat,type,target}`，**没有 feature**；只有 layer 级 `l7Layer.on('contextmenu', e)` 才带 `e.feature`）。用单拍 `setTimeout(0)` 合并成一次 `setMenu`，与到达顺序无关。
  - **拿到 L7 图层实例**：`useLayerList()` → larkmap `LayerManager` 里的 `CoreLayer`/`CompositeLayer`；composite 要走 `subLayers.getLayers()[].layer`，普通图层走 `.layer`，再 `on('contextmenu')`。绑定的实例存 `Map` 防重复绑定（L7 事件是累加的），并做 `setTimeout(bind, 800)` 补绑与卸载时统一 `off`。
  - **`datasetId` 不能从 L7 图层属性里取**：`useLayerProps` 会 `omit(sourceConfig, ['datasetId','parser'])`。须由 li 图层 id 反查 `useStateManager().layersStore.getLayerById(id)` → `sourceConfig.datasetId` → 数据集 `metadata.stream/streamKey`。
  - **轨迹图层不进 li 的 dataset/layer store**：Builder 自动保存是从 editor React state 重建 application 的，而 `stateManager.initState` 在 datasets/layers 数组引用变化时会整体替换 store——塞进 store 既会被抹掉、也有被存进项目的风险。故轨迹由组件内 `<LineLayer>`/`<PointLayer>` 直接渲染，清除/卸载即消失。
  - 属性面板（`registerForm.ts`）：`showRightMenu`（已有）、`showHistoryTrack`、`historyMaxRows`（透传后端 limit）、`trackColor`。
  - 项目 ID 沿用 `window.__L7VP_PROJECT_ID__`，非 Builder 页从 hash 路由兜底解析。
- **构建此包在本机有坑**：`/mnt/d`（WSL 9p）上原地 `father build` 会死锁（进程 `State: D` / `wchan: p9_client_rpc`，只写出几个 `.less` 后 CPU 归零，重试必现）。须把 src 与配置拷到 `/tmp` 沙箱构建再把产物 `cp -r` 回 `packages/<pkg>/dist` 与 `node_modules/@antv/<pkg>/dist`（**合并而非整目录替换**，保留遗留 `.d.ts`）。沙箱下 dts 仍会撞同一死锁而失败，故只能得到 esm——website 走 `module` 字段只吃 esm，不影响运行，但新文件的 `.d.ts` 会缺失。
- **未运行时验证**：`<LineLayer>` 的 `source.parser.coordinates`（轨迹折线）只在 L7 源码层确认过。若不显示，退路是改用 `website/src/pages/Share/index.tsx` 里 `highlightLines` 那种 `{x,y,x1,y1}` 分段行形态。

## 图标图层的图标类型（固定图标 / 基于字段）

样式面板在 **`packages/li-p2/src/LayerAttribute/IconImageLayerStyle/`**（`schema.tsx` 表单、`helper.ts` flat↔config 转换、`types.ts`）。**图标图层(IconLayer) 与 ClusterLayer 的 `renderer:'icon'` 共用这一套配置**，所以改这里两边同时生效。

**先注意 UI 上有两个不同的东西都叫「基于字段」**，实际是 **3 种**渲染策略：

| 场景 | 配置 | 图标怎么来 |
|---|---|---|
| ① 固定图标（不选基于字段） | `iconType='fixed'`，`iconField` 空，`iconImg`=一张图 | 全体用同一张，`iconAtlas={[iconImg]:iconImg}` |
| ② 固定图标 + 基于字段 | `iconType='fixed'`，`iconField`=某列，`iconImgScale`={domain,range} | 图层配置里**手工写的对照表**，值→图标，纯前端 `cat` scale |
| ③ 图标模式「基于字段」 | `iconType='field'`，`iconLibraryField`+`iconCodeField` | 两个字段的组合值**实时去图标库查**（见下） |

- **② 的对照表**由 `IconScaleSelector`（`LayerAttribute/components/IconScaleSelector/`）维护：一条 =「一个图标 + 一个字段取值」，已选过的值会从其它条候选里剔除。「应用」→ `getScaleByCustomMappingData` 产出 `{domain:[值], range:[图标url], unknown}`，`helper.ts` 再包成 `icon = {field, value: range, scale:{type:'cat', domain, unknown}}`。**可选字段仅限 `type==='string'` 的列**（`iconFieldList`，schema.tsx:9），与 ③ 能用任意列不同。切换字段会清空已配映射。
- **② 的 `unknown` 实际是写死的**：选「未匹配图标」那块 UI 被注释掉了（`IconScaleSelector/index.tsx:170-176`），`unknownIcon` state 永远是初值 `/icons/default-icon.svg`。而 L7 **缺 `unknown` 时会把未匹配数据渲染成蓝色默认圆点**（helper.ts:23-25 有注释强调），所以这个路径必须真实可加载——这正是 ClusterLayer「切图标后仍出现聚合圆点」的根因所在。
- **② 的图标 URL 会固化进项目配置**，之后改图标库不影响已配好的图层；③ 则是每次数据刷新都重新查。选型：图标集合固定、与业务字段一一对应 → ②；图标由业务方按编号体系在图标库里维护、会增删 → ③。

**③「库号/代号」模式的完整链路**（列名选择器，不是值）：

1. 图标库数据模型：`ICONS(ICON_ID, CATEGORY_ID, LIBRARY_CODE, CODE_NAME, URL, ...)`，唯一约束 `(CATEGORY_ID, LIBRARY_CODE, CODE_NAME)`。上传时 `/icons/upload` 会**自动编号**：`getDefaultLibraryCode` 取该分类下出现次数最多的库号（无则 `"1"`），`getDefaultCodeName` 取同库号内最大数字代号 +1（`IconService.java:240-268`）。
2. `useLayerProps`（`li-sdk/.../WrapperLayer/hooks/useLayerProps.ts:106-113`）监听 `dataset.data` 与 `iconType/iconLibraryField/iconCodeField` → `enrichIconUrls`。
3. `enrichIconUrls` 收集去重后的 `库号|代号` 组合，**每个组合并行发一次** `GET /api/icons/lookup?lib=&code=`，然后给**每行**写 `_iconUrl = 命中url || fallbackIconUrl || ''`。
4. 再用所有不同的 `_iconUrl` 构建**动态 `iconAtlas: {[url]:url}`**（useLayerProps.ts:120-132）——因为 L7 `loadIconAtlas()` 把 atlas 的 **key 注册成图片名**，而 ③ 的 `shape` 取的就是 `_iconUrl`，故 url→url 自洽。`helper.ts` 在 ③ 下把 `icon` 直接设成字符串 `'_iconUrl'`（不是 `{field}` 对象），`iconAtlas` 留空待动态填。
5. 后端 `IconService.lookupByCode` → `IconRepository.findByCode`：`WHERE LIBRARY_CODE = ? AND CODE_NAME = ?`，**精确字符串等值，且不带 CATEGORY_ID**——同名组合出现在多个分类下时取第一条（顺序未定义），是个隐患。

**关键坑 / 排查**：
- ③ 未匹配且**没配「默认图标」** → `_iconUrl` 为空串，**该点直接不渲染**（不是回退圆点）；② 未匹配 → 走 `unknown`。
- **改「默认图标」不一定立刻生效**：`enrichIconUrls` 的 effect 依赖里**没有** `iconImgFallback`，它只在 enrich 当时被写进行数据，需等数据刷新或重新进页面。
- **只对 local/remote 数据集生效**（`isLocalOrRemoteDataset`），瓦片数据集会跳过。
- `enrichIconUrls` 里留了大量 `console.log`（逐行打印库号/代号、lookup 状态与结果），排查「某点图标不对」看控制台最直接。
- 图标库的库号/代号怎么看：项目页「图标库」按钮 → 每张卡片下方直接显示 `库号 / 代号`（`IconLibraryModal/index.tsx:240-244`，两者皆空则不渲染该行）；或 `curl -s localhost:3001/api/icons | jq -r '.[]|.type as $c|.icons[]|[$c,.libraryCode,.codeName,.name]|@tsv'`；或直接 `SELECT LIBRARY_CODE, CODE_NAME, ORIGINAL_NAME, URL FROM DIG_GEO.ICONS`。

## 聚合图层 ClusterLayer（点聚合）

`packages/li-core-assets/src/layers/ClusterLayer/`（`implementLayer`，资产包内注册，非独立包）。把运行时注入的普通 source 包一层 `cluster:true` 交给 larkmap：`Component.tsx` 里 `{...source, cluster:true, clusterOptions:{radius:40, maxZoom:20}}`，L7 source 级聚合（supercluster）—— 聚合节点合成出 `{cluster:true, point_count, point_count_abbreviated}`，叶子保留原始行字段，故**数量字段只存在于聚合节点**（叶子无 `point_count_abbreviated`，仅 `point_count:1`）。缩放才裂解为单点（`maxZoom:20` → 约 21 级后才出单点）。流式 replace 由 larkmap changeData → L7 `Source.setData` 自动重聚合，无需自持 L7 source。

- **双渲染模式**：`visConfig.renderer = 'dot' | 'icon'`（默认 `dot`）。
  - `dot`：`BubbleLayer`，聚合圆按 `point_count` 映射 radius/fillColor + `point_count_abbreviated` 文本。
  - `icon`：`IconImageLayer` 整层图标（聚合节点=大图标+数量、单点=小图标），固定图标 / 基于字段两种，对齐「图标图层(IconLayer)」（**图标类型的完整说明见上一节**，两点共用 `li-p2` 的同一套样式面板）。仍由**单个 composite 承载**（li 图层 id ↔ 1 composite），LayerPopup 按 id 命中不受影响。
  - 样式面板在 `register-form/schema.ts`（`collapseItem_renderer` 单选 + 两套样式 schema，靠 `x-reactions` 按 `renderer` 显隐）；`register-form/index.ts` 的 to/from 按 `renderer` 分派 bubble/icon 两套 flat 转换。
- **悬停信息（LayerPopup）**：`packages/li-core-assets/src/widgets/LayerPopup/Component.tsx` 的 `customContent` 对 `feature.cluster===true` 只显示「目标数量」；叶子走原字段行逻辑。

**关键实现要点 / 坑**：
- `IconImageLayer.loadIconAtlas()`（`@antv/l7-composite-layers`）以 **`iconAtlas` 的 key 作为 `scene.addImage(imageName, url)` 的注册名**，主图标子图层 `shape` 直接取 `icon` 值。→ **固定图标模式下 `icon` 的值必须是某个已注册的图集 key**，否则匹配不上会回退成 L7 默认圆点。`Component.tsx` 的 `normalizeIconLayerProps` 负责收敛：固定串丢失扩展名（如存 `.../xxx` 而图集 key 是 `.../xxx.png`）时按前缀匹配到真实 URL 并以 shape 名注册；基于字段 `_iconUrl` 时给聚合节点补 `scale.unknown`（取真实可加载 URL，勿用会 404 的 `/icons/default-icon.svg`）。
- 「数量文本看不见」多为**对比度**问题：深色底图 + 深绿聚合圆上深灰 `#3b3b3b` 无描边=隐形。`index.tsx` `defaultVisConfig` 的 dot 文本已加 `stroke:'#ffffff', strokeWidth:1`；`Component.tsx` dot 路径另做运行时兜底（`label.style` 无任何描边定义时补白描边），以覆盖**历史保存的旧配置**——注意 li-sdk `useLayerProps` 把已存 `visConfig` 直传组件（**不会**与 `defaultVisConfig` 深合并），故改 `defaultVisConfig` 只对新建图层生效，旧项目须靠运行时兜底。
- **改 li-core-assets 后重建即可，不需要往 `node_modules` 拷贝**（`node_modules/@antv/li-core-assets` 是指向
  `packages/li-core-assets` 的 **symlink**，见上文「改 packages/li-* 后重建」第 3 条的更正）：
  `npm run build:package`（或 `cd packages/li-core-assets && npm run build`）→ `npm run build:website` → 拷 static → 打 jar。

**遗留问题（2026-09 未完全解决，待续）**：
1. **切图标后仍出现聚合圆点**：已按上述 `normalizeIconLayerProps` 修（当时活项目保存的固定图标 `icon` 为无 `.png` 的 URL、`iconAtlas` key 为带 `.png`），**待打包后运行时验收**是否彻底消失。
2. **默认圆点模式下聚合数量数字不显示**：按对比度加白描边修复，但**尚未确认根因就是对比度**。若打包后数字仍**完全不出现**（而非只是看不清），则问题在 dot 模式 round-trip 是否**丢掉了 label 的 `field`（`point_count_abbreviated`）**，需沿 `register-form/index.ts` 的 dot 分支 flat↔config 转换与 BubbleLayerStyle helper 再查。
3. **单点/聚合悬浮无弹窗**：**非代码缺陷**——LayerPopup widget 的 items 里须包含该 ClusterLayer 图层并勾 ≥1 字段（活项目 `79f3431e` 的 LayerPopup 未含测试用 ClusterLayer，故无 popup 绑定）。属页面配置前提，需在页面补齐后再验收。
4. 未在真实流式运行时链路（Kafka→Flink→WS）上复现验证过（WSL 下 ws 端口从 Windows 不可达，headless 复现受阻）。

## 智能体地图控制（外部智能体驱动地图）

让外部智能体（Dify / Claude / 任意程序）在 **Builder 预览页**上「看图层、查目标、控地图、建图层」。
契约与实现方案见 `doc/AGENT_MAP_CONTROL_SKILL.md`（v1.4），Dify 工具见
`doc/AGENT_MAP_CONTROL_DIFY_TOOL.yaml` + `.md`，可直接粘进 Dify 的提示词见
`doc/AGENT_MAP_CONTROL_DIFY_PROMPT.md`（**标注了每个能力依赖哪几个接口**——一个能力常常要多步调用）。

**架构：两条腿**（这是理解全部实现的前提）

- **查询腿（同步）**：N7 `GET /api/agent/projects`（项目名模糊检索→projectId，**唯一不带 projectId 的接口**）、
  N1 `GET /agent/targets`（按编号/名称查流式目标最新位置）、
  N8 `GET /agent/targets/in-region`（区域名或 bbox → 区域内目标，v1.2 新增）、
  N2 `GET /agent/regions`（区域名→bbox，`regions.json` 12 个预置区域）、
  功能 01 直接用现有 `GET /api/projects/{id}`。
- **控制腿（异步）**：地图场景对象**只活在浏览器内存**里，HTTP 请求只能由浏览器发起，方向反不过来。
  故 N3 `POST /agent/commands` 只**入队**（内存队列，绝不落库），页面组件长轮询 N4 取令 → 在**本页**执行
  → N5 回写回执；智能体经 N6 `GET /agent/commands/{cmdId}/result` 读回执确认（N3 也可带 `wait=true` 直接等）。
  指令类型见附录 A（**v1.4 起分两类**：`runtime` 7 个 = `layer.visibility` / `layer.isolate`
  / `layer.bringToFront` / `layer.sendToBack` / `map.focus` / `map.reset` / `target.select`；
  `config` 2 个 = `dataset.create` / `layer.update`）。

**v1.2 的两处行为约定**：

- **图层显隐默认保留瓦片底图**：`layer.visibility` 批量形态 `{scope:"all", visible}` 与
  `layer.isolate` 都带 `keepTiles`（默认 `true`）。语义是**「瓦片图层一律不动」**——既不强制显示、
  也不隐藏，所以「全隐藏」不会把画布变空白，也不会覆盖用户刻意关掉的瓦片图层；点名单个图层时一律照做。
  瓦片识别：图层 `type ∈ {TileLayer, RasterTileLayer, RasterLayer, MVTLayer}` 或其数据集
  `type ∈ {xyz-tile, raster-tile, mvt-tile, vector-tile}`。逐层下发是智能体最容易犯的错（底图被关掉只剩白屏），
  Dify 工具描述里已明确要求「全部图层」走批量形态。
- **区域筛选（N8）按矩形 bbox 判定、只判「目标当前位置」**：不做多边形、不看历史轨迹，
  因此对凹形/斜长区域会**多圈进一些目标**（如渤海湾口）。`region=`（先查 `regions.json` 字典）与
  `bbox=minLng,minLat,maxLng,maxLat` 互斥、二者必给其一；`windowMinutes` 圈定扫描时间窗，
  `scanLimit`（默认 20000 行）是扫描上限，`truncated=true` 表示窗口或条数上限用尽、**结果可能不全**。

**v1.3 的行为约定（图层层级）**：新增 `layer.bringToFront` / `layer.sendToBack` 两个指令类型，
**不新增接口**。只做「提到最上 / 压到最下」两个动作，不做完整 z 序（相对次序是项目配置，临时指令不该表达它）。
后端只把 `layerName` 钉成 `layerId`（`resolveLayerRef`，与 `layer.visibility` 单层形态共用），
**`zIndex` 取值由页面侧算**（层清单与瓦片层只有页面知道）：分配值必须**未被占用**且**严格大于瓦片层**
（L7 渲染列表按 zIndex 升序**稳定排序**，撞值=没改；低于瓦片=图层藏进底图）。

- **根因（非显而易见，改这里前必读）**：li 的图层分两类，**只有普通图层吃 `zIndex`**——
  普通图层（larkmap `CoreLayer`）的 `updateConfig` 会把 `options.zIndex` 通过 `setIndex` 应用下去；
  **图标/聚合图层**（`CompositeLayer`，含 li 的 `IconLayer`、`IconImageLayer`、`BubbleLayer`、ClusterLayer）
  的 `update()` **完全不处理 zIndex**，larkmap 里也没有任何一处调 `setIndex`。
  所以**编辑器图层面板拖动对图标图层无效**（这正是「两个图标图层永远一个盖住另一个」的原因），
  只能显式 `instance.setIndex(n)`——而它会把该图层全部子图层设成同一个值，故图层间 zIndex 必须唯一。
- 页面侧因此**双写**：`layersStore.updateLayer(id,{visConfig:{zIndex}})`（走官方路径存状态）
  ＋ `useLayerList()` 拿到实例后 `instance.setIndex(zIndex)`（立刻生效）。
- **未做运行时验证**：`setIndex` 对复合图层的实际叠放效果是读 L7/larkmap 源码得出的结论，待在真实页面实测
  （见 `doc/AGENT_MAP_CONTROL_SKILL.md` 附录 C 待定项 E）。

**v1.4 的行为约定（两步建图层）**：新增「智能体建图层」能力。**没有 `layer.create`** ——
li 的图层必须绑数据集，所以拆成两步：

1. `dataset.create`：智能体给 `datasetName` + `columns` + `rows`（**只支持内联数据**，
   没有传文件/连库的方式；签名是 `{datasetName, columns:[{name,type,displayName?}], rows:[{列名:值}],
   layerName?, autoCreateLayer?}`），后端**只校验后入队、绝不落库**；回执里的 `layers[]`
   是页面侧执行后回报的。校验：列名 ≤64 字 / 无控制字符 / 不重名；**行里的键必须都在 columns 里**
   （否则 400 并回全部可用列名）、值必须是**标量**（嵌套对象 400，并提示「坐标请拆成 lng/lat 两列」）。
2. `layer.update`：拿回执的 `layerId` 改属性。**受控白名单**（不放自由 JSON）：
   `name` / `description` / `visible` / `parser` / `visType` / `color` / `size` / `opacity` 八个，
   一个都不给 → 400。

**为什么第一步是「建数据集」而不是「建图层」**：复用产品自己的推断——数据集带
`metadata._autoCreateLayers === true` 时，`EditorDatasetManager.update` → `autoCreateSchemaHandler`
会自动建出图层、往 LayerPopup 补字段、切到图层面板。**不重新实现图层类型推断，也不把资产内部信息
写进契约**。推断规则（`getPointFieldPairs`，改这里前必读）：只认 `type==='number'` 的列；列名转小写；
token 必须被**串首/串尾或 `[#_&@\.\-\ ]`** 包住；配对 `lat`↔`lng`/`lon`/`long`、`latitude`↔`longitude`、
`纬度`↔`经度`、`wd`↔`jd`。**所以经纬度列必须叫 `lng`/`lat` 这类名字并声明为 `number`**
（`x`/`y` 或 `string` → 数据集建得成但**没有图层**，回执给 `warning`）。图层名 = `${dataset.name}_${pair.displayName}`，
只有前两个点对图层可见（`visible: [0,1].includes(index)`）。

- **持久化的唯一办法是写进编辑器状态**：Builder 每次 `liEditor.on('change')`（300ms 防抖）PUT 整个
  application 快照，`ApplicationAssembler.disassemble()` 按 id diff 并**反向删除**（图层无条件删、
  widget 无条件删、孤儿数据集删）——任何绕过编辑器状态的 DB 直写都会在下一次自动保存时消失。
  故 `dataset.create` **先把行数据 `POST /api/projects/{pid}/datasets/upload`**（行数据不随项目自动保存，
  `stripDataRowsFromApplication` 会清掉 `data`），再 `updateState` 写编辑器状态，让新建的数据集/图层
  随快照落库。上传失败要**抛错**，不能静默。
- **数据行的列定义**：`ColumnDef = {name, type, index, comment}` —— SDK 的 `displayName` 映射到 **`comment`**。
- **`layer.update` 的两条硬约定**：① `visType` 与 `color`/`size`/`opacity` **互斥**，同一条给会直接抛错
  （样式字段名随资产而变，要求分两条发）；② 样式字段名按资产映射（Bubble/MVT = `fillColor`/`radius`/`opacity`；
  Line/Arc = `color`/`style.sourceColor`；Icon = `radius`/`iconStyle.opacity`，**没有 color**；
  改不了就报错，不静默忽略）。换 `visType` 走 `appService.getImplementLayerDefaultVis(name)`
  **整包替换 `visConfig`**（与产品自己的类型切换 `LayerForm/index.tsx` 同语义）。
  `parser`（`{x,y}` 或 `{geometry}` 二选一）与 `description`/`visible` 后端会先校验：
  `parser` 的列名必须在该图层绑定的数据集里真实存在，查不到当场 400 并回全部可用列名。
- **`dataset.create` 的硬顶**（载荷全量走内存队列并往返，必须有上限）：行 ≤5000、列 ≤100、
  单元格字符串 ≤512 字；列名 ≤64 字、数据集/图层名 ≤100 字、备注 ≤200 字（与编辑器表单一致）。
  列 `type` 只认 `string/number/boolean/geo/date/h3` 六种，但带一层**别名宽容映射**
  （`int`/`float`/`varchar`/`日期`/`数值`… → 归一到上面六种），认不出的一律按 `string` 不报错
  ——列类型只影响样式候选，不影响能不能画。
- **指令分组与两个执行器**（这是 v1.4 的结构性改动）：指令带 `capability`（`runtime`|`config`）。
  页面执行器分两处：`AgentBridge`（包内，吃 `runtime`，任何地图页都挂）与
  `AgentConfigBridge`（website 侧 `website/src/pages/Builder/widgets/AgentConfigBridge/`，吃 `config`，
  只在 Builder 页挂）。N4 取令带 `capabilities` 过滤，**被过滤掉的指令留在队列里**给另一个执行器；
  两边各用各的 `since` 游标也安全（`PollResult` 返回的是 `ch.seq` 全局最新序号，不是最后命中的那条）。
  `AgentConfigBridge` 必须挂进 Builder 的 widget `container` 槽位才渲染——`resolveContainerSlotMap`
  会**跳过没有 `container` 的 widget**（不挂载 = 不轮询 = 指令永远 queued）；返回 `null` 则不渲染任何东西。
- **开关旗标走 window 协议，两侧各读各的**：`__L7VP_AGENT_ENABLED__`（写入侧在包内
  `widgets/AgentBridge/agentFlag.ts`，读取侧是 website 自己的 `AgentConfigBridge/agentFlag.ts`）。
  **有意不从包根导出**——`node_modules/@antv/li-analysis-assets` 是独立拷贝，跨包 import 一个
  「包没重建就没生成」的具名导出会拿到 `undefined` 而不报错，读侧直接白屏；window 全局同
  `__L7VP_PROJECT_ID__` 的先例。**复用同一个开关**：用户打开「接入智能体」即同时授权配置写入。
- **后端只做「入队 + 校验 + 把 `layerName` 钉成 `layerId`」**，绝不执行任何写库动作；也不存在
  「智能体删图层/删数据集」的接口。契约改动同样走 SKILL 文档附录 D 的同步清单（见本节末）。

**页面执行器（两个）**：① `packages/li-analysis-assets/src/widgets/AgentBridge/`（组件名「智能体桥」，
`type:'Auto'` → 渲染在 `<LarkMap>` 内，故用 larkmap 组件自绘高亮/详情浮层）吃 `runtime`；
② `website/src/pages/Builder/widgets/AgentConfigBridge/`（Builder 页专属）吃 `config`。
**注意 ② 平时不显示任何东西**（2026-09 改）：原来授权开着就在导航栏底部常驻一行
「智能体配置桥：等待指令…」的 Tag，是纯噪音，现已改成 **只有 `phase === 'error'` 才渲染**
（拿不到项目 ID、取令失败），正常时返回 `null`——**组件仍挂载、轮询照旧**，只是不出字。
**必须有显式开关**：页面默认不接受任何外部控制，用户手动打开开关后才开始轮询
——开关未开时指令一直积压为 `queued`。

**三条硬不变式**（改这个功能前必读 `doc/AGENT_MAP_CONTROL_SKILL.md` §6）：
① 不写 li 的 dataset/layer store（官方 `setLayerVisibility` 路径除外，Builder 自动保存 + `initState` 会整体替换 store）；
② 必须显式开关；③ 高亮/浮层一律自绘、卸载即消失。
**注意区分两个 store**（不变式 ① 的「store」专指前者）：「运行时 store」= `@antv/li-sdk` 的
datasetStore/layerStore，会被 `initState` 整体替换；「编辑器状态」= li-editor 的 editorState，
**是唯一能落库的路径**。`AgentConfigBridge` 写的是**后者**，所以不违反 ①。

**为什么没有 `layer.create`**（v1.2 曾计划、v1.4 仍**不做**）：运行时新建的图层只活在浏览器内存，
编辑器下一次自动保存会把它整体抹掉（即不变式 ①），做出来是个「点了没反应」的假功能。
v1.4 用「先建数据集让产品自动推出图层，再改这个图层」（见上）绕开了这条，**而不是**去破不变式 ①。
**删除图层/数据集仍然只能去编辑器**（有意不提供接口）。
**「创建图层」在 Dify 里不是一个独立工具，也没有独立后端接口**——后端**没有** LayerController，
它只是 `send_map_command` 上的两个 `type`（`dataset.create` / `layer.update`）。所以查「建图层的接口在哪」时
不要去找 `/agent/layers` 之类的路径：Dify 导入后 8 个工具里看 `send_map_command`，
其 `description` 里的**「配置指令」表**就是建图层那两步（2026-09-20 补的——此前那张表只列了 7 个运行时
`type`，日志里搜「创建图层」在 YAML 中 0 命中，会被误判成「接口没实现」）。

**注意**：`N5`（回执写入）是**页面专用**，排查问题时不要把它暴露给智能体（否则智能体能伪造回执）；
Dify 规范里**有意只收 N6**。接口契约改动要走附录 D 的「五处同步，只加不删」流程
（= SKILL 文档自身四处 + Dify 三份 YAML/TOOL.md/PROMPT.md，逐字见附录 D），
YAML 改完**要重新导入 Dify**；本文件这一节也应同步。

## 离线部署（offline-deploy/）

`offline-deploy/` = **三件套单机离线部署**（l7vp + Flink + 时序库）+ 把
`java-server/target/l7vp-server-1.0.0.jar` 打成**自包含镜像**并导出离线包
（**Java 运行时在镜像里**，目标机只要有 docker）。仓库里老 `deploy/`（手工 `docker build ./backend` 那套）
**2026-09 已删除**，`DEPLOY.md` 顶部也已加废弃横幅，只作历史参考。

**2026-09-20 整合**（三份单服务 compose → 一份编排）：

- 编排：`docker-compose.yml` 一份，三个 service **`timescaledb` / `flink` / `l7vp`**；
  容器名带 `-l7vp` 后缀 **`timescaledb-l7vp` / `flink-l7vp` / `l7vp-server`**（`docker exec`/`logs` 用）。
  ⚠️ **容器间互访必须用 service 名**（`flink:8081` / `l7vp:3001` / `timescaledb:5432`），
  DNS 只保证 service 名可解析，容器名不一定 —— 这三处已写进 `config/application.properties`，别改回 localhost。
- 变量：`.env`（原 `.env` + `sjk.env` 合并）只管**替换 compose 的 `${...}`**，不管容器环境变量；
  末尾的 `NEO4J_*`/`KAG_*` 是本栈没用到的遗留，保留备查。**旧三份 yaml 与 `sjk.env`/`load-image.sh`
  已移到 `archive/offline-deploy-merged/`**（`load-image.sh` 还本身是坏的：对 hash-only 的 `.sha256`
  用 `sha256sum -c` 必失败，`build-image.sh` 已改成写标准两段式）。
- 宿主机端口（`.env` 可改）：**3003→3001**（HTTP，3001 被 dataease 占用）、**3002**（WS）、
  **8081**（Flink UI/REST）、6123（REST RPC）、5432（时序库）。容器内端口不变（3001/3002/8081/5432）。
- **时序库数据卷钉死为旧名 `kag-njupt_timescale_data`**（`volumes.timescale_data.name`），
  衔接 `kag` 那套的既有数据、不丢 `stream_events`；`docker compose down` 不删卷，`-v` 才删。
  `init-postgis.sh` 只在**空卷**首次初始化时跑（PostGIS 必装成功、TimescaleDB 装不上只提示）。
- 时序库口令**两处必须一致**（`.env` 的 `TSDB_*` 管容器、`application.properties` 的 `flink.tsdb.*` 管应用，
  互不可见）；且 `POSTGRES_*` 只在空卷时生效，改 `.env` 对老库无效。
- 一键部署脚本 **`deploy-load-l7vp.sh`**：环境检查 → **端口冲突检查** → 导三个镜像（`.sha256` 校验、本地已有则跳过）
  → 备挂载目录 + 打印必改配置项 → `docker compose up -d` → 等三个容器 healthy → 打印地址与常用命令；
  另有 `--no-load` / `--force-load` / `--skip-port-check` / `--status` / `--logs` / `--down`，超时用 `L7VP_WAIT_SECONDS` 调。
- **端口冲突检查（起栈前，冲突即中止且不改任何东西）**：查 `.env` 里 5 个宿主机端口
  （`L7VP_HTTP_PORT`/`L7VP_WS_PORT`/`FLINK_UI_PORT`/`FLINK_RPC_PORT`/`TSDB_PORT`）有没有被占。
  端口来源**取并集**：`ss -ltn` + `netstat -ltn` + `/proc/net/tcp{,6}`(state=`0A`) + **`docker ps` 里别的容器已发布的端口**
  （Docker 关 userland-proxy 时容器端口在系统命令里不可见）。只判 TCP（compose 的 `"HOST:CONTAINER"` 默认只发 TCP）。
  两类不算冲突：**本系统自己的三个容器**占着（重跑部署要能过）、以及端口值本身的问题单独报
  （非数字 / `0` / `>65535` / **两个变量撞成同一端口**）。
  **坑：`set -o pipefail` 下 `外部命令 | grep -q` 会静默失效**——`grep -q` 命中即退出并关管道，
  上游被 SIGPIPE（rc=141）打死，pipefail 把 141 当整个管道结果，于是「端口明明在监听却判成空闲」。
  必须 `取全量 | grep -x ... >/dev/null`（不带 `-q`）。同理「是不是纯数字」用 `${port//[0-9]/}` 判，不拉 grep。
  非 root 时 `ss -ltnp` 看不到别人的进程名，此时提示用 `sudo ss -ltnp | grep :端口` 自查。
  WSL 里跑看不到 Windows 侧的监听（脚本会提示结果可能不完整），误报可加 `--skip-port-check`。
- 入口：`./build-image.sh` → 打 l7vp 镜像 + `docker save` 到 `dist/*.tar.gz`（附 `.sha256`，两段式）；
  目标机把整个 `offline-deploy/` 拷过去（`dist/` 备齐三个包）→ `./deploy-load-l7vp.sh`。
  **打镜像与启动都由用户执行**。
- **jar 来源按顺序自动找**：`java-server/target/l7vp-server-1.0.0.jar` → 找不到则用
  `offline-deploy/app/l7vp-server-1.0.0.jar`（上次构建留下的副本，目录被拷到无源码机器时靠它）；
  `--jar` 可指定任意路径。脚本顺带把仓库最新 `schema.sql` 一并收进镜像（`/opt/l7vp/schema.sql`）。
- **构建过程不联网**（Dockerfile 内无任何 apk/网络操作，`--pull=false`），只要基础镜像在本地即可全离线；
  默认基础镜像 `eclipse-temurin:21-jre-alpine`（本机已有）。`--base/--platform` 可换 Java 版本与架构。
- 端口：容器内 **3001 = HTTP**（`SERVER_PORT` 覆盖 jar 内的 3003）、**3002 = WS**（`L7VP_WS_PORT`）；
  compose 两个端口都做了映射（宿主机侧 3003/3002）。前端 `publicPath=/l7vp/`，
  直连必须带前缀：`http://IP:3003/l7vp/#/project`。
- **`config/` 与 `web/` 是两个目录、不能合并**：`config/application.properties` 是 Spring Boot 外部配置目录
  （**不参与静态资源**），`config/config.js` 挂到 `/opt/l7vp/web/config.js` 并靠
  `spring.web.resources.static-locations=file:/opt/l7vp/web/,…` 对外提供。老 `deploy/backend/application.properties`
  把 `file:/opt/l7vp/config/` 当静态目录用，会让 `curl http://IP:3001/application.properties` **下载到达梦口令**，别照抄。
- 配置优先级：compose 的 `environment` > 挂载的 `application.properties` > jar 内置
  （Spring Boot 里 OS 环境变量优先于外部配置文件），所以端口/中台地址改 compose 即可。
- 建表：后端不自动建表（`spring.sql.init.mode=never`），需手动执行一次 `schema.sql`。
  镜像里带了一份（脚本构建时从 `java-server/src/main/resources/schema.sql` 复制）：
  `docker run --rm --entrypoint cat <镜像> /opt/l7vp/schema.sql > schema.sql`；
  jar 内 `BOOT-INF/classes/schema.sql` 也有一份但是**打 jar 当时的快照**（改了 `schema.sql` 想让 jar 内那份也更新，
  要重新 `mvn package`；镜像里那份独立的不受影响），优先用前者。
  **该脚本是幂等的**（模式/表/索引全 `IF NOT EXISTS`；`TILE_CONFIG` 默认瓦片是
  `INSERT … SELECT … FROM DUAL WHERE NOT EXISTS(…)`，只在缺行时插入，不覆盖页面里改过的底图——
  原来是 `MERGE … WHEN MATCHED THEN UPDATE`，重跑会把底图冲回高德，已改掉；末尾附只读核对查询）
  ——重复执行无任何改动。

### 配置放哪儿（env / properties / config.js 的分工）

**判断依据只有一个：这份配置的「读者」是谁**——容器里的 Java 进程可以吃环境变量，浏览器不行。

| 配置内容 | 放哪里 | 为什么 |
|---|---|---|
| 达梦地址/账号/口令、`l7vp.auth.mode`、Flink/TSDB 地址 | `config/application.properties` **或** compose `environment:`（随你） | 读者是 Java 进程，两条路 Spring 都支持 |
| 中台地址 / SSO / 端口 / JVM 内存 | `docker-compose.yml` 的 `environment:` | jar 内本就写成 `${ENV:默认}`，天生环境变量优先 |
| 底图 | **页面「底图配置」改一次存库**（`TILE_CONFIG` 表）；`config.js` 只是兜底 | 见下：前端读 DB 优先 |
| `wsPort` / `streamMaxWindow` 等前端运行时项 | `config/config.js`（保持挂载） | **读者是浏览器**，env 顶不上 |

- **`config.js` 不能 env 化**：它由 `index.html` 的 `<script src="/config.js">`（`website/config/config.ts:59` 的
  `headScripts`）在**浏览器**里加载、写 `window.L7VP_CONFIG`（`hooks/useStreamDatasets.ts`、
  `pages/Project/herlper.ts` 读它）。容器环境变量只活在服务端进程，浏览器看不到，**放 `.env` 不会有任何效果**。
  真要 env 化只有两条路：① entrypoint 用 `sed` 把占位模板渲染成 `/opt/l7vp/web/config.js`
  （改 Dockerfile/entrypoint，不改 Java 代码；**alpine+busybox 没有 `envsubst`**，装 gettext 会破坏离线，
  且底图 URL 含 `{}`/`&` 转义易错）；② 后端加 `GET /api/config` 下发（**改 Java 代码**，还要处理配置到手前不画图的时序）。
  两者代价都远大于收益，**维持挂载文件**。
- **底图有更省事的路**：项目页读配置的优先级是 **数据库 `TILE_CONFIG` → `window.L7VP_CONFIG` → 硬编码兜底**
  （`pages/Project/herlper.ts` 顶部注释），所以能在页面里改一次存库，就不必碰 `config.js`。
- **`.env` 默认不进容器**：compose 的 `.env` 只用于替换 `docker-compose.yml` 里的 `${...}`。
  要变成容器环境变量必须在 `environment:` 里引用（`SPRING_DATASOURCE_URL: ${DM_URL}`），或整份 `env_file: .env`。
- **带 `-` 的属性名别硬拼 env**（`spring.datasource.hikari.connection-init-sql`、
  `spring.web.resources.static-locations`）：环境变量形式要按「点→下划线、去连字符」转，**拼错不报错、静默用默认值**。
  前者**不用设**（`SET SCHEMA DIG_GEO` 已在 jar 内置配置 `application.properties:14`，覆盖 URL/账号/口令不会顶掉它）；
  后者 jar 内置**没有**（外置那份是为「挂载 config.js 生效」加的），要用就走**命令行参数**零歧义：
  `command: ["--spring.web.resources.static-locations=file:/opt/l7vp/web/,classpath:/META-INF/resources/,classpath:/static/"]`
  （entrypoint 末尾的 `"$@"` 会原样追加到 `java -jar app.jar` 之后）。
  *以上 env 名解析未在真实容器里实测过，所以带连字符的一律建议避开。*
- **env 不比文件安全**：`docker inspect` / `docker compose config` 都能明文看到环境变量，容器内所有进程也都读得到；
  挂载的 properties 同样是明文。别以为放 env 更安全——真正敏感的只有达梦口令，怎么放取决于运维习惯。

## 数据源连接 / 地图刷新 / 图标转向（2026-09 新增）

这三块（MySQL/PostgreSQL/Redis 数据源、地图按间隔刷新、图标按字段转向）的**完整说明见
`doc/DATASOURCE_AND_REFRESH.md`** —— 细节较多，为不挤占本文件（已顶到工作区指令注入的字节上限）而单独成文。
改动前请先读那份文档，几个最容易踩的点先记在这里：

- **数据源类型元信息只有后端一处真值**：`GET /api/db-connections/supported-types`
  （类型值/默认端口/字段叫法/**操作提示文案**），前端只渲染。加类型改 `DbConnectionService` 一处。
  规范类型名 5 个：`Dameng` / `Doris` / `MySQL` / `PostgreSQL` / `Redis`。
- **Redis 不是 JDBC，是 `RedisSourceSupport` 里的平行实现**：「表列表」= 键族（`aircraft:*`）、
  「表名」= 键 glob 模式、「一行」= 一个键或成员；必须 SCAN 不能用 KEYS。
  值还原 `coerceScalar` 错了会让经纬度列被判成 string（**图上直接不出点**）。
- **PostgreSQL 的 `schemaName` 字段装的是数据库名**（连接串必须带库名）；catalog/schema 是两层，别混。
  MySQL/Doris 必须用 catalog 传库名、schemaPattern 传 null。
- **地图刷新的唯一真值是 `metadata.refreshInterval`（秒）**，li-editor 与 **li-sdk 两边都要认**——
  后者（`useRemoteDataset.ts`）2026-09 才补上，缺了它「配好的地图」在预览页/嵌入页不会刷新。
- **图标转向只存字段名** `visConfig.iconRotationField`，角度归一化在
  `li-core-assets/src/layers/icon-rotation.ts`（360/非数值一律回落到 0，因为上游用它当哨兵值）。

## 文档

- `offline-deploy/README.md` — 离线镜像打包与内网部署（单 jar → 单容器）
- `doc/DE_L7_INTEGRATION.md`、`doc/L7_DE_LINKAGE_PLAN.md` — DE-L7 联动实现与方案
- `doc/STREAM_DATASET_INTEGRATION.md` — 流式数据集 WebSocket 接入方案与改动清单
- `doc/AGENT_MAP_CONTROL_SKILL.md` — 智能体地图控制接口契约与实现方案（v1.4）
- `doc/AGENT_MAP_CONTROL_DIFY_TOOL.yaml`、`doc/AGENT_MAP_CONTROL_DIFY_TOOL.md` — Dify 自定义工具规范与接入说明
- `doc/AGENT_MAP_CONTROL_DIFY_PROMPT.md` — 可直接粘进 Dify 的智能体提示词（v1.4，标注「哪个能力依赖哪几个接口」）
- `java-server/README.md` — 后端部署与 API 说明
- `DEPLOY.md` — **已废弃**（老 Docker 双容器方案，对应已删除的 `deploy/`），只作历史参考
- `DEVELOPMENT.md` — 各资产包开发说明
