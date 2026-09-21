# 智能体地图控制 —— 接口文档与实现方案

> 让外部智能体（Claude / MCP 工具链 / 任意程序）驱动 L7VP 地图，完成九类操作。
>
> 生效页面：**运行时控制（02~05、08）在 Builder 预览页 `/builder/:id`**；
> **配置写入（09 建图层）只在 Builder 页**（§2「两类执行器」）。
> 目标数据源：**时序库 TimescaleDB**。
> **接口状态：v1.4**（v1.0 于 2026-09-10 冻结；2026-09-11 追加 N6 与 N3 的 `wait`；
> 2026-09-17 追加 N7 / N8 与批量显隐、`layer.isolate`；2026-09-17 追加图层层级
> `layer.bringToFront` / `layer.sendToBack`；2026-09-17 追加 **`dataset.create` / `layer.update`（两步建图层）**
> 与执行器能力分流 `capabilities`，均为**兼容新增**）。
> 契约见 §2 / §4，冻结决策清单见附录 C。
>
> **已实现**（2026-09-17）：后端 N1~N8（`AgentController` / `AgentCommandService` / `RegionService` /
> `StreamHistoryService`）+ 两个页面执行器：运行时「智能体桥」
> （`li-analysis-assets/src/widgets/AgentBridge/`）与编辑器侧「智能体配置桥」
> （`website/src/pages/Builder/widgets/AgentConfigBridge/`，v1.4）。
> **仍未实现**：`layer.create`（运行时直接建图层）与 `layer.clear`——运行时建的图层进不了自动保存，
> 故 v1.4 改为**两步建图层**（`dataset.create` 让产品自己推断出图层 → `layer.update` 再改），
> 绕开了持久化问题（附录 C 决策 27、35~40）。
> Dify 自定义工具规范见 [`AGENT_MAP_CONTROL_DIFY_TOOL.yaml`](./AGENT_MAP_CONTROL_DIFY_TOOL.yaml)
> 与[接入说明](./AGENT_MAP_CONTROL_DIFY_TOOL.md)。

## 阅读指引

本文有两类读者，按需跳转：

| 你是 | 看哪几节 |
|---|---|
| **调用方**（要写智能体、写集成代码） | §2 总览 → §3 现有接口 → §5 五个功能的调用流程 → §4「v1.4 的指令形态」（建图层）→ 附录 A/B |
| **实现方**（要往本项目加这些 API） | §1 前提 → §4 接口规格 → §6 页面执行器 → §7 区域字典 → §8 顺序 |

**当前状态**：九个功能里，**功能 01 今天就能调**（用现有接口，零改动）；**02~09 已实现**（见 §6）。每节都标了「✅ 现有 / 🆕 需实现」。
**02~05、08 是运行时改动（刷新即恢复），09 会真的写入项目配置**——这是两类指令的本质区别（§2「两类执行器」）。

**关于两处「实现期再定」的事**：它们**都不影响接口契约**，所以接口可以先冻结。

| 待定项 | 影响范围 | 为什么不影响契约 |
|---|---|---|
| SSO 鉴权凭证形态 | 调用方在 HTTP 头里怎么带凭证 | 路径、参数、返回体一个都不变 |
| 模糊搜索走查库还是内存快照 | N1 **服务端内部**查哪张数据源 | 请求/响应形状完全一致，对调用方透明 |

---

## 一、前提：为什么控制类必须有「命令通道」

这一节是理解全部接口设计的基础，先说清楚。

### 1.1 五个功能分两类

| 功能 | 本质 | 后端能否用普通接口实现 |
|---|---|---|
| 01 查看图层列表 | **读数据** | ✅ 能 |
| 02 控制图层显隐 | **改浏览器里的渲染状态** | ❌ 不能 |
| 03 按经纬度聚焦缩放 | **操纵浏览器里的地图对象** | ❌ 不能 |
| 04 查目标位置 | **读数据** | ✅ 能 |
| 04 聚焦目标 + 展示面板 | **操纵地图 + 页面 UI** | ❌ 不能 |
| 05 语义区域聚焦 | 语义解析（数据） + **操纵地图** | 半 |

### 1.2 为什么「不能」

**地图活在浏览器标签页里，后端够不着它。**

缩放的实现是一行前端代码：`scene.setZoomAndCenter(12, [lng, lat])`。这个 `scene` 对象只存在于浏览器内存中，服务端没有任何对象代表「当前视野」。

你可以写一个 `POST /api/map/zoom {zoom:12}` 把数字存进数据库——但**地图不会动**。那只是数据库里的一行记录，浏览器里已经画出来的地图不会自己知道。除非**浏览器主动来问**。

同理，`/api/projects/{id}` 里确实存着 `visConfig.visible`，写个接口把它改成 `false` 也能落库——但同样，**已经打开的页面不会变**（页面不会重新拉取项目），而且这个改动会**持久污染用户的项目**。

### 1.3 一条硬证据

前端所有 service 模块里，**没有任何图层/显隐/缩放的接口调用**：

```
website/src/services/  →  asset.ts  auth.ts  case.ts  icon.ts  project.ts  tile-config.ts
grep "visible|zoom|setZoom|layer"  →  （空）
```

这些功能前端**不发任何请求**，纯在内存里完成。

### 1.4 结论：两条腿

```
┌──────────────────┐   HTTP    ┌────────────────────────────┐
│  外部智能体      │──────────▶│  java-server               │
│  (Claude / MCP)  │◀──────────│  /api/projects/{pid}/agent │
└──────────────────┘  数据     └───────────┬────────────────┘
       ▲                                   │
       │ 查询腿：直接返回数据              │ 控制腿：入队一条指令
       │ （§3、§4 的 GET）                 │ （§4 的 commands）
       │                                   │
       │                     GET .../commands?since=<seq>  长轮询
       │                                   │
       │                   ┌───────────────▼────────────────┐
       │                   │ Builder 页面「智能体桥」        │
       │                   │  - layersStore.setLayerVisibility│
       │                   │  - scene.setZoomAndCenter       │
       │                   │  - 自绘目标详情面板             │
       │                   └────────────────────────────────┘
```

- **查询腿**：同步返回数据。智能体直接拿到结果。
- **控制腿**：后端只**入队一条指令**，页面主动拉取并执行。这是「操控一个正在运行的页面」唯一的接口形态——HTTP 请求天生由浏览器发起，方向反不过来。

> 项目现有 WebSocket（`/ws/datasets/{id}`）只用于推送流数据，`handleTextMessage` 是空实现，**没有反向通道**，因此控制腿采用长轮询（理由见 §4 N4）。

### 1.5 v1.4 的例外：为什么「写项目配置」也必须走控制腿

09 建图层是**要落库**的，看起来完全可以做成普通接口（后端直接把数据集和图层写进达梦）。
**但它不能**——原因是本项目的一个保存语义：

> Builder 每次 `liEditor.on('change')`（300ms 防抖）都会 **PUT 一份完整的 application 快照**，
> 后端 `ApplicationAssembler.disassemble()` 按 id **反向删除**：图层无条件删、数据集变孤儿就删。

所以任何绕过编辑器状态的写库结果，都会在**用户下一次动一下编辑器**时被抹掉。
改项目配置的**唯一**通路是写进**编辑器状态**，让它随快照一起提交——而编辑器状态只活在
Builder 页的浏览器内存里，后端够不着。于是它和控制类指令一样，只能走命令通道（§2「两类执行器」）。

这也解释了 09 为什么是**两步**：`dataset.create` 把数据交给产品，**让产品自己推断出图层**
（`_autoCreateLayers` → 自动图层），而不是让智能体描述「图层长什么样」。
图层资产的内部结构因此不必进入契约，智能体只要描述「数据是什么」。

---

## 二、接口总览

统一前缀 `/api/projects/{projectId}/agent`（功能 01 与 N7 除外，见下）。

| # | 方法 | 路径 | 作用 | 状态 |
|---|---|---|---|---|
| — | GET | `/api/projects/{id}` | **图层清单 + 数据集清单**（功能 01 全靠它） | ✅ 现有 |
| N7 | GET | `/api/agent/projects` | **查项目 ID**：项目名 → `projectId`（其余接口的入口） | 🆕 v1.2 追加 |
| N1 | GET | `/agent/targets` | 查目标位置：精确（编号）/ 模糊（名称） | ✅ v1.0 |
| N8 | GET | `/agent/targets/in-region` | **区域筛选目标**：某矩形内的当前目标（区域名或 bbox） | 🆕 v1.2 追加 |
| N2 | GET | `/agent/regions` | 区域名 → bbox / 中心 / 缩放级 | ✅ v1.0 |
| N3 | POST | `/agent/commands` | **下发**一条地图指令（可选 `wait=true` 直接等回执，v1.1） | ✅ v1.0 / v1.1 |
| N4 | GET | `/agent/commands` | 页面**取令**（长轮询，`since` 游标；v1.4 起可带 `capabilities` 只取自己那类） | ✅ v1.0 / v1.4 |
| N5 | POST | `/agent/commands/{cmdId}/result` | 页面**回执**执行结果（**智能体不应调用**，见下） | ✅ v1.0 |
| N6 | GET | `/agent/commands/{cmdId}/result` | **读回执**（确认指令真的被执行） | ✅ v1.1 追加 |

**N7 为什么不带 `projectId`**：它正是智能体拿来问 `projectId` 的入口——要有 `projectId` 才能挂在
`/api/projects/{projectId}/agent` 下，所以它只能挂在 `/api/agent`。这是<b>唯一</b>不以 `projectId` 为路径参数的接口。

**N5 与 N6 是同路径的写 / 读两端**：N5 是**浏览器页面专用**的上报口，智能体不应用它——
暴露给智能体等于让智能体自己写回执再自己读，回执就失去意义了。智能体要读回执走 N6。
（因此 Dify 工具规范里**有意只收 N6**。）

**各功能依赖哪些接口**：

| 功能 | 依赖 | 状态 |
|---|---|---|
| 01 查看图层列表 | 现有 `GET /api/projects/{id}` | ✅ 能 |
| 02 控制图层显隐 | N3 + N4（+N5 回执页面侧 / N6 读取）；批量形态默认保留瓦片（v1.2） | ✅ 已实现 |
| 03 经纬度聚焦缩放 | N3 + N4 | ✅ 已实现 |
| 04 查目标位置 → 聚焦 → 面板 | N1 + N3 + N4 | ✅ 已实现 |
| 05 语义区域聚焦 | N2 + N3 + N4 | ✅ 已实现 |
| 06 项目名 → 项目 ID | N7 | 🆕 v1.2 已实现 |
| 07 区域筛选目标 | N8（+N2 可选，用来把中文区域名换成 bbox） | 🆕 v1.2 已实现 |
| 08 调整图层层级（提到最上 / 压到最下） | N3 + N4（+N5 回执页面侧 / N6 读取）| 🆕 v1.3 已实现 |
| 09 创建图层（建数据集 → 改图层，两步） | N3 + N4（**Builder 页**的配置执行器） | 🆕 v1.4 已实现 |

**注意 N3/N4 是共用底座**：命令通道一通，02 和 03 立刻可用；04、05 再各自加查询接口。
**08 也只是 N3 上多两个 `type`**——不新增接口，zIndex 由页面侧算（见 §4 末尾「v1.3 的指令形态」）。
**09 同样是 N3 上多两个 `type`**（`dataset.create` / `layer.update`），但它落在**另一个执行器**上，
见下面「v1.4：两类执行器」与 §4 末尾「v1.4 的指令形态」。

**调用方必需的前置条件**：目标页面必须渲染了「智能体桥」组件（`AgentBridge`），
且用户**手动打开**了组件面板上的「接入智能体」开关——这是 §6 约束 2，页面默认不接受任何外部控制。
开关未打开时，指令会一直积压在队列里（`status` 停在 `queued`）。

### v1.4：两类执行器（理解 09 的前提）

指令按「会不会改动项目配置」分两类，**由不同的页面组件执行**：

| 类别 | 指令 | 执行器 | 在哪 |
|---|---|---|---|
| `runtime` | `layer.visibility` / `layer.isolate` / `layer.bringToFront` / `layer.sendToBack` / `map.focus` / `map.reset` / `target.select` | 地图侧「智能体桥」（`AgentBridge`） | 有 `AgentBridge` 的任何页面（含 Share 嵌入页） |
| `config` | `dataset.create` / `layer.update` | 编辑器侧「智能体配置桥」（`AgentConfigBridge`） | **仅 Builder 页** |

为什么必须分开：`runtime` 指令只改运行时视图（刷新即恢复），而 `config` 指令要**写进项目**——
只有 Builder 页能把改动落库（走编辑器状态 + 自动保存，见 §6.2），Share/预览页根本没有这条通路。
若把两类混在一个轮询里，Share 页会抢走 `config` 指令然后执行失败。

因此 **N4 取令可带 `capabilities`**（v1.4 新增，可选）：只取自己那类指令。
**不带该参数 = 两类都取**（与 v1.0 行为一致，向后兼容）；被过滤掉的指令**留在队列里**等另一个执行器，
不会丢。

**授权是同一个开关**：`config` 执行器不另设开关，复用地图侧「接入智能体」开关——
用户在地图上打开开关 = 授权本页智能体，**此时在 Builder 页同时也授权了「建数据集/改图层」**。
两者的开关状态通过 `window.__L7VP_AGENT_ENABLED__`（＋ `l7vp-agent-enabled` 事件）互通。
若目标项目是 Builder 页且要下发 `config` 指令，务必提醒用户：**这会在他的项目里真的写入东西**。

---

## 三、现有接口：功能 01 现在就可用

### 3.1 `GET /api/projects/{id}`

无需任何改动，直接可调（浏览器需带登录态 Cookie；`l7vp.auth.mode=local` 下免登录）。

```bash
curl -s 'http://localhost:3001/api/projects/79f3431e' | jq '{layers: .spec.layers, datasets: [.datasets[] | {id, name, type, metadata}]}'
```

### 3.2 返回结构（**注意：与前端 `LayerSchema` 形状不同**）

`ApplicationAssembler.assemble()` 输出的顶层是 `{metadata, datasets, spec:{map, layers, widgets}}`。其中 `spec.layers[i]` 的真实字段是：

```json
{
  "id": "layer_abc",
  "name": "船舶实时",              // ← 图层显示名（智能体做名称匹配用这个）
  "type": "BubbleLayer",
  "dataset": "ds_ship",            // ← 数据集 id 在【顶层 dataset】，不是 sourceConfig.datasetId
  "order": 0,
  "sourceConfig": { "datasetId": "ds_ship", "parser": { ... } },   // 两者并存，值相同
  "visConfig": { "visible": true, "...": "..." },                 // ← 显隐在这里
  "其他非标准字段": "..."
}
```

**两个坑**（由 `ApplicationAssembler.java:768-775` 的保存逻辑决定）：

1. **数据集引用有两个位置**：顶层 `dataset` 和嵌套 `sourceConfig.datasetId`。优先取**顶层 `dataset`**；两者可能并存（历史数据只写了其中一个）。
2. **除 `{id, name, type, dataset, order, createTime}` 之外的所有字段都被合并进 `vis_config` 存储，读取时又拍平回顶层**。所以 `visConfig` 是**嵌套对象**（`layer.visConfig.visible`），`sourceConfig` 也原样嵌套回来。

`datasets[i]` 里带着流式数据集的关键配置，**功能 04 需要的信息这里就有**：

```json
{
  "id": "ds_ship",
  "name": "船舶实时数据",
  "type": "local",
  "metadata": { "stream": true, "streamKey": "mmsi", "kafka": { "topic": "...", "bootstrapServers": "..." } },
  "columns": [ { "name": "mmsi", "type": "number" }, "...": "..." ]
}
```

### 3.3 从响应中提取图层清单

```bash
# 图层清单：id / 名称 / 类型 / 绑定的数据集 / 保存时的显隐
curl -s 'http://localhost:3001/api/projects/<pid>' \
  | jq -r '.spec.layers[] | [.id, .name, .type, (.dataset // .sourceConfig.datasetId), (.visConfig.visible // true)] | @tsv'
```

### 3.4 ⚠️ `visible` 是「**上次保存值**」，不是运行时真值

- 用户在编辑器里点眼睛图标 → 写进 editor state → 自动保存时落库 → **这里能读到**。
- 智能体通过命令通道隐藏图层 → **只改浏览器内存，不落库** → **这里读不到**。

所以：**功能 01 单次调用是准的；但智能体连续操作后，这个值会与页面实际状态脱节。** 需要精确的运行时状态时，实现 §4 末尾的「可选增强：状态上报」。

---

## 四、需新增的接口（完整规格）

> v1.0 新增 N1~N5；v1.1 追加 N6；v1.2 追加 N7 / N8 与批量显隐、`layer.isolate`（见各节末尾）。

错误体统一沿用现有约定：`{"error": "..."}`。

### N1 `GET /agent/targets` — 查目标位置【功能 04】

一个接口两种模式：给 `key` 走精确匹配（编号），给 `q` 走模糊匹配（名称）。

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `datasetId` | ✅ | — | 目标所在流式数据集 |
| `key` | 二选一 | — | 精确匹配值（编号，如 mmsi） |
| `q` | 二选一 | — | 模糊关键词（名称片段） |
| `field` | ❌ | 见下 | 匹配哪个 payload 字段 |
| `windowMinutes` | ❌ | 60 | 模糊模式的时间窗（**性能关键**） |
| `limit` | ❌ | 20 | 模糊模式最多返回条数 |

`field` 默认值：`key` 模式 → 数据集 `metadata.streamKey`；`q` 模式 → **无默认，必填**（名称字段无法预知，见风险 5）。两种模式同时给 → 400。

**返回**（两种模式同一形状，便于调用方统一处理）：

```json
{
  "mode": "exact",
  "field": "mmsi",
  "rows": [
    {
      "key": "245272000",
      "lng": 121.47, "lat": 31.23,
      "eventTime": "2026-09-10T15:28:11Z",
      "payload": { "mmsi": 245272000, "name": "远洋之星", "speed": 8.2 }
    }
  ],
  "rowCount": 1,
  "truncated": false
}
```

**约定的字段语义**（v1 冻结）：

- **`key` = 该目标在数据集 `metadata.streamKey` 字段上的值（编号），不是本次被搜索的字段值。** 两种模式都如此。它是后续操作的凭据（历史轨迹、选中目标都用它）。数据集未配 `streamKey` 时为 `null`。
- `field` = **本次匹配用的字段**（`key` 模式默认取 `streamKey`；`q` 模式必填，不预设默认）。
- `payload` = 该目标最新一条报文的**完整**原始字段，供详情面板直接展示，不做裁剪。
- `rowCount == 0` 表示未找到。**不返回 404**——两种模式行为一致，调用方只需判断 `rowCount`。

**实现要点**：

- 精确模式 SQL（配索引后毫秒级，**04 主路径**）：
  ```sql
  SELECT event_time, payload FROM <table>
  WHERE dataset_id = ? AND payload ->> '<field>' = ?
  ORDER BY event_time DESC LIMIT 1
  ```
- 模糊模式 SQL：
  ```sql
  SELECT event_time, payload FROM <table>
  WHERE dataset_id = ?
    AND event_time >= ?                    -- now() - windowMinutes，必须有
    AND payload ->> '<field>' ILIKE ?
  ORDER BY event_time DESC LIMIT ?
  ```
- 结果按目标 key **去重，只留每个目标最新一条**。
- **`<field>` 内联为字面量并过 `^[a-zA-Z0-9_]+$` 白名单**——不能用 `payload ->> ?` 占位符，PG 会在 `->> text` 与 `->> integer` 之间判定不出唯一算子而报 *operator is not unique*（`StreamHistoryService.java:122-123` 已记录此坑）。
- 复用 `StreamHistoryService` 的连接/校验逻辑（新增兄弟方法，别另起一套）。

**错误**：数据集不存在/不属于该项目 → 404；非流式或 `streamKey` 缺失（key 模式）→ 400；未配 TSDB → 503。

### N2 `GET /agent/regions` — 区域字典【功能 05】

| 参数 | 必填 | 说明 |
|---|---|---|
| `q` | ❌ | 区域名或别名；不传返回全部 |

**返回**：

```json
{
  "regions": [
    { "key": "bohai", "name": "渤海", "aliases": ["渤海湾", "环渤海"],
      "bbox": [117.0, 37.0, 122.5, 41.0], "center": [119.7, 39.0], "zoom": 7 }
  ],
  "rowCount": 1
}
```

`bbox` 顺序为 `[minLng, minLat, maxLng, maxLat]`。未命中返回 `{"regions": [], "rowCount": 0}`（同样不用 404）。

数据来源与预置清单见 §7。

### N3 `POST /agent/commands` — 下发指令【功能 02/03/04/05/08】

**请求体**：

```json
{ "type": "layer.visibility", "payload": { "layerId": "layer_abc", "visible": false } }
```

**查询参数（v1.1 追加，均可选）**：

| 参数 | 默认 | 说明 |
|---|---|---|
| `wait` | `false` | `true` = 本次调用阻塞至多 `timeoutMs` 毫秒等页面回执，响应里多一个 `result` 字段、`status` 变成 `done` |
| `timeoutMs` | 5000 | 等回执的超时毫秒数，仅 `wait=true` 时有效；服务端上限 30000 |

**为什么追加**：v1.0 的 N3 发完就走，`status:"queued"` 只说明「入队成功」，智能体无法区分
「页面执行了」和「页面根本没开」。`wait=true` 让一次调用就能拿到确定结论（附录 C 决策 19）。

**指令类型全表**（附录 A 有速查版）：

| `type` | `payload` | 功能 |
|---|---|---|
| `layer.visibility` | `{ layerId \| layerName, visible: boolean }` | 02 |
| `layer.visibility` | `{ scope:'all', visible, keepTiles? }` （v1.2 批量形态） | 02 |
| `layer.isolate` | `{ layerIds? \| layerNames?, keepTiles? }` （v1.2） | 02 |
| `layer.bringToFront` | `{ layerId \| layerName }` （v1.3） | 08 |
| `layer.sendToBack` | `{ layerId \| layerName }` （v1.3） | 08 |
| `map.focus` | `{ mode:'point', lng, lat, zoom }` | 03 |
| `map.focus` | `{ mode:'bounds', bbox:[minLng,minLat,maxLng,maxLat], padding? }` | 03 / 05 兜底 |
| `map.focus` | `{ mode:'region', regionKey }` | 05 |
| `map.reset` | `{}` | 03 复位 |
| `target.select` | `{ target:{key,name,lng,lat,payload}, focus, openPanel }` | 04 |
| `dataset.create` | `{ datasetName, columns, rows, layerName?, autoCreateLayer? }` （v1.4） | 09 |
| `layer.update` | `{ layerId \| layerName, name?, description?, visible?, parser?, visType?, color?, size?, opacity? }` （v1.4） | 09 |

**返回**：`{ "cmdId": "c_8f2a", "seq": 42, "status": "queued" }`；
`wait=true` 且页面已回执时：`{ "cmdId": "c_8f2a", "seq": 42, "status": "done", "result": { "ok": true, "error": null, "detail": {...}, "executedAt": "..." } }`。

**v1.4 起响应多两个字段**：

- `capability`：本条指令属于哪一类（`runtime` / `config`）。智能体据此判断**该去哪类页面**等回执，
  也便于「等超时了到底是谁没开」的排障。
- `hint`：仅当 `wait=true` 且**等到超时仍未回执**时出现，说明该去哪儿打开开关（原文）：

  > `runtime`：该指令只改运行时视图，需在地图页打开「智能体桥」组件上的「接入智能体」开关后才会执行（刷新页面即恢复）
  > `config`：该指令会写入项目配置，需在 Builder 页（/builder/{projectId}）执行，并先在该页地图的「智能体桥」组件上打开「接入智能体」开关（该开关同时授权配置写入）

  **`config` 的两个条件是并列的**：必须在 Builder 页（只有它有编辑器状态与自动保存），
  且用户得在**那一页**上打开「接入智能体」开关。`hint` 里**不会**出现「智能体配置桥开关」这种说法——
  「智能体配置桥」是执行器组件，它**没有**自己的开关。

**实现要点**：

- 服务端维护 `Map<String /*projectId*/, Deque<Command>>` + 每项目一个 `AtomicLong seq`。
- **只入队，不落库**：命令是瞬时的，不进达梦。进程重启即丢——这符合「只控制运行时视图」的语义。
- 队列上限（建议 100 条），超出丢最旧。
- **`region` 模式在此处就解析成 `bounds`**：页面侧只认 `point`/`bounds`，执行器保持简单。
- **`layerId` 校验必须存在于该项目**，防止越权操作别的项目图层。
- **`layerName` 替代 `layerId`（v1 契约内）**：`payload` 允许用 `layerName` 代替 `layerId`，服务端在本项目内做唯一匹配；不存在或重名 → 400，并在 `error` 中列出候选名。这样「隐藏『船舶实时』」可以少一次往返。
  v1.3 起这条规则由 `resolveLayerRef` 统一实现，`layer.visibility`（单层）/`layer.bringToFront`/`layer.sendToBack` 三处共用同一套判定与文案（重名**绝不猜**，一律列 id 让调用方改口）。

### N4 `GET /agent/commands?since=<seq>&timeout=25&capabilities=runtime` — 页面取令

**语义**（v1 冻结）：

- 返回**严格大于** `since` 的命令（`seq > since`）。
- **省略 `since`** → 不回放任何指令，只返回当前最新 `seq`（`commands: []`）。页面**首次连接用它对齐游标**，避免执行陈旧指令。
- 响应里的 `seq` 是服务端当前最新序号，页面下次拿它当 `since`。
- 没有新命令时挂起至多 `timeout` 秒（**服务端上限 30s**）后返回空数组。
- **多开页面 = 广播**：所有订阅该项目的页面都会拿到并执行同一条指令，不做抢占。**运行时指令**都是幂等的视图操作（同样的显隐、同样的视野），多开时表现一致。⚠️ **但配置指令（`dataset.create` / `layer.update`）不是视图操作**：多开 = 各写一次，`dataset.create` 会**建出重复的数据集与图层**（与「回执不明时不要重发」是同一个道理）。

**新增查询参数 `capabilities`（v1.4 追加，可选）**：

| 值 | 含义 |
|---|---|
| 省略 | **不过滤**，返回全部指令（与 v1.0 完全一致，向后兼容） |
| `runtime` | 只取只改运行时视图的指令（地图侧「智能体桥」用） |
| `config` | 只取会写入项目配置的指令（Builder 页「智能体配置桥」用） |
| `runtime,config` | 逗号分隔，等同省略 |

- **不属于本次 `capabilities` 的指令留在队列里**，不会被取走丢弃，等另一个执行器来取——
  这是该参数能用的前提（否则两个执行器会互相把对方的指令吃掉）。
- **游标照常推进**：响应里的 `seq` 仍是服务端全局最新序号，**不是**「最后一条命中的指令序号」。
  所以两个执行器各自维护自己的 `since` 是安全的：被过滤掉的指令不会导致自己的游标跳过后续指令
  （`seq` 严格递增，各自只关心命中的那些）。
- 非法值 → 400 并列出可用值。

**为什么需要它**：v1.4 起有了两个执行器（§2「两类执行器」）。若都不带该参数，
Share 页会把 `config` 指令抢走然后执行失败、Builder 页也拿不到本该它做的指令。

```json
{ "commands": [ { "cmdId": "c_8f2a", "seq": 42, "type": "layer.visibility", "payload": {...} } ], "seq": 42 }
```

**为什么用长轮询而不是 WebSocket**：项目已有 WS（第二端口 3002），但 `handleTextMessage` 是空实现、没有反向通道，且 nginx 下要额外配升级与端口转发。长轮询**零新增基础设施**、穿透任何代理、`curl` 就能模拟页面调试。延迟敏感时再升级（见风险 4）。

### N5 `POST /agent/commands/{cmdId}/result` — 页面回执

**请求体**：`{ "ok": true, "error": null, "detail": { ... } }`。

用途：让智能体能确认指令**真的被执行了**（而不是页面根本没开）。没有回执时智能体只能"假定成功"。

- `cmdId` 未知（已被队列上限挤出）→ 404。
- 重复提交**幂等覆盖**（页面重试不会报错）。

> ⚠️ **本接口是浏览器页面专用的写入端，智能体不应调用**。智能体读回执走 N6。

### N6 `GET /agent/commands/{cmdId}/result` — 读回执【v1.1 追加】

**返回**：

```json
{
  "cmdId": "c_8f2a", "seq": 42, "type": "layer.visibility",
  "status": "done",
  "result": { "ok": true, "error": null, "detail": { "layerId": "layer_abc", "visible": false }, "executedAt": "2026-09-11T10:12:03Z" }
}
```

- `status:"queued"` + `result:null` → 还没被执行（页面未打开，或页面上没开启「接入智能体」开关）。
- `status:"done"` → 看 `result.ok`；`ok:false` 时读 `result.error` 向用户说明原因。
- `cmdId` 未知（已被回执表 LRU 挤出，容量 200）→ 404。

**为什么追加**：v1.0 只有 N5（页面写回执）**没有读回执的路径**，而附录 B 「任何控制指令下发后都要
确认实际结果」一条要求智能体
「有回执时读回执确认」、功能 02 的流程第 4 步也依赖读回执——缺了读路径，这两个要求都落不了地。
属纯新增，不影响既有调用方（附录 D：只加不删）。有了 N6 之后，N3 的 `wait` 只是「省一次往返」的糖，
两者读的是同一份回执。

---

### N7 `GET /api/agent/projects` — 查项目 ID【功能 06，v1.2 追加】

**注意路径不在 `/api/projects/{projectId}/agent` 下**（无需 `projectId`，见 §2 说明）。

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `q` | ❌ | — | 项目名关键词，双向包含、大小写不敏感；不传 = 按更新时间倒序取前 `limit` 个 |
| `limit` | ❌ | 50 | 最多返回条数，钳制 `[1, 200]` |

**返回**：

```json
{
  "projects": [
    { "projectId": "79f3431e-...", "projectName": "船舶监控", "description": "东海方向", "updateTime": "2026-09-17 10:00:00" }
  ],
  "rowCount": 1
}
```

**为什么需要**：智能体手里通常只有「项目名称」，而除本接口外的所有接口都要 `projectId`。
现有的 `GET /api/projects` 能用，但它返回**原始实体**（含体积很大的 `mapConfig`）、且不支持按名称搜——
让智能体拉全量再自己翻，既费 token 又费时间。本接口只回四个轻量字段。

未命中 → 200 + `rowCount: 0`（同决策 7，不用 404）。

### N8 `GET /agent/targets/in-region` — 区域筛选目标【功能 07，v1.2 追加】

语义：**此刻**这片区域里有哪些目标——即「每个目标的最新位置落在矩形内」。
与「历史上进过这片区域的目标」**不是一回事**（后者要扫全部历史点，是另一个量级的查询）。

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `datasetId` | ✅ | — | 流式数据集 ID |
| `region` | 二选一 | — | 区域 key（`bohai`）或中文名/别名（`渤海`），走 N2 的同一本字典 |
| `bbox` | 二选一 | — | 逗号分隔的 4 个数值 `minLng,minLat,maxLng,maxLat`（决策 6 的顺序） |
| `windowMinutes` | ❌ | 60 | 只取最近多少分钟的报文，钳制 `[1, 10080]` |
| `limit` | ❌ | 200 | 最多返回的目标数，钳制 `[1, 2000]` |
| `countOnly` | ❌ | `false` | `true` = 只数个数，`rows` 为空、`rowCount` 仍为命中数 |
| `scanLimit` | ❌ | 20000 | 最多扫描的原始报文行数，钳制 `[1, 50000]` |

**返回**：

```json
{
  "filter": { "region": "bohai", "regionKey": "bohai", "regionName": "渤海", "bbox": [117.0, 37.0, 122.5, 41.0] },
  "field": "mmsi",
  "rows": [
    { "key": "245272000", "lng": 119.7, "lat": 38.9, "eventTime": "2026-09-17T02:10:00Z",
      "payload": { "mmsi": 245272000, "name": "远洋之星" }, "region": "bohai" }
  ],
  "rowCount": 1,
  "scanned": 8421,
  "truncated": false
}
```

- `rows` 的元素形状与 N1 **完全一致**（`key`/`lng`/`lat`/`eventTime`/`payload`），另加 `region` 回填区域 key
  （手给 `bbox` 时为 `null`）。`filter.regionKey`/`regionName` 同理。
- `scanned` = 时间窗内实际逐行判定的原始报文行数，用于判断 `truncated` 是「数据太多」还是「框里真没人」。
- `truncated:true` 有两种成因：扫描窗口用尽（`scanned` 已达 `scanLimit`，可能还有目标没扫到）、
  命中数超过 `limit`（`rows` 没取全）。`countOnly` 模式下 `rowCount` 不受 `limit` 限制。
- **精度是矩形**（决策 22）：`bbox` 判定用的是矩形，`region` 命中的也是字典里配置的矩形，
  所以「框内的陆地/邻区目标」也会被算进来，属已知过包含。

**错误**：`region` 与 `bbox` 都不给或都给 → 400（不猜）；区域名命中多条（如「海峡」）→ 400 并列出候选 key；
未知区域 → 400 并列出全部可用 key；数据集不存在 → 404；非流式或缺 `streamKey` → 400；未配 TSDB → 503。

**实现要点**：SQL 只做 `dataset_id + event_time >= ?` 并按时间倒序取 `scanLimit+1` 行（多取一行判截断），
**bbox 在 Java 内存里判**（`StreamHistoryService.inBbox`）——目标标识与经纬度都埋在 payload jsonb 里，
时序库上没有可用的空间索引，把 bbox 写进 SQL 只能全表扫再加 jsonb 过滤，不比取回后判快。
逐行按 `streamKey` 去重只留每个目标最新一条，再判是否落在框内。

### v1.2 的指令形态：批量显隐与 `layer.isolate`

`layer.visibility` 保持 v1.0 的单层形态不变，另加**批量形态**；并新增 `layer.isolate`。

| 形态 | payload | 说明 |
|---|---|---|
| 单层（v1.0） | `{layerId \| layerName, visible}` | 点谁改谁，**不做**瓦片特殊处理 |
| 批量（v1.2） | `{scope:'all', visible, keepTiles?}` | 整片图层一起改；`keepTiles` 默认 **true** |
| 隔离（v1.2） | `{layerIds?, layerNames?, keepTiles?}` | 只显示点名的图层，其余隐藏；`keepTiles` 默认 **true** |

**`keepTiles` 的语义（决策 23）= 瓦片图层一律不动**：既不隐藏也不浮现。
这样「隐藏全部图层」不会把底图也关掉、留下空白画布。要连瓦片一起改，必须显式传 `keepTiles:false`。
瓦片判定在**页面侧**做（图层资产类型 `TileLayer`/`RasterTileLayer`/`RasterLayer`/`MVTLayer`，
或它绑定的数据集类型 `xyz-tile`/`raster-tile`/`mvt-tile`/`vector-tile`）——后端不复制一份图层类型知识。

**回执 `detail`（N6 读）**：

```json
// layer.visibility 批量
{ "scope": "all", "visible": false, "keepTiles": true,
  "changed": [{ "layerId": "layer_abc", "layerName": "船舶实时", "visible": false }],
  "skipped": [{ "layerId": "tile_1", "layerName": "底图", "reason": "tile" }] }

// layer.isolate
{ "keepTiles": true,
  "changed": [{ "layerId": "layer_abc", "layerName": "船舶实时", "visible": true }],
  "skipped": [{ "layerId": "tile_1", "layerName": "底图", "reason": "tile" }],
  "missing": [] }
```

`missing` = 点名了但运行时图层 store 里不存在的 `layerId`（后端已按达梦里的图层表校验过，
出现 `missing` 说明页面加载的版本与库不一致，如实回报而非静默吞掉）。

### v1.3 的指令形态：图层层级（`layer.bringToFront` / `layer.sendToBack`）

**问题**：两个图标图层叠在一起时，永远有一个盖住另一个。编辑器图层面板拖动**对图标图层无效**（原因见下），
智能体侧此前也没有任何手段。

**契约（只两个动作，不做完整排序）**：

| `type` | `payload` | 语义 |
|---|---|---|
| `layer.bringToFront` | `{ layerId \| layerName }` | 该图层盖到**所有业务图层之上** |
| `layer.sendToBack` | `{ layerId \| layerName }` | 该图层压到**所有业务图层之下**，但**仍在瓦片底图之上** |

- **不做「完整 z 序」**（不给 `zIndex` 绝对值、不做 `layer.order` 列表）：图层的相对次序是**项目配置**
  （`visConfig.zIndex`，编辑器面板拖动就是在改它），智能体一次临时改动不应该、也无法表达整套次序。
  「提到最上 / 压到最下」这两个动作在真实问答里覆盖绝大多数诉求（「让船舶盖住轨迹」）。
- **后端只做「名字 → id」的钉死**，不下发 `zIndex`：层清单、哪些是瓦片层，只有运行页面知道（§6 不变式 ① 同理）。
- **`sendToBack` 不会把图层藏进底图下面**：分配的 zIndex 严格大于当前全部瓦片图层的最大 zIndex。

**回执 `detail`（N6 读）**：

```json
{ "layerId": "layer_abc", "layerName": "船舶实时",
  "action": "front", "before": 0, "zIndex": 41 }
```

`before` = 改动前的 zIndex（没配过是 0），`zIndex` = 页面实际写入的值。

**页面侧怎么算 zIndex**（实现细节，调用方不用管）：

1. 读出全部图层的 `visConfig.zIndex`（缺省 0，与 L7 侧同口径）与**瓦片图层的最大 zIndex** `tileMax`；
2. `bringToFront` → `max(其余图层的 zIndex …, tileMax, 0) + 1`；`sendToBack` → 从 `tileMax + 1` 起找**第一个没被占用**的值；
3. **必须是没被占用的值**——L7 的渲染列表按 zIndex 升序稳定排序，**相等时回落到插入顺序**，撞值等于没改；
4. 双写：`layersStore.updateLayer(id, {visConfig:{zIndex}})`（存进 li 状态，让编辑器/自动保存看到）
   + 直接 `instance.setIndex(zIndex)`（立刻生效，见下）。

**为什么必须双写（关键坑）**：li 的图层分两类，只有一类吃 `zIndex`——

- **普通图层**（PointLayer / LineLayer / PolygonLayer …，即 larkmap 的 `CoreLayer`）：
  `updateConfig` 会把变化的 `options.zIndex` 通过 `setIndex` 应用下去，写 `visConfig.zIndex` 就有效。
- **图标图层 / 聚合图层**（`IconLayer`、`IconImageLayer`、`BubbleLayer`、`ClusterLayer` —— 都是 `CompositeLayer`）：
  `CompositeLayer.update()` **完全不处理 zIndex**（只做 updateOption / changeData / updateSubLayers），
  子图层创建时也不带 zIndex，larkmap 里更是**没有任何 `setIndex` 调用**。
  → 这类图层只能**显式调 `composite.setIndex(n)`**（内部转成 `subLayers.setZIndex`），
  而它会把**该图层的全部子图层设成同一个 zIndex**，所以**图层之间的 zIndex 值必须互不相同**（见上面第 3 条）。

这就是「拖动图层面板改不动图标图层」的根因，也是这条指令存在的理由。

### v1.4 的指令形态：两步建图层（`dataset.create` / `layer.update`）

**用户诉求**：让智能体「用这批数据建一个图层」。而 li 的图层**必须绑数据集**，所以拆成两步：

1. **`dataset.create`** — 建数据集，并让**产品自己**据此生成图层；
2. **`layer.update`** — 在上一步自动生成的图层上改属性（名字、显隐、样式、可视化类型）。

**为什么第一步不直接「建图层」**（这是本设计的关键）：把「数据长什么样」交给产品已有的推断链路，
比让智能体描述「图层长什么样」简单得多，也不用把图层资产的内部结构写进契约。

产品侧已有的链路（`packages/li-editor`，**本功能没有重写它**）：数据集带 `metadata._autoCreateLayers=true`
→ `EditorDatasetManager.update` → `autoCreateSchemaHandler` → 按列名推断可视化类型、产出图层 Schema、
追加 LayerPopup 字段、把左侧导航切到「图层」、并把地图飞到数据范围。

- 推断的**确切规则**（`packages/li-editor/src/utils/dataset.ts` `getPointFieldPairs`）：
  - **只看 `type==='number'` 的列**；
  - 列名**小写后**按「整词」匹配 `POINT_FIELDS` 里的一对——`lat`↔`lng`、`lat`↔`lon`、`lat`↔`long`、
    `latitude`↔`longitude`、`纬度`↔`经度`、`wd`↔`jd`（另有 `起点/终点纬度` 两条，但按下面的边界规则，
    写成 `起点纬度` 是**匹配不上**的，须写成 `起点_纬度` 之类）；
  - 「整词」= 该词前面是**行首或** `#_&@.- 空格` 之一，后面同理。所以 `lng`/`lat` ✓、`ship_lng` ✓、
    `shiplng` ✗；
  - 配对的名字由**同一个字符串里替换那个词**得到，故前后缀必须一致：`start_lat` 配 `start_lng` ✓，
    `start_lat` 配 `lng` ✗。
  - 命中后图层名 = **`数据集名_<纬度列名>`**（列没有 `displayName` 时用**小写后的列名**）；
  - **自动生成的图层只有前两个默认可见**（`visible: [0,1].includes(index)`），其余建出来了但是隐藏的。

> **给数据时务必注意**：经纬度列要用 `lng`/`lat`（或 `lon`/`lat`、`longitude`/`latitude`、`jd`/`wd`）
> 这类**能被识别的名字**，且**声明为 `number`**。名字不配对（如 `经度`/`纬度` 之外的自造名、或 `x`/`y`）
> → 数据集建得成但**不会生成图层**，回执里给 `warning`（不是报错：数据确实存好了）。

#### `dataset.create` 的 payload

| 字段 | 必填 | 说明 |
|---|---|---|
| `datasetName` | ✅ | 数据集名，≤100 字。**自动图层名由它拼出来**，取个能读的名字 |
| `columns` | ✅ | `[{name, type, displayName?}]`，≤100 列。`name` ≤64 字、不含控制字符，**不许重名** |
| `rows` | ✅ | `[{列名: 值}]`，≤5000 行。每行的键必须是 `columns` 里声明过的列名 |
| `layerName` | ❌ | 想给图层指定名字。**仅在恰好自动生成 1 个图层时生效**（多个图层时改谁都不对，会在回执里说明） |
| `autoCreateLayer` | ❌ | 默认 `true`。`false` = 只建数据集、不生成图层 |

- **列类型**：只认 `string` / `number` / `boolean` / `geo` / `date` / `h3` 六种；
  传别名会被宽容映射（`int`/`float`/`double`/`decimal`/`bigint` → `number`，`datetime`/`timestamp` → `date`，
  `point`/`polygon`/`geometry` → `geo`，`text`/`varchar` → `string`，中文「数值/布尔/日期/字符/地理」同样认）。
  **认不出的一律按 `string`**，不报错（列类型只影响样式候选，不影响能不能画）。
- **单元格**：只接受标量（字符串/数字/布尔/null）；**不许嵌套对象或数组**——
  传 `{lng, lat}` 这种会被拒，并提示拆成两个列。单个值 ≤512 字。
- **行数据会真的落库**：执行器先调 `POST /api/projects/{pid}/datasets/upload` 存行，成功后才把数据集推进编辑器状态。
  顺序不能反——Builder 的自动保存会**剥掉**数据集里的行数据（`stripDataRowsFromApplication`），
  只推状态不上传的话，刷新后数据集就是空的。上传失败则整条指令失败（不会留下「有图层却查不到数据」的假数据集）。

#### `layer.update` 的 payload

| 字段 | 说明 |
|---|---|
| `layerId` 或 `layerName` | 定位图层（与 `layer.visibility` 同一套 `resolveLayerRef`：不存在/重名 → 400 + 候选） |
| `name` | 改图层名 |
| `description` | 图层备注（存 `metadata.description`，纯说明、不参与渲染，≤200 字） |
| `visible` | 显隐 |
| `parser` | 坐标字段映射：`{x, y}` **或** `{geometry}`（二选一，不许都给）。列名会按该图层的数据集列校验 |
| `visType` | 换可视化类型（如 `BubbleLayer` → `IconLayer`）。**与下面三项互斥** |
| `color` / `size` / `opacity` | 改颜色 / 尺寸 / 透明度为**固定值** |

- **至少给一个要改的字段**，否则 400。
- **`visType` 与样式三项互斥**：样式字段名随资产而变（`BubbleLayer` 用 `fillColor`/`radius`，
  `LineLayer` 用 `color`/`size`…），换完类型旧字段就失效了，同时给会写进一个没人读的键（表现为「改了没反应」）。
  要换类型又要改样式 → **分两条指令**，先换类型再改样式。
- **样式字段映射**（执行器内置表，只登记确实存在的字段）：

  | 图层资产 | `color` | `size` | `opacity` |
  |---|---|---|---|
  | `BubbleLayer` / `MVTLayer` | `fillColor` | `radius` | `opacity` |
  | `ChoroplethLayer` / `H3HexagonLayer` | `fillColor` | —（无尺寸概念） | `opacity` |
  | `LineLayer` | `color` ＋ `style.sourceColor` ＋ `style.targetColor` | `size` | `style.opacity` |
  | `ArcLayer` | `style.sourceColor` ＋ `style.targetColor` | `size` | `style.opacity` |
  | `IconLayer` | —（图标自带颜色） | `radius` | `iconStyle.opacity` |
  | `GridLayer` | `color` | — | `style.opacity` |
  | `TileLayer` | — | — | `style.opacity` |
  | `ClusterLayer` | `fillColor`（`renderer:'dot'` 时） | `radius` | `opacity` / `iconStyle.opacity` |

  `LineLayer`/`ArcLayer` 的颜色**三处一起写**成同一个值：样式面板与默认配置分别写顶层 `color` 与
  `style.sourceColor`/`targetColor`，只写一个可能不生效。
- **改不了就明确报错**，不静默忽略：给某图层改了它没有的项（如给 `IconLayer` 改颜色、给 `TileLayer` 改尺寸）
  → 该条指令失败，`error` 里说明「可视化类型 X 没有 color 对应的样式字段」。
  静默忽略会让智能体以为改成功了。
- **`visType` 会重置样式**：整块 `visConfig` 换为新资产的默认样式（与编辑器里换可视化类型同一行为），
  但**保留可见性**（刚设的显隐不该因为换类型而变）。

#### 回执 `detail`（N6 读）

```json
// dataset.create（成功，自动生成 1 个图层）
{ "datasetId": "dataset_7c1f", "datasetName": "试验航迹", "rowCount": 3,
  "autoCreateLayer": true,
  "layers": [{ "layerId": "BubbleLayer_9a2b", "layerName": "试验航迹_lat", "type": "BubbleLayer", "visible": true }],
  "renamedTo": "试验航迹" }

// dataset.create（列名没能配对 → 数据集建好了，但没有图层）
{ "datasetId": "dataset_7c1f", "datasetName": "试验航迹", "rowCount": 3, "autoCreateLayer": true,
  "layers": [],
  "warning": "数据集已创建，但没有自动生成图层（多半是列里没有可识别的经纬度/几何字段）" }

// layer.update
{ "layerId": "BubbleLayer_9a2b", "layerName": "试验航迹", "type": "BubbleLayer",
  "changed": ["name", "color", "size"], "applied": { "name": "试验航迹", "color": "#F86624", "size": 12 },
  "note": "改动已写入编辑器状态，随 Builder 自动保存落库；页面上即时生效" }
```

`layers[].layerId` 就是接着调 `layer.update` 要用的 id；`note` 里的「随自动保存落库」是**重要语义**：
改动先进编辑器状态，随后（300ms 防抖）随整份 application 快照提交到达梦。所以：

- **生效是「立即」的**（编辑器状态一变，页面就重渲染），但**落库有几百毫秒延迟**；
- 与 `runtime` 类指令不同，`config` 类指令**刷新页面后仍在**（真的改进了项目）。

#### 错误

- 项目不存在 → 404；`dataset.create` 缺 `datasetName`/`columns`/`rows` → 400；
  列重名、列名非法、行里的键不在列里（`error` 里**列出可用列名**）、单元格是嵌套对象、
  行数为 0 或超过 5000（提示改走文件上传）→ 400。
- `layer.update`：`layerId`/`layerName` 都缺或都无效 → 400；一个字段都没给 → 400；
  样式的值超出范围 → 400；`visType` 与样式三项同给 → 400；
  `visType` 不是本项目已注册的图层资产 → 400（`error` 里说明去哪看可用资产）；
  `parser` 的列名不在该图层的数据集里 → 400；`parser` 给 `geometry` 又给 `x`/`y` → 400。

#### 执行位置与前置条件（**与 02~08 不同，务必注意**）

- 只在 **Builder 页**（`/builder/{projectId}`）执行，且该页地图上必须挂有「智能体桥」组件、
  用户**打开了「接入智能体」开关**。Share 嵌入页/预览页**没有**这条通路（它们不落库），
  所以这类指令在那两页会一直 `queued`。
- 开关**没有单独的「配置」开关**：用户在地图上打开「接入智能体」= 同时授权本页的配置写入。
  `hint` 里的原文见 §4 N3。
- **这是真的改用户的项目**：与 02~08「刷新即恢复」有本质区别。下发前应当向用户说明会写入什么。

> ⚠️ **未做运行时验证**：本条链路（`_autoCreateLayers` → 自动图层 → `layer.update`）是按
> `packages/li-editor` 源码实现的，**尚未在真实页面上跑通一次**（见 §11）。
> 已知最可能出问题的一环是 `waitForAutoCreatedLayers` 的 3 秒轮询窗口：
> 若数据集查询慢于 3 秒，回执会退化成「数据集已创建但没有自动生成图层」的 warning，
> 此时图层其实稍后会自己出现——**这种情况应让用户看页面确认，而不是重发指令**（重发会建出重复的数据集）。

### 可选增强（阶段 4，非必需；不属于 v1 契约）

**`POST /agent/state`** — 页面上报运行时状态（图层可见性 + 当前视野），每 5 秒或状态变化时上报。用途：让功能 01 的 `visible` 反映**运行时真值**，消除 §3.4 的脱节。不做也不影响功能，只是智能体连续操作时可能基于过期状态决策。

---

## 五、五个功能的完整调用流程（01~05）

> **06~09 的调用流程不在本节**：v1.2~v1.4 追加的功能把「怎么调」直接写在了对应契约旁边
> ——06/07 见 §4 N7/N8，08 见 §4「v1.3 的指令形态」，09 见 §4「v1.4 的指令形态」，
> 可跑的 `curl` 见 §11。

每步都给了可直接跑的 `curl`（`<pid>` 换成项目 ID）。

### 功能 01 — 查看图层列表

```bash
curl -s "http://localhost:3001/api/projects/<pid>" \
  | jq -r '.spec.layers[] | [.id, .name, .type, (.dataset // .sourceConfig.datasetId), (.visConfig.visible // true)] | @tsv'
```

→ 智能体直接向用户陈述图层名、类型、绑定的数据集、保存时的显隐状态。
**注意**：`visible` 是上次保存值（§3.4）。

---

### 功能 02 — 控制图层显隐

**调用顺序**：

1. 先调**功能 01** 的接口，把用户说的中文名映射成 `layerId`——智能体通常只知道「船舶实时」，不知道 `layer_abc`。
2. 下发指令：

```bash
# 推荐：wait=true 一次调用直接拿到回执
curl -sX POST "http://localhost:3001/api/projects/<pid>/agent/commands?wait=true&timeoutMs=8000" \
  -H 'Content-Type: application/json' \
  -d '{"type":"layer.visibility","payload":{"layerId":"layer_abc","visible":false}}'
# → {"cmdId":"c_8f2a","seq":42,"status":"done","result":{"ok":true,...}}
# 不带 wait 时 → {"cmdId":"c_8f2a","seq":42,"status":"queued"}
```

3. 页面执行器拉到指令后调 `stateManager.layersStore.setLayerVisibility(layerId, false)`，图层立刻消失。
4. 读回执确认（`wait=true` 已在响应里，否则调 **N6** `GET /agent/commands/{cmdId}/result`），
   再回复用户「已隐藏『船舶实时』」。`status` 长期为 `queued` → 让用户去页面上打开「接入智能体」开关。

**关键约束**：这是**运行时**改动，**不落库、不进项目 JSON，刷新页面即恢复**。这既是不变式（§9），也正好符合「智能体不能篡改用户项目」的安全预期——需要持久化时必须明确告诉用户去编辑器里改。

---

### 功能 03 — 按经纬度聚焦缩放

**一步到位**：

```bash
curl -sX POST "http://localhost:3001/api/projects/<pid>/agent/commands" \
  -H 'Content-Type: application/json' \
  -d '{"type":"map.focus","payload":{"mode":"point","lng":121.47,"lat":31.23,"zoom":12}}'
```

→ 页面执行 `scene.setZoomAndCenter(12, [121.47, 31.23])`。`zoom` 未给时默认 12。

**复位**：`{"type":"map.reset","payload":{}}` → 用项目 `spec.map.config` 里的 `{zoom, center}` 调 `setZoomAndCenter`。
**不要**用重新初始化整个 application 的方式复位——那会连带清掉用户的其他运行时状态。

---

### 功能 04 — 按名称/编号查目标 → 聚焦 → 展示面板

**先查后控**，四步：

**① 从功能 01 拿到 `datasetId` 和 `streamKey`**（这一步别忘了，N1 需要它）

**② 查位置**——判断用户给的是编号还是名称：

```bash
# 编号（精确，快）
curl -s "http://localhost:3001/api/projects/<pid>/agent/targets?datasetId=ds_ship&key=245272000"
# → {"mode":"exact","field":"mmsi","rows":[{...,"lng":121.47,"lat":31.23,"payload":{...}}],"rowCount":1}

# 名称（模糊，需时间窗）
curl -s "http://localhost:3001/api/projects/<pid>/agent/targets?datasetId=ds_ship&q=远洋&field=name&windowMinutes=60"
# → {"mode":"fuzzy","rows":[...多条...],"rowCount":3}
```

**③ 处理结果**：
- `rowCount == 0` → 如实回复「未找到目标 X」，**不要**退化成模糊聚焦。
- `rowCount > 1`（模糊模式）→ **列出让用户选**，不要猜一条。
- `rowCount == 1` → 继续。

**④ 聚焦 + 展示面板**：

```bash
curl -sX POST "http://localhost:3001/api/projects/<pid>/agent/commands" \
  -H 'Content-Type: application/json' \
  -d '{"type":"target.select","payload":{"target":{"key":"245272000","name":"远洋之星","lng":121.47,"lat":31.23,"payload":{}},"focus":true,"openPanel":true}}'
```

→ 页面执行：`setZoomAndCenter(14,[lng,lat])` + 高亮该目标 + 渲染目标详情浮层（渲染 `payload` 字段）。

**详情面板为什么是自绘的**：现有的 `LayerPopup` widget **没有编程开关**（`isOpen` 只来自配置），无法由外部打开。所以执行器自绘一个浮层。这与 `RightClickMenu` 自绘菜单、自绘轨迹图层的做法一致——**不写入 li 的 store**，避免被 Builder 自动保存抹掉。

**加分项**：面板上放「查看历史轨迹」按钮 → 复用现有 `GET /api/projects/{pid}/datasets/{did}/stream/history?key=&start=&end=` 接口 + `RightClickMenu` 里现成的绘制逻辑。

---

### 功能 05 — 语义区域聚焦

**① 规范化**：智能体把「渤海区域」→「渤海」。

**② 查字典**：

```bash
curl -s "http://localhost:3001/api/projects/<pid>/agent/regions?q=渤海"
# → {"regions":[{"key":"bohai","name":"渤海","bbox":[117.0,37.0,122.5,41.0]}],"rowCount":1}
```

**③a 命中** → 下发：

```bash
curl -sX POST "http://localhost:3001/api/projects/<pid>/agent/commands" \
  -H 'Content-Type: application/json' \
  -d '{"type":"map.focus","payload":{"mode":"region","regionKey":"bohai"}}'
```

**③b 未命中**（`rowCount == 0`）→ **智能体依据自身地理常识直接产出 bbox**：

```bash
curl -sX POST "http://localhost:3001/api/projects/<pid>/agent/commands" \
  -H 'Content-Type: application/json' \
  -d '{"type":"map.focus","payload":{"mode":"bounds","bbox":[122.0,23.0,130.0,31.0]}}'
```

→ 页面执行 `scene.getMap().fitBounds(bbox, {padding:60, duration:600})`。

**为什么要有 ③b**：区域是开放集合，枚举永远不全（「第一岛链北段」「巴士海峡以东 200 海里」怎么枚举？）。**字典保证常用区域的准确边界，LLM 兜底保证覆盖率**——两者结合才是「智能理解」。智能体在回复中应说明自己用的范围，让用户可纠正。

---

## 六、页面执行器（实现方视角）

**形态**：Builder 页面上的一个组件「智能体桥」，职责是「取令 → 执行 → 回执」。

**骨架代码**（API 均已核实存在）：

```ts
const stateManager = useStateManager();
const [scene] = useScene();

async function execute(cmd: Command) {
  switch (cmd.type) {
    case 'layer.visibility':
      stateManager.layersStore.setLayerVisibility(cmd.payload.layerId, cmd.payload.visible);
      break;
    case 'map.focus':
      if (cmd.payload.mode === 'point') {
        scene?.setZoomAndCenter(cmd.payload.zoom ?? 12, [cmd.payload.lng, cmd.payload.lat]);
      } else {  // bounds（region 已由后端解析成 bounds）
        const map: any = (scene as any)?.getMap?.();
        map?.fitBounds?.(toBounds(cmd.payload.bbox), { padding: cmd.payload.padding ?? 60, duration: 600 });
      }
      break;
    case 'map.reset': /* spec.map.config 的 zoom/center → setZoomAndCenter */ break;
    case 'target.select': /* 聚焦 + 自绘高亮 PointLayer + 自绘详情浮层 */ break;
  }
}
```

**核心 API 出处**（已核实）：

| 能力 | 调用 |
|---|---|
| 图层显隐 | `stateManager.layersStore.setLayerVisibility(id, visible)` — `packages/li-sdk/src/state/layers.ts:54-60` |
| 地图实例 | `stateManager.mapStore.getScene()`（React 侧 `useScene()`） |
| 视野 | `scene.setZoomAndCenter(zoom,[lng,lat])` / `scene.getMap().fitBounds(...)` |
| 项目 ID | `window.__L7VP_PROJECT_ID__`（`website/src/pages/Builder/index.tsx:61` 已写入） |
| 运行时句柄 | `window.liRuntimeApp`（同文件 :64；React 内优先用 hook） |

**必须遵守的约束**：

1. **不得写入 li-sdk 的运行时 dataset / layer store**（`setLayerVisibility` 这一条官方路径除外）。Builder 会自动保存，而 `stateManager.initState` 在 datasets/layers 数组引用变化时**整体替换** store——塞进去的临时对象会被抹掉，且有被存进项目的风险（`RightClickMenu/Component.tsx:61-63` 已记录此坑）。
   - **注意区分两个 store**：这里是**运行时 store**（`@antv/li-sdk` 的 datasetStore/layerStore）。
     要**持久化**地改项目，唯一通路是**编辑器状态**（`useEditorState().updateState`，li-editor 的 editorState）
     ——那正是 §6.2 的 `config` 执行器走的路，两者不矛盾。
2. **必须有显式开关**：页面默认**不**接受智能体控制，用户手动开启后才开始轮询。避免任何能访问该页面的程序静默操纵地图。
3. 轨迹/高亮/详情浮层一律**自绘**，卸载即消失。

**一个待实测项**：`setLayerVisibility` 走的是运行时 store，而编辑器图层面板改的是 editor React state（`LayersPanel/LayerList/LayerItem/index.tsx:50`），**两者不保证双向同步**。实现阶段 0 时先实测，若表现不对就改走 editor 的更新路径。

### 6.1 已实现形态（2026-09-11；v1.2 追加见本节末尾）

**组件**：`packages/li-analysis-assets/src/widgets/AgentBridge/`（`Component.tsx` / `registerForm.ts` /
`ComponenStyle.ts` / `constants.tsx` / `index.tsx`），`implementWidget` 注册名 `AgentBridge`，显示名「智能体桥」，
`type:'Auto'` → 由 MapContainer 的 `controls` 槽位渲染在 `<LarkMap>` 内，故直接用 larkmap 的
`<PointLayer>` / `<Marker>` 自绘高亮与详情浮层。

**属性面板**（`registerForm.ts`）：
`enabled`（默认**关**）、`pollTimeout`（长轮询挂起秒数，5~30，默认 25）、`highlightColor`（默认 `#F86624`）。

**轮询循环要点**：
- 首次请求**不带 `since`**（只对齐游标、不回放），此后带上一次返回的 `seq`。
- `execute` 经 `useRef` 取最新版本，避免 `scene` 就绪时重启循环。
- 关闭开关 / 组件卸载 → `stopped` 标志 + `AbortController.abort()` 收口，请求不会悬挂 25 秒。
- 请求失败 → 置 `error` 态并 2 秒后重试；回执 POST 失败**不影响本页执行**，下一条继续。
- 项目 ID：`window.__L7VP_PROJECT_ID__`，非 Builder 页从 hash 路由兜底解析（沿用 RightClickMenu 约定）。

**回执内容**：`{ ok, error, detail }`，其中 `detail` 是执行的实际入参（如 `layer.visibility` 回
`{layerId, layerName, visible}`），让智能体读到的回执能直接复述给用户。

**v1.2 追加的执行形态（2026-09-17）**：

- **批量显隐**（`layer.visibility` + `scope:'all'`）与 **`layer.isolate`**：遍历
  `stateManager.layersStore.getLayerList()`，只对**非瓦片**图层调官方运行时路径
  `setLayerVisibility`，瓦片图层收进回执的 `skipped`（`reason:'tile'`）。
- **瓦片判定**（`Component.tsx` 的 `isTileLayer`）：图层 `type` ∈
  `{TileLayer, RasterTileLayer, RasterLayer, MVTLayer}`，**或**它 `sourceConfig.datasetId` 指向的数据集
  `type` ∈ `{xyz-tile, raster-tile, mvt-tile, vector-tile}`（数据集清单取自 `datasetStore.getDatasetList()`）。
  两头都认是因为瓦片图层与瓦片数据集在本项目里**不一定成对出现**（历史项目里有只配了一头的）。
- `layer.isolate` 还会回报 `missing`：点了名但运行时 store 里没有的 `layerId`（页面加载的版本与库不一致时
  才会出现，如实回报而不是静默吞掉）。

**v1.3 追加的执行形态（2026-09-17）**：

- **`layer.bringToFront` / `layer.sendToBack`**：按 §4「v1.3 的指令形态」的算法在页面侧算 zIndex，
  然后**双写**——`stateManager.layersStore.updateLayer(id, {visConfig:{zIndex}})` 走官方路径存状态，
  再 `useLayerList()` 找到该图层的实例调 `instance.setIndex(zIndex)` 立刻生效。
- **为什么要拿 `useLayerList()`**：`updateLayer` 只改 li 的 store；对**复合图层**（图标/聚合）它不落到 L7，
  必须直接对实例调 `setIndex`。实例取法：`useLayerList()` 返回的项里 `item.layer`
  （普通图层直接就是 L7 实例；复合图层按 li 图层 id ↔ 1 个 composite 对应，也取 `.layer`）。
- **图层不在当前页面**（`getLayerList()` 里找不到该 id）→ 回执 `ok:false` + `error`，不静默成功。
- **未做运行时验证**：`setIndex` 对复合图层的实际效果是本轮**读 L7/larkmap 源码**得出的结论
  （`composite-layer.js` 有 `setIndex`、`CoreLayer.updateConfig` 才会应用 `options.zIndex`，
  larkmap 里没有任何一处调 `setIndex`），需在真实页面上验证一次（见 §11）。

**仍未实测**：本文 §6 末尾那条 —— `setLayerVisibility` 与编辑器图层面板的同步表现（附录 C 待定项 C）。
本轮又读了一遍渲染链路，**代码路径是通的**：`LayerList` 订阅 store → `WrapperLayer` 把整个 `visConfig`
展平成 props 传给图层组件 → 图层组件 `{...props}` 透传给 larkmap（如 `BubbleLayer/Component.tsx`），
L7 图层的 `visible` 选项由此生效。但**这仍然是读代码得出的结论**，需在真实页面上打开开关跑一次确认
（**本轮未做运行时验证**，见 §11）。

### 6.2 编辑器侧执行器「智能体配置桥」（v1.4）

**组件**：`website/src/pages/Builder/widgets/AgentConfigBridge/`（`AgentConfigBridge.tsx` / `execute.ts` /
`agentFlag.ts` / `index.tsx`），`implementEditorWidget` 注册名 `AgentConfigBridge`，显示名「智能体配置桥」。

**为什么它在 website 而不在资产包**：它要 `useEditorState()` / `useEditorService()`——那是 li-editor 的
编辑器 API，只有 Builder 页有。而「智能体桥」（`AgentBridge`）在 `packages/li-analysis-assets`，
是**运行时 widget**，两处是不同性质的组件。

**挂载点**：`container: {type:'SideNav', slot:'bottom'}` —— **`container` 是必须的**：
`resolveContainerSlotMap` 会**跳过没有 `container` 的控件**，组件永远不渲染、也就无法轮询。
授权关闭时组件返回 `null`（antd `Space` 会丢掉 null 子节点），故关着时在导航栏底部**不占位置**；
开着时显示一枚 Tag（连接中/已写入 N 条/失败原因），让「智能体正在改这个项目」这件事可见。

**注册范围**：只进 `editorWidgetsWithBuilder`，**不进** `editorWidgets`——后者被
`Preview/Template.tsx` 共用，而那两页不该出现「会写项目配置」的执行器。

**取令**：与 AgentBridge 同构的长轮询，但带 `capabilities=config`（只取 `dataset.create` / `layer.update`）。

**授权互通**：读 `window.__L7VP_AGENT_ENABLED__`（写入侧是 `AgentBridge`，见下）。**两侧各读一份常量**，
不跨包 import——`packages/li-*` 在 website 侧是 `node_modules` 里的**独立拷贝**，
跨包 import 一个新增的具名导出，若包没重建会拿到 `undefined` **而不报错**，读侧组件直接白屏。
`window.__L7VP_PROJECT_ID__` 在这个项目里也是「li-editor 写、website 与资产包各读一次」，同一套路子。
**改旗标名要同时改两处**：`packages/li-analysis-assets/src/widgets/AgentBridge/agentFlag.ts` 与
`website/src/pages/Builder/widgets/AgentConfigBridge/agentFlag.ts`。

**`executeLayerUpdate` 为什么要先「全部校验再一次性写入」**：校验通过后才 `updateState`
（一次 immer 更新），避免出现「改了一半才失败」留下半个结果（如名字改了、样式没改）。

**硬点：改动必须写进编辑器状态，不能带外写库。** 见 `execute.ts` 顶部的说明：
Builder 每次 `change` 都会 PUT 一份完整 application 快照，后端 `ApplicationAssembler.disassemble()`
按 id **反向删除**（图层无条件删、数据集变孤儿就删）——任何绕过编辑器状态的写库结果都会在
下一次自动保存时被抹掉。

**§6.1 的运行时组件（`AgentBridge`）本轮的两处改动**：

- 取令加 `capabilities=runtime`（不抢 config 指令）；
- 开关变化时写 `window.__L7VP_AGENT_ENABLED__` 并派发 `l7vp-agent-enabled`；**组件卸载时置回 false**
  （离开页面即收回授权）；
- `enabled` 的属性面板提示补了一句：在 Builder 页打开它**同时**授权图层创建/修改。

---

## 七、区域字典

**数据**：`java-server/src/main/resources/regions.json`，字段 `{key, name, aliases[], bbox, center, zoom}`。

**预置清单**：

| key | 名称 | 别名示例 |
|---|---|---|
| `bohai` | 渤海 | 渤海湾、环渤海 |
| `huanghai` | 黄海 | |
| `donghai` | 东海 | |
| `nanhai` | 南海 | |
| `first_island_chain` | 第一岛链 | 岛链 |
| `second_island_chain` | 第二岛链 | |
| `taiwan_strait` | 台湾海峡 | |
| `bashi_channel` | 巴士海峡 | |
| `miyako_channel` | 宫古海峡 | |
| `malacca` | 马六甲海峡 | |
| `zhoushan` | 舟山群岛 | |
| `pearl_river_estuary` | 珠江口 | |

**匹配规则**：`name` 或任一 `aliases` 包含 `q`（大小写不敏感）即命中。

**局限**：bbox 是工程近似值，第一岛链这类**长条状区域用矩形框会覆盖大片无关海域**。后续可升级为 GeoJSON 多边形（接口形态不变，`bbox` 换成 `geometry`，由后端算外接矩形），对调用方透明。

---

## 八、实现顺序

严格按依赖排序，**每阶段结束都可独立验收**：

| 阶段 | 内容 | 解锁 | 依赖 |
|---|---|---|---|
| **0** | 命令通道：N3 + N4 + N5，执行器只实现 `layer.visibility` 和 `map.focus` | 功能 **02、03** | 无 |
| **1** | N1 精确模式（`key`） | 04 的查询腿 | 无（可并行） |
| **2** | N1 模糊模式（`q`）+ `target.select` + 自绘详情面板 | 功能 **04** | 0、1 |
| **3** | N2 区域字典 + `region` 模式 + 技能里的 LLM 兜底策略 | 功能 **05** | 0 |
| **4**（可选） | `POST /agent/state` 状态上报 | 01 的 `visible` 变准 | 0 |

**为什么阶段 0 先做**：02/03 是纯控制、无数据依赖，能最快验证「命令代理」这条链路是通的。**链路不通的话后面全是白做。**

**后端改动范围**：新增 `AgentController` + `AgentCommandService`（命令队列）；`StreamHistoryService` 加目标查询兄弟方法（N1）。**不用动 Flink、不用改数据库 schema**（索引除外）。

### 建议的索引（DDL 需在库上手工执行）

N1 的性能瓶颈是「按 payload 字段过滤」无索引（实测 0.7~1.6s）：

```sql
-- N1 精确查询（键名随数据集的 streamKey 而定）
CREATE INDEX IF NOT EXISTS idx_stream_events_ds_key_time
  ON stream_events (dataset_id, (payload ->> 'mmsi'), event_time DESC);

-- 模糊搜索（若 N1 的 q 模式要常用）
CREATE INDEX IF NOT EXISTS idx_stream_events_ds_name_time
  ON stream_events (dataset_id, (payload ->> 'name'), event_time DESC);
```

不做也能用，只是每次查询有秒级延迟。

---

## 九、安全与不变式

1. **鉴权**：`/agent/*` 必须校验登录态，与现有 `/api/*` 一致。**凭证形态（Cookie / Bearer token）实现期确定，不影响本文任何路径与参数**——调用方只需在请求头带上凭证即可。`l7vp.auth.mode=local` 下免登录。
2. **只读 + 瞬时不变式**：`/agent/*` **绝不写达梦、绝不改项目 application**。命令队列只在内存。这条要写进代码注释，防止后人"顺手"加了持久化。
3. **页面显式授权**：见 §6 约束 2。
4. **命令白名单**：`type` 必须匹配枚举，`payload` 逐字段校验；`layerId` 必须存在于当前项目。
5. **队列限流**：每项目队列上限 + 每分钟下发上限，防智能体失控刷屏。
6. **字段名白名单**：拼进 SQL 的字段名一律过 `^[a-zA-Z0-9_]+$`。

---

## 十、风险与备选

1. **模糊搜索的性能（最大风险）**：`payload->>'name' ILIKE '%q%'` 在 3.1M 行上无索引可走。三级缓解：① 强制时间窗（默认 60 分钟）；② 建表达式索引（见 §8）；③ **降级方案**——给 `StreamSessionManager` 加 `public List<Map<String,Object>> snapshot(String datasetId)`，直接搜内存里的实时全量表，**毫秒级且与地图所见完全一致**，代价是只覆盖在线目标。对「查某条船现在在哪」这个真实场景，③ 可能比查库更合适。**这属于服务端内部实现，N1 的请求/响应契约不受影响**——实现阶段 1 时实测后再定（见附录 C）。
2. **`setLayerVisibility` 与编辑器面板可能不同步**：见 §6 末。
3. **图层重名**：智能体只知道中文名，重名时必须返回 400 + 候选列表，**不能猜**。
4. **长轮询的连接占用**：每个打开的页面挂住一个连接。单机演示无碍；多页面并发时换 WS 或 SSE。
5. **名称字段无处可存**：目标 payload 是 Kafka 消息透传（字段名随数据源而定），不像 `streamKey` 有配置位。所以 N1 的 `q` 模式**必须显式传 `field`**。建议长期在「编辑数据集」里加一个与 `streamKey` 并列的名称字段配置，届时 `field` 就可省略。
6. **`region` 边界未校准**：见 §7 局限。

---

## 十一、验收

> **v1.2（2026-09-17）已完成**：后端 **4 个测试类共 63 个用例全通过**
> （`AgentControllerTest` 39 / `AgentCommandServiceTest` 12 / `RegionServiceTest` 8 /
> `StreamHistoryServiceTest` 4），其中 v1.2 新增覆盖 N7 的模糊检索与 limit 钳制、N8 的区域解析
> （key / 中文名 / 多命中 / 未命中）、bbox 解析与校验、`countOnly`、错误映射（400/404/503），
> 以及批量显隐与 `layer.isolate` 的归一化（`keepTiles` 默认值、按 id 去重、重名与未知图层拒绝）；
> `inBbox` 的矩形判定（含边界命中）单测覆盖。
> ⚠️ `StreamHistoryService.queryTargetsInRegion` 的**查询主体**（去重、limit、`truncated` 记账）
> 要连时序库，按本仓库惯例不进单测，**只由构造保证、未经真实数据验证**。
>
> **v1.3（2026-09-17）已完成**：后端测试累计 **69 例全通过**，新增 6 例覆盖
> `layer.bringToFront` / `layer.sendToBack` 的 id 与名字两条解析路径、「既无 id 也无名字」的 400、
> 跨项目图层拒绝、未知图层名列出本项目图层、重名列出候选 id；并断言未知 type 的报错文案里**列全了新增的两个 type**。
>
> **v1.4（2026-09-17）已完成（代码与静态检查层面）**：后端 **`AgentControllerTest` 64 + 
> `AgentCommandServiceTest` 19 = 83 例全通过**（v1.4 新增 17 例 controller、7 例 service）；
> 前端两个执行器均通过 `tsc --noEmit`（website 与 li-analysis-assets 各自干净）。
> 契约与实现见 §4「v1.4 的指令形态」与 §6.2，验收步骤见本节 v1.4 小节。
> ⚠️ **v1.4 的运行时链路未验证**（`_autoCreateLayers` → 自动图层 → `layer.update`；见该小节末尾）。
>
> **未做运行时端到端验证**：按约定服务由用户自行启动，故下列 curl 与浏览器步骤由用户执行。

**功能 01（现在就能验）**
```bash
curl -s "http://localhost:3001/api/projects/<pid>" | jq '.spec.layers | length'
```
期望：能列出图层，字段与 §3.2 一致。

**阶段 0（命令通道）**
```bash
# 下发并等回执（页面需已打开「接入智能体」开关，否则 status 停在 queued）
curl -sX POST "localhost:3001/api/projects/<pid>/agent/commands?wait=true&timeoutMs=8000" \
  -H 'Content-Type: application/json' \
  -d '{"type":"layer.visibility","payload":{"layerId":"<lid>","visible":false}}'
# 读回执（N6）
curl -s "localhost:3001/api/projects/<pid>/agent/commands/<cmdId>/result"
# 排障：忽略 since 看游标（不会抢走指令）
curl -s "localhost:3001/api/projects/<pid>/agent/commands?timeout=0"
# 模拟页面取令（会把指令取走，仅在页面没开时用）
curl -s "localhost:3001/api/projects/<pid>/agent/commands?since=0&timeout=5"
```
期望：`wait=true` 直接返回 `status:"done"` 且 `result.ok:true`；浏览器里图层消失；
**刷新页面后恢复**（验证不落库不变式）。

**阶段 2（目标查询）**
```bash
curl -s "localhost:3001/api/projects/<pid>/agent/targets?datasetId=<did>&key=245272000"
```
期望：`lng/lat` 为数值、`eventTime` 接近当前时间；与该目标历史轨迹接口查到的**最后一条**一致。
⚠️ 库内数据可能滞后数天（Flink 从 Kafka `earliest` 重放），`eventTime` 与「现在」不一致属正常，
模糊模式（`q`）需要据实际数据时段放大 `windowMinutes`。

**v1.2（2026-09-17）**

```bash
# N7 查项目 ID
curl -s "localhost:3001/api/agent/projects?q=船舶" | jq '.rowCount, .projects[].projectName'
curl -s "localhost:3001/api/agent/projects" | jq '.rowCount'          # 不给 q = 按更新时间取前 50

# N8 区域筛选：按预置区域
curl -s "localhost:3001/api/projects/<pid>/agent/targets/in-region?datasetId=<did>&region=bohai" | jq '.filter, .rowCount, .scanned'
# N8 中文区域名 / 手给 bbox / 只要数量
curl -s "localhost:3001/api/projects/<pid>/agent/targets/in-region?datasetId=<did>&region=渤海"
curl -s "localhost:3001/api/projects/<pid>/agent/targets/in-region?datasetId=<did>&bbox=117,37,122.5,41"
curl -s "localhost:3001/api/projects/<pid>/agent/targets/in-region?datasetId=<did>&region=bohai&countOnly=true" | jq '.rowCount, .rows'

# 批量隐藏全部（瓦片应保留）/ 只看指定图层
curl -sX POST "localhost:3001/api/projects/<pid>/agent/commands?wait=true" -H 'Content-Type: application/json' \
  -d '{"type":"layer.visibility","payload":{"scope":"all","visible":false}}'
curl -sX POST "localhost:3001/api/projects/<pid>/agent/commands?wait=true" -H 'Content-Type: application/json' \
  -d '{"type":"layer.isolate","payload":{"layerNames":["船舶实时"]}}'
```
期望：
- N7 返回轻量字段，`q` 支持模糊；不存在的关键词 → `rowCount:0`（200）。
- N8 的 `filter.bbox` 与 N2 查到的同一区域一致；`rows[].key` 能直接喂给 `target.select`；
  `countOnly=true` 时 `rows` 为空、`rowCount` 为命中数。
- 批量隐藏后**底图瓦片仍在**（回执 `detail.skipped` 里有 `reason:'tile'` 的条目）；
  `layer.isolate` 后只剩点名图层 + 瓦片。
- **N8 的过包含**：框内陆地上的目标也会被返回，属已知行为（决策 23），不是缺陷。

**v1.3（2026-09-17）：图层层级**

> **v1.3 已完成**：后端 **4 个测试类共 69 个用例全通过**（`AgentControllerTest` 45 / 其余同上），
> 其中 v1.3 新增 6 例覆盖 `layer.bringToFront` / `layer.sendToBack` 的 id 与名字两条解析路径、
> 「既无 id 也无名字」的 400、跨项目图层拒绝、未知图层名列出本项目图层、重名列出候选 id；
> 并断言未知 type 的报错文案里**列全了新增的两个 type**（防漏改）。

```bash
# 让图标图层盖到最上 / 压到最下（zIndex 由页面算，回执里有 before 与实际写入值）
curl -sX POST "localhost:3001/api/projects/<pid>/agent/commands?wait=true" -H 'Content-Type: application/json' \
  -d '{"type":"layer.bringToFront","payload":{"layerName":"船舶实时"}}' | jq '.result.detail'
curl -sX POST "localhost:3001/api/projects/<pid>/agent/commands?wait=true" -H 'Content-Type: application/json' \
  -d '{"type":"layer.sendToBack","payload":{"layerId":"<lid>"}}' | jq '.result.detail'
```

期望：`detail.zIndex` 为页面实际写入值、`detail.before` 为改前值；
**两个图标图层重叠处，被 `bringToFront` 的那个盖在上面**（这正是本功能要解决的场景）；
`sendToBack` 之后图层**仍可见**（不会被压到瓦片底图下面）。
⚠️ **本功能的运行时效果是本轮读源码得出的结论，未在真实页面上验证**——需要用户在浏览器里实测一次
（`setIndex` 对复合图层是否真的改变叠放次序）；若无效，退路是在 `AgentBridge` 里改为
临时把该图层**重建为顶层复合图层**（成本高，仅在实测失败时考虑）。

**v1.4（2026-09-17）：两步建图层**

> **代码已完成，但运行时链路未验证**（见下）。后端两个指令的校验已有单测覆盖
> （`AgentControllerTest` 64 例，含 17 例新增：列类型别名、列重名、行有未知键、嵌套单元格、行数超限、
> `autoCreateLayer:false`、`layer.update` 保 id 丢 `layerName`、改名与清空备注、至少给一个字段、
> 未知图层、值越界、`parser` 列不在数据集、`parser` 给全 x/y/geometry、半对、`visType` 透传）；
> 取令过滤有 `AgentCommandServiceTest` 覆盖（含「被过滤的指令留在队列里，不被别的能力取走」）。
> **前端两个执行器均已过 TypeScript 检查**（website 与 li-analysis-assets 各自 `tsc --noEmit` 干净）。

```bash
# 第一步：建数据集（经纬度列名必须是 lng/lat 这类可识别的、且 type 为 number）
curl -sX POST "localhost:3001/api/projects/<pid>/agent/commands?wait=true" -H 'Content-Type: application/json' \
  -d '{"type":"dataset.create","payload":{
        "datasetName":"试验航迹",
        "columns":[{"name":"mmsi","type":"string","displayName":"船号"},
                   {"name":"lng","type":"number"},{"name":"lat","type":"number"}],
        "rows":[{"mmsi":"413000001","lng":121.47,"lat":31.23},
                {"mmsi":"413000002","lng":121.52,"lat":31.28}]}}' | jq '.capability, .result.detail'

# 第二步：用回执里的 layerId 改图层
curl -sX POST "localhost:3001/api/projects/<pid>/agent/commands?wait=true" -H 'Content-Type: application/json' \
  -d '{"type":"layer.update","payload":{"layerId":"<lid>","name":"试验航迹","color":"#F86624","size":12}}' \
  | jq '.result.detail'
```

期望：
- 第一步回执 `capability:"config"`、`detail.layers[].layerId` 有值、`rowCount` 等于行数；
  **列名写成 `x`/`y` 或把 lng/lat 声明成 `string` → `layers:[]` + `warning`**（数据集建得成，只是没图层）。
- 第二步回执 `detail.changed` 列出实际改的项，`applied` 是实际写入的值。
- **落库断言**：改完等 1 秒（自动保存 300ms 防抖），`GET /api/projects/<pid>` 里能看到新数据集与改后的图层名
  ——**这是与 02~08 最本质的区别**（那些指令不该出现在项目里）。
- **过滤断言**：Builder 页开着「接入智能体」时，`dataset.create` 由「智能体配置桥」执行；
  在 **Share 页**下发同样的指令，回执**永远 `queued`**（Share 页只取 `runtime`，不碰 config 指令）。

⚠️ **本轮未做运行时验证**（同 §11 开头那份说明）。风险最高的一环是 `waitForAutoCreatedLayers`
的 **3 秒轮询窗口**：数据集查询若慢于此，回执会退化成 warning，而图层随后仍会自己出现。
**这种情况应让用户看页面确认，不要重发指令**（重发会建出重复的数据集）。

**每次都要做的回归**
- 智能体操作后，`GET /api/projects/{id}` 的 `spec.layers` **内容不变**（比对 datasets/layers 数量与 `visConfig.visible`）。
  - **例外**：09（`dataset.create` / `layer.update`）**本来就要改这份内容**——它不在「内容不变」的回归范围内，
    校验它要反过来：改动**必须**在里面。
- 关闭「接入智能体」开关后，页面不再轮询、不再执行任何命令。
- Builder 页上**同时**有两个执行器在轮询（地图侧 + 编辑器侧），各自带 `capabilities`；
  关掉开关后**两个都停**（配置桥读同一个旗标）。

---

## 附录 A：指令类型速查

| type | payload | 说明 |
|---|---|---|
| `layer.visibility` | `{layerId, visible}` | 显隐单个图层（可用 `layerName` 替代 `layerId`） |
| `layer.visibility` | `{scope:'all', visible, keepTiles?}` | 🆕 v1.2 整片显隐；`keepTiles` 默认 true = 瓦片不动 |
| `layer.isolate` | `{layerIds?, layerNames?, keepTiles?}` | 🆕 v1.2 只显示点名的图层；`keepTiles` 默认 true |
| `layer.bringToFront` | `{layerId \| layerName}` | 🆕 v1.3 提到所有业务图层之上（可用于图标图层互相覆盖） |
| `layer.sendToBack` | `{layerId \| layerName}` | 🆕 v1.3 压到所有业务图层之下（仍在瓦片底图之上） |
| `map.focus` | `{mode:'point', lng, lat, zoom?}` | 聚焦到点 |
| `map.focus` | `{mode:'bounds', bbox:[minLng,minLat,maxLng,maxLat], padding?}` | 聚焦到矩形 |
| `map.focus` | `{mode:'region', regionKey}` | 聚焦到区域（后端解析成 bounds） |
| `map.reset` | `{}` | 复位到项目初始视野 |
| `target.select` | `{target:{key,name,lng,lat,payload}, focus?, openPanel?}` | 选中目标：聚焦 + 高亮 + 详情面板 |
| `dataset.create` | `{datasetName, columns:[{name,type,displayName?}], rows:[{列名:值}], layerName?, autoCreateLayer?}` | 🆕 v1.4 建数据集（并让产品自动生成图层）＝建图层的**第一步** |
| `layer.update` | `{layerId \| layerName, name?, description?, visible?, parser?, visType?, color?, size?, opacity?}` | 🆕 v1.4 改图层属性＝**第二步**（`visType` 与 `color/size/opacity` 互斥） |

**按能力分组**（N4 取令的 `capabilities` 值，见 §2「两类执行器」）：

| `capabilities` | 指令 |
|---|---|
| `runtime` | `layer.visibility` / `layer.isolate` / `layer.bringToFront` / `layer.sendToBack` / `map.focus` / `map.reset` / `target.select` |
| `config` | `dataset.create` / `layer.update` |

## 附录 B：给智能体的调用约定

> 这不是接口清单，而是**决策顺序**——告诉智能体先做什么、遇到分歧怎么办。

0. **只知道项目名称、不知道 `projectId`** → 先调 **N7** `GET /api/agent/projects?q=<名称>` 换成 ID（功能 06）。
   返回多条时列出来让用户选，不要猜；`rowCount:0` 说明没有匹配的项目。
1. 「图层」「隐藏」「显示」→ 先调功能 01 把中文名映射成 `layerId`，再下发 `layer.visibility`。
   - 「**只**看 A、B」「其它都关掉」→ 用 `layer.isolate`（一次调用，避免中间态）；
   - 「**全部**隐藏/显示」→ 用批量形态 `{scope:'all', visible}`，**不要**逐层下发——
     批量形态默认保留瓦片图层，逐层下发会把底图也关掉。
   - 「**A 盖住了 B**」「让 A 显示在 B 上面」「两个图标图层重叠看不全」→ 用 `layer.bringToFront`
     （把要露出来的那个提上去）或 `layer.sendToBack`（把该让位的那个压下去）。
     **没有完整排序、也没有「放在 B 之上」这种相对定位**——只有「提到最上 / 压到最下」两个动作。
     要精确控制次序得去编辑器图层面板调，但**图标图层在编辑器里拖动无效**（见 §4 v1.3），
     这类诉求一律用这两条指令解决；做完把两个图层现在的上下关系复述给用户确认。
2. 给出经纬度或「放大到某处」→ 直接 `map.focus` 的 `point` 模式，`zoom` 未指明时用 12。
3. 提到某个**目标**（船名/编号/呼号）→ 先判断是编号还是名称：
   - 像编号（纯数字/固定长度）→ N1 传 `key`；
   - 像名称 → N1 传 `q` 并**必须给 `field`**（名称字段见功能 01 返回的 `datasets[].metadata`，或问用户）；
   - **一次返回多条时必须列出让用户选**，不要猜。
4. 提到**区域**（「渤海」「第一岛链」）→ 先查 N2；**查不到就用你自己的地理知识给 bbox**，走 `bounds` 模式，并在回复里说明所用范围，让用户可纠正。
5. 「这片海域/区域里有哪些目标」「有几条船」→ 用 **N8**：能给区域名就给 `region`（后端查同一本字典），
   字典里没有的（某个锚地、某段航道）自己给 `bbox`；只问数量时加 `countOnly=true`。
   - `truncated:true` 时必须说明结果不完整（`scanned` 已达上限或命中数超过 `limit`），
     可缩小 `windowMinutes`／区域或提高 `limit` 后重试。
   - 拿到 `rows[].key` 后可以继续 `target.select` 聚焦某个目标（N8 的行与 N1 同形，可直接喂给 `target.select` 的 `target`）。
6. 任何控制指令下发后，**向用户确认实际结果**，不要假定成功：
   - 推荐做法：下发时带 `wait=true`，一次调用直接拿到 `result`；
   - 或下发后调 **N6** `GET /agent/commands/{cmdId}/result` 读回执。
   - `status` 长期停在 `queued` = 页面上没打开「接入智能体」开关 → **明确告诉用户去打开开关**，别反复重试。
   - 超时未回执时，响应里的 `hint` 会直说该去哪个页面开开关；**按 `capability` 判断**：
     `config` 类还要求在 **Builder 页**（`runtime` 类在任意挂了「智能体桥」的地图页都行）。
7. **默认你不能修改用户的项目**：02~05、08 都是运行时视图，刷新即恢复；需要持久化时告诉用户去编辑器里改。
   **唯一的例外是 09**（`dataset.create` / `layer.update`）——它**真的会写入项目**，所以：
   - 只在用户明确要求「建图层 / 新建数据集 / 把这个加进项目」时才用；
   - **下发前先向用户说明会写入什么**（数据集名、行数、图层会怎么改）；
   - 用户没在 Builder 页打开开关时**不要绕过**（也不要改用别的接口），直接告诉他去哪申请。
8. **建图层的顺序固定为「先建数据集 → 再改图层」**：
   - 第一步 `dataset.create`，`columns` 里**经纬度列要用 `lng`/`lat` 这类可识别的名字、且 `type:'number'`**
     ——否则产品认不出坐标配对，数据集建得成但**不会生成图层**（回执里给 `warning`，不是报错）；
   - 拿回执里 `layers[].layerId`，再去调 `layer.update` 改名字/样式/可视化类型；
   - 回执 `layers` 为空或带 `warning` 时，**让用户看页面确认，不要重发指令**（重发会建出重复的数据集）；
   - 回执里 `visible:false` 的图层，是**自动生成的图层只有前两个默认可见**造成的。
     要让它**以后一直显示** → 用 `layer.update` 传 `visible:true`（落库）；
     只是**这次看一下** → 用 `layer.visibility`（临时，刷新即恢复）。别用错：`layer.visibility` 不会持久化。

## 附录 C：v1 冻结决策清单

以下条目已在 2026-09-10 定稿，**实现时按此执行，不再讨论**。改动需走 §附录 D 的流程。

### 接口范围

| # | 决策 | 理由 |
|---|---|---|
| 1 | 功能 01 **不新增接口**，用现有 `GET /api/projects/{id}` 的 `spec.layers` | 数据已存在，加接口是冗余 |
| 2 | 目标查询合并为**一个** `GET /agent/targets`（`key` / `q` 两模式），不拆两个 | 返回形状统一，调用方处理逻辑单一 |
| 3 | 新增接口共 **5 个**：N1~N5 | §4 |
| 4 | 控制腿用**长轮询**，不用 WebSocket | 现有 WS 无反向通道；长轮询零新基础设施、可 curl 调试 |
| 5 | 指令**只入内存队列，绝不落库** | 视图操作必须可刷新即恢复 |

### 契约细节

| # | 决策 |
|---|---|
| 6 | `bbox` 顺序固定 `[minLng, minLat, maxLng, maxLat]` |
| 7 | 「未找到」一律返回 **200 + `rowCount:0`**，不用 404（N1、N2 一致） |
| 8 | N1 的 `key` = 目标在 `streamKey` 字段上的**编号**，与本次搜索字段无关 |
| 9 | N1 的 `q` 模式 `field` **必填**（名称字段无法预设默认） |
| 10 | N1 的 `windowMinutes` **默认 60**，模糊模式强制生效 |
| 11 | N4 省略 `since` → **只回游标不回放**（首次连接对齐用） |
| 12 | N4 **多开 = 广播**，不做指令抢占（**运行时**指令均幂等；`dataset.create` 例外 —— 多开会建出重复的数据集） |
| 13 | N4 `timeout` 服务端上限 **30s** |
| 14 | N5 未知 `cmdId` → 404；重复提交幂等覆盖 |
| 15 | `layer.visibility` **支持 `layerName` 替代 `layerId`**，重名/不存在 → 400 + 候选 |
| 16 | `map.focus` 的 `region` 模式**由后端解析成 `bounds`** 后再入队（页面只认 point/bounds） |
| 17 | `layerId` 必须校验属于当前项目 |

### v1.1 追加（2026-09-11，均为**兼容新增**，见附录 D）

| # | 决策 | 理由 |
|---|---|---|
| 18 | 新增 **N6 `GET /agent/commands/{cmdId}/result`** 读回执 | v1.0 只有写回执（N5）没有读路径，附录 B 「确认实际结果」一条与功能 02 的确认步骤都落不了地 |
| 19 | N3 追加可选查询参数 **`wait`**（默认 `false`）与 **`timeoutMs`**（默认 5000，上限 30000） | 让「下发 + 等回执」一次调用完成，省一次往返。不传则行为与 v1.0 完全一致 |
| 20 | **不把 N5 暴露给智能体**（Dify 规范只收 N6） | N5 是页面专用写入端；暴露给智能体等于让它伪造自己的回执，回执的可信度归零 |
| 21 | N6 与 N3 的 `wait` 读的是**同一份回执** | 避免两条确认路径产生分歧 |

### v1.2 追加（2026-09-17，均为**兼容新增**，见附录 D）

| # | 决策 | 理由 |
|---|---|---|
| 22 | **N8 区域筛选做独立端点**，不并进 N1 | 语义不同（「此刻在框里」vs「某目标在哪」），参数与返回都不同；并进 N1 会把 `key`/`q` 二选一变成三选一，调用方更易误用 |
| 23 | 区域判定用**矩形 bbox**，不做多边形 | 与 regions.json 的既有口径一致；多边形要引入边界数据与点在多边形算法，收益不抵复杂度。**过包含**（框内陆地/邻区目标会被算进来）已在 N8 契约里写明 |
| 24 | N8 先定**每个目标的最新位置**再判是否在框内，**不**做「历史进过该区域」的全历史扫描 | 后者要扫全部历史点，是另一个量级的查询；契约里写清语义避免误用 |
| 25 | 批量显隐的 `keepTiles` **默认 true**，语义是**瓦片图层一律不动**（不隐藏也不浮现） | 「隐藏全部图层」不该把底图也关掉、留下空白画布。要连瓦片一起改必须显式传 `false`。瓦片判定放在**页面侧**，后端不复制一份图层类型知识 |
| 26 | 新增 **`layer.isolate`**，不复用 `layer.visibility` 逐层下发 | 「只看 A、B」用单层形态得先列全部图层再逐条隐藏：啰嗦，且在两次调用之间留下中间态（用户会看到图层闪一下） |
| 27 | **不做 `layer.create` / `layer.clear`**，也不写进契约 | 运行时新建的图层**进不了 Builder 的自动保存**（保存的是编辑器 React state；`stateManager.initState` 在 datasets/layers 数组引用变化时还会整体替换 store）。不能持久化的写操作不该写进契约。<br>**v1.4 更新**：其**前提**（「建图层」这件事无解）已由决策 35~40 解决——改走**两步**（建数据集 → 让产品自动生成图层 → 改图层），写进编辑器状态因而可持久化。`layer.create` 作为**单条指令**仍不做（两步已覆盖，且产品推断图层类型比让智能体描述更简单）；`layer.clear` 仍不做（删除图层未纳入本轮范围） |
| 28 | N7 路径挂在 `/api/agent/projects`，**不**在 `/api/projects/{projectId}/agent` 下 | 它是智能体用来问 `projectId` 的入口，不能要求先有 `projectId` |
| 29 | N7 只返回**轻量字段**（`projectId`/`projectName`/`description`/`updateTime`），不返原始实体 | 现有 `GET /api/projects` 含体积很大的 `mapConfig`，让智能体拉全量再自己翻既费 token 又费时间 |

### v1.3 追加（2026-09-17，均为**兼容新增**，见附录 D）

| # | 决策 | 理由 |
|---|---|---|
| 30 | 图层层级**只提供 `layer.bringToFront` / `layer.sendToBack` 两个动作**，不做完整 z 序（无 `layer.order`、不接受绝对 `zIndex`） | 相对次序是**项目配置**（`visConfig.zIndex`，编辑器面板拖动改的就是它）。智能体一次临时改动无法表达整套次序，也不该表达；这两个动作覆盖真实诉求（「让船舶盖住轨迹」） |
| 31 | **`zIndex` 的具体取值由页面侧算**，后端只把 `layerName` 钉成 `layerId`，不下发 `zIndex` | 层清单与「哪些是瓦片层」只有运行页面知道（同决策 25 的分工）；后端算就会把图层类型知识复制一份到后端 |
| 32 | 分配的 zIndex **必须未被占用**，且严格大于全部瓦片图层的 zIndex | L7 渲染列表按 zIndex 升序**稳定排序**，撞值回落到插入顺序 = 等于没改；低于瓦片层则图层会藏进底图下面（表现为「点了没反应」） |
| 33 | 页面侧**双写**：`layersStore.updateLayer(id, {visConfig:{zIndex}})` + 直接 `instance.setIndex(zIndex)` | li 的图层分两类：普通图层（CoreLayer）的 `updateConfig` 会把 `options.zIndex` 应用到 L7，写 store 即有效；**复合图层**（图标/聚合，`CompositeLayer`）的 `update()` 完全不处理 zIndex，只能显式 `setIndex`。只写 store 对图标图层无效——**这就是「两个图标图层永远一个盖住另一个」的根因**（`setIndex` 会把该图层全部子图层设成同一值，故值必须唯一，见决策 32） |
| 34 | 页面侧找不到该图层（不在运行时 store 里）→ 回执 `ok:false` | 与 `layer.isolate` 的 `missing` 同口径：如实回报，不静默成功 |

### v1.4 追加（2026-09-17，均为**兼容新增**，见附录 D）

| # | 决策 | 理由 |
|---|---|---|
| 35 | 建图层做成**两步**：`dataset.create` → `layer.update`，**不做**单条 `layer.create` | li 的图层必须绑数据集；而「数据集 → 图层」的推断链路产品**已经有了**（`_autoCreateLayers` → `autoCreateSchemaHandler`：按列名认坐标配对、产出图层 Schema、追加 LayerPopup 字段、切导航、飞地图）。复用它可以不把图层资产的内部结构写进契约，也不用让智能体重述「图层长什么样」 |
| 36 | `dataset.create` **只收内联数据**（`rows` 直接放在 payload 里），不做文件上传 / 中台表 / URL | 智能体的场景是「用户口述/它自己造的一小批数据」。行数硬顶 5000（超了在错误里指向文件上传），避免把大流量灌进指令通道 |
| 37 | 行数据**先走 `POST /datasets/upload` 落库，成功后才进编辑器状态**；上传失败则整条指令失败 | Builder 的自动保存会**剥掉**数据集里的行数据（`stripDataRowsFromApplication`）。只推状态不上传，刷新后数据集就是空的——那会留下「有图层却查不到数据」的假象 |
| 38 | `layer.update` 是**受控白名单**（`name`/`description`/`visible`/`parser`/`visType`/`color`/`size`/`opacity`），**不放开自由 visConfig**；某图层没有该样式字段就 **400 明确报错**，不静默忽略 | 各图层资产的样式字段名完全不同（`BubbleLayer` 用 `fillColor`/`radius`，`LineLayer` 用 `color`/`size`，`IconLayer` 用 `radius`/`iconStyle.opacity`…），放开写等于把「资产内部结构」变成对外契约，改一个资产就破坏契约。静默忽略则会让智能体以为改成功了 |
| 39 | `visType`（换可视化类型）**与 `color`/`size`/`opacity` 互斥**，同时给 → 400，要求分两条指令 | 样式字段名随资产而变，换完类型旧字段就失效了；同时给会写进一个没人读的键，表现为「改了没反应」 |
| 40 | 指令按**能力分两组**（`runtime` / `config`），N4 取令加 `capabilities` 过滤；**被过滤的指令留在队列里** | 09 起有了两个执行器（地图侧 / Builder 侧）。若都不过滤，Share 页会抢走 `config` 指令然后执行失败。留队列而不是丢弃，是「两个执行器各取各的」能成立的前提 |
| 41 | **`config` 执行器不另设开关**，复用地图侧「接入智能体」开关（`window.__L7VP_AGENT_ENABLED__` + `l7vp-agent-enabled` 事件） | 用户已经在用这个开关表达「授权本页智能体」。再要一个开关只会让人漏开，然后困惑于「为什么指令不执行」 |
| 42 | 「授权旗标」用 **window 全局 + 事件**互通，**不跨包 import**；两侧各存一份常量 | 写入侧在 `packages/li-analysis-assets`，读取侧在 `website`，而 `packages/li-*` 在 website 侧是 `node_modules` 里的**独立拷贝**：跨包 import 一个新增的具名导出，若包没重建会拿到 `undefined` **而不报错**，读侧组件直接白屏。项目里 `window.__L7VP_PROJECT_ID__` 已是同一套路子。代价：改旗标名要改两处（已在两侧注释里互相指明） |
| 43 | `config` 执行器（`AgentConfigBridge`）**只注册进 `editorWidgetsWithBuilder`**，不进共用的 `editorWidgets` | 后者被 `Preview/Template.tsx` 共用，而那两页没有编辑器状态、不该出现「会写项目配置」的执行器 |
| 44 | `AgentConfigBridge` 的 `container` **必须给**（`SideNav`/`bottom`） | `resolveContainerSlotMap` 会**跳过没有 `container` 的控件**，组件永远不渲染、也就无法轮询（授权关闭时返回 `null`，不占位置） |
| 45 | 参数校验不通过的一律 **400**，并在 `error` 里**说清是哪个字段、哪一行**（如 `datasetName` 过长、列重名、行里出现列定义之外的键、单元格是嵌套对象） | 智能体要能凭错误信息自我修正，而不是反复试。「行里出现了列定义之外的键」这条会把**可用列名全部列出** |

### 实现期再定（**已在契约中排除，不影响调用方**）

| # | 待定项 | 何时定 | 为什么可以缓 |
|---|---|---|---|
| A | SSO 鉴权凭证形态（Cookie / Bearer） | 实现阶段 0 前 | 只是 HTTP 头怎么带，路径参数返回均不变 |
| B | N1 模糊搜索走查库还是内存快照 | 实现阶段 1~2 实测后 | 服务端内部查哪张源，契约完全一致 |
| C | `setLayerVisibility` 与编辑器面板的同步表现 | 实现阶段 0 实测（**仍未实测**） | 若不同步则改走 editor 更新路径，对调用方不可见 |
| D | 是否做 `POST /agent/state` 状态上报 | 阶段 4，可选 | 只影响功能 01 的 `visible` 精度，不加也能用 |
| E | `composite.setIndex()` 对**复合图层**（图标/聚合）的实际叠放效果 | v1.3 运行时实测（**仍未实测**） | 契约（两个 `type` + `payload`）不依赖实测结果；若 `setIndex` 无效，只改页面侧实现（重建顶层图层），调用方不可见 |
| F | 两步建图层的**端到端链路**：`_autoCreateLayers` 是否真的触发自动建图层、`waitForAutoCreatedLayers` 的 3 秒窗口够不够、`layer.update` 改完是否随自动保存落库 | v1.4 运行时实测（**仍未实测**） | 契约（两个 `type` + `payload` + 回执形状）不依赖实测结果；若窗口不够，只调大页面侧的等待毫秒数，调用方不可见。**若自动建图层整条不触发**，则要回到「自己造图层 Schema」的方案——那会改实现而不改契约 |

## 附录 D：契约变更流程

接口冻结后，**改契约要同步改五处**，缺一处调用方就会踩坑：

1. 本文 §2 总览表 + §4 对应接口规格；
2. 附录 A 指令类型速查（含**能力分组表**；若涉及指令）；
3. 附录 C 冻结清单（追加一条，注明日期与原因）；
4. 附录 B 智能体调用约定（若改变了智能体的决策顺序）；
5. **Dify 侧三份**（v1.3 起，因为它们各自复制了契约）：
   [`AGENT_MAP_CONTROL_DIFY_TOOL.yaml`](./AGENT_MAP_CONTROL_DIFY_TOOL.yaml)（工具定义，**改了要重新导入 Dify**）、
   [`AGENT_MAP_CONTROL_DIFY_TOOL.md`](./AGENT_MAP_CONTROL_DIFY_TOOL.md)（接入说明）、
   [`AGENT_MAP_CONTROL_DIFY_PROMPT.md`](./AGENT_MAP_CONTROL_DIFY_PROMPT.md)（提示词，含「哪个能力依赖哪几个接口」）。

**只加不删**：新增可选参数/可选字段属于兼容变更；删除字段、改字段语义、改必填性属于破坏性变更，需同时升版本号（v1.1 / v2.0）并在 §2 标注。
