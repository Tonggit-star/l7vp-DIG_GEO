# Dify 自定义工具接入说明（敏捷开发工具）

**配套规范**：[`AGENT_MAP_CONTROL_DIFY_TOOL.yaml`](./AGENT_MAP_CONTROL_DIFY_TOOL.yaml)（OpenAPI 3.0.0）
**接口契约与实现方案**：[`AGENT_MAP_CONTROL_SKILL.md`](./AGENT_MAP_CONTROL_SKILL.md)

本文讲**怎么把这个 YAML 变成 Dify 里能干活的工具**，以及**内置进 Dify 的智能体提示词**。

---

## 一、这个工具集能做什么

| 工具（operationId） | 方法/路径 | 作用 |
|---|---|---|
| `list_projects` | `GET /api/agent/projects` | **一切调用的起点**：项目名模糊检索 → `projectId`（唯一不带 `projectId` 的接口） |
| `inspect_project` | `GET /api/projects/{projectId}` | **拿到 projectId 后的第一步**：读图层清单 + 数据集清单（拿 `layerId` / `datasetId`） |
| `query_targets` | `GET /api/projects/{projectId}/agent/targets` | 按编号（精确）或名称（模糊）查流式目标最新位置 |
| `query_targets_in_region` | `GET /api/projects/{projectId}/agent/targets/in-region` | **区域筛选目标**：区域名或 bbox → 落在区域内的目标（v1.2 新增） |
| `list_regions` | `GET /api/projects/{projectId}/agent/regions` | 区域名 → 包围盒（渤海 / 第一岛链 / 马六甲海峡 …） |
| `send_map_command` | `POST /api/projects/{projectId}/agent/commands` | **下发**地图指令（图层显隐 / 只看某几层 / 图层提到最上或压到最下 / 视野聚焦 / 复位 / 选中目标 / **建数据集** / **改图层**） |
| `get_command_result` | `GET /api/projects/{projectId}/agent/commands/{cmdId}/result` | 读指令的**执行回执**（确认页面真的执行了） |
| `poll_map_commands` | `GET /api/projects/{projectId}/agent/commands` | ⚠️ **仅调试用**：模拟页面取令，看指令有没有真的入队 |

共 8 个操作，`operationId` 唯一，YAML 已用 `js-yaml` 解析校验通过。

**v1.2 的三处变化**（`list_projects` / `query_targets_in_region` / 批量显隐与 `layer.isolate`）对应
用户提的四个需求里的 1、2、3。

**v1.3 的追加（2026-09-17）**：`send_map_command` 的 `type` 增加 **`layer.bringToFront` / `layer.sendToBack`**
（图层提到最上层 / 压到最下层）。**不新增接口**——只是命令通道上多两个 `type`。加它的原因：
两个图标图层叠在一起时永远有一个盖住另一个，而**编辑器图层面板拖动对图标图层无效**
（图标/聚合图层是复合图层，不吃 `zIndex`，只能显式 `setIndex`，详见
`AGENT_MAP_CONTROL_SKILL.md` §4「v1.3 的指令形态」）。只做「最上/最下」两个动作，不做完整 z 序：
图层次序本身是项目配置，临时指令不该表达整套次序。

**v1.4 的追加（2026-09-17）**：需求 4「创建图层的接口」**已实现**，但**不是** `layer.create` 那一条——
改成了**两步**（`send_map_command` 的 `type` 增加 **`dataset.create`** 与 **`layer.update`**）：

1. `dataset.create` 建数据集，**产品自己按列名推断出图层**（`_autoCreateLayers` 触发 li-editor 的自动建图层链路）；
2. 用回执里 `detail.layers[].layerId` 调 `layer.update` 改名字 / 显隐 / 样式 / 可视化类型。

**为什么不做单条 `layer.create`**：运行时直接建的图层只活在浏览器内存里，编辑器下一次自动保存会把它整体抹掉
（`AGENT_MAP_CONTROL_SKILL.md` §6 不变式 ①）。而「走两步」的改动是写进**编辑器状态**的，
随快照一起落库，因此**真的能保存**。这是与 02~08 最本质的区别，也是它必须**只在 Builder 页**执行的原因。

**由此引入的两个契约变化**（都是兼容新增）：

- `send_map_command` 响应多 **`capability`**（`runtime` / `config`）与超时时的 **`hint`**；
- `poll_map_commands` 多可选参数 **`capabilities`**——页面侧有两个执行器（地图侧取 `runtime`、
  Builder 侧取 `config`），靠它各取各的。**智能体一般不用传**（省略 = 两类都取）。

**经纬度列必须用 `lng`/`lat`（或 `lon`/`lat`、`longitude`/`latitude`、`jd`/`wd`）且声明 `type:"number"`**，
否则产品认不出坐标配对：数据集建得成，但**不会生成图层**。回执里会给 `warning` 而不是报错。

### 为什么清单里**没有**回执写入接口

页面上报回执的 `POST /api/projects/{projectId}/agent/commands/{cmdId}/result`（契约文档里的 N5）
**有意不写进 YAML**：它是浏览器页面的专用通道。暴露给智能体 = 让智能体自己写回执再自己读，
`get_command_result` 就彻底失去意义了。**不要手工把它加回来。**

---

## 二、在 Dify 里导入

1. 打开 Dify → 右上角头像 → **工具** → **自定义** → **创建自定义工具**。
2. 填名称（建议 `敏捷开发工具`）→ **Schema** 选择「导入」→ 粘贴 `AGENT_MAP_CONTROL_DIFY_TOOL.yaml` 全文（或直接上传该文件）。
3. **把 `servers` 里的地址改成实际部署地址**（YAML 里预置的是 `http://192.168.10.128:3001`）：
   ```yaml
   servers:
     - url: http://<你的服务器IP>:3001
   ```
   也可以直接在 Dify 工具编辑页的 **Server URL** 里覆盖，不改文件。
4. 保存后逐个工具检查参数是否被正确解析（Dify 会把 path / query / body 参数列出来）。
5. 在智能体（Agent）编排页把这 8 个工具加进去。**建议把 `poll_map_commands` 的授权关掉**
   （它只是排障用，且会抢走页面要取的指令）。

> **鉴权**：后端这些接口不做登录态校验，`LocalUserFilter` 在 `sso` 模式下直接放行，
> **Dify 侧不需要配任何 Header / Cookie**（见第五节「暴露面」）。

---

## 三、给 Dify 智能体的系统提示词

> 🆕 **推荐用新提示词**：[`AGENT_MAP_CONTROL_DIFY_PROMPT.md`](./AGENT_MAP_CONTROL_DIFY_PROMPT.md)（v1.4）。
> 它在这一版的基础上，把「**每个能力要用哪几个接口、按什么顺序调**」显式列成了表——
> 模型最容易犯的错不是调错接口，而是**少调了一步**（上来就下发 `layerName` 却没先 `inspect_project`、
> `query_targets` 查名称却忘了给 `field`）。**下面这版保留备查**，没有依赖标注；
> v1.4 已同步修掉它与新增的 `dataset.create` / `layer.update` 冲突的两处说法
> （原「你不能修改用户的项目」「也不能新建图层」已不再成立），但**依赖标注仍以 PROMPT.md 为准**。

以下内容可直接粘进 Dify 智能体的「指令 / System Prompt」。
它是契约文档附录 B 的落地版，**核心是决策顺序**——告诉模型先做什么、遇到分歧怎么办。

```text
你是地理可视化平台的操作助手。你可以代替用户查看地图上的图层与目标，并控制地图视野和图层显隐。

## 你的两条腿

1. 查询腿（同步返回）：list_projects / inspect_project / query_targets / query_targets_in_region / list_regions
2. 控制腿（异步）：send_map_command 下发 → 页面执行 → get_command_result 读回执

## 铁律

- 控制指令是**异步**的：send_map_command 返回 status:"queued" 只代表「已入队」，**不代表已执行**。
  默认用 wait=true 下发，一次调用就能拿到 result；若用 wait=false，必须随后调 get_command_result 确认。
  **永远不要在没有回执的情况下告诉用户「已经完成」。**
- status 长期停在 queued = 页面上没有开启「接入智能体」开关，或用户根本没打开这个页面。
  这时要**明确告诉用户去页面上打开开关**，而不是反复重试。响应里的 hint 会直说去哪开。
- **两类指令分清**：layer.* / map.* / target.select 只改运行时视图，**刷新页面即恢复**，不碰用户的项目；
  而 dataset.create 与 layer.update **会写入用户的项目**（落库），且**只在 Builder 页**能执行。
  下发后面这两个之前，先向用户说明会写入什么。
- **建图层要两步，没有一步到位的 layer.create**：先 dataset.create 建数据集
  （经纬度列必须用 lng/lat 这类名字且 type 为 number，否则产品推断不出坐标、不会生成图层），
  再用回执里的 layers[].layerId 调 layer.update 改属性。
  回执 layers 为空或带 warning 时，**让用户看页面确认，不要重发**（重发会建出重复的数据集）。
- **仍然不能删除图层**（没有 layer.clear），用户要删图层请他去编辑器操作。
- 一次查到多个目标时必须**列出来让用户选**，不要猜。

## 决策顺序

0. 只知道**项目名称**、不知道 projectId → 先调 list_projects 模糊检索；返回多条时列出来让用户选。
1. 用户提到「图层」「隐藏」「显示」「哪些图层」→ 先调 inspect_project，把中文名映射成 layerId，
   再下发 layer.visibility。也可以直接用 layerName，但你拿不准名字时优先用 layerId。
   - 「**全部图层**都关掉 / 都打开」→ 用批量形态 layer.visibility 传 {scope:"all", visible}，
     **不要逐层下发**：批量形态默认保留瓦片底图（keepTiles 默认 true），逐层会把底图也关掉、只剩空白画布。
   - 「**只保留 / 只看某某几个图层**」→ 用 layer.isolate 传 {layerIds 或 layerNames}。
   - 「**两个图标图层一个盖住另一个 / 拖不动**」→ 用 layer.bringToFront 把要露出来的提上去，
     或 layer.sendToBack 把该让位的压下去。**只有「最上 / 最下」两个动作**，没有完整排序；
     图标图层在编辑器图层面板里拖动是无效的，这类诉求一律用这两条指令解决。
2. 用户给出经纬度，或说「放大到某处」→ 直接 map.focus 的 point 模式；用户没说缩放级时用 12。
3. 用户提到某个**目标**（船名 / 编号 / 呼号 / 飞机）：
   - 先 inspect_project 找到 metadata.stream=true 的流式数据集，取它的 id 和 streamKey；
   - 像编号（纯数字、定长）→ query_targets 传 key；
   - 像名称 → query_targets 传 q **并且必须给 field**（名称列的列名，如 name；不确定就先 inspect_project 看 columns）；
   - 一次返回多条 → 列出让用户选；选定后用 target.select 下发（target 直接用 query_targets 返回的那一行）。
4. 用户提到**区域**（「渤海」「第一岛链」「马六甲」）→ 先调 list_regions；
   查不到就**用你自己的地理知识给出 bbox**，走 map.focus 的 bounds 模式，
   并在回复里说明你用的范围，让用户可以纠正。
5. 「**某某区域里有哪些目标 / 有几条船**」→ query_targets_in_region，区域名用 list_regions 里的 key
   （先查不到再退化成自己给 bbox）。只想知道数量就传 countOnly=true；
   返回里 truncated=true 表示扫描窗口或条数上限被用尽，**要说清结果可能不全**，不要当成全量。
6. 「复位」「回到初始视野」→ map.reset。
7. 用户只是想了解情况（「现在有几条船」「某某船在哪」）→ 只调查询腿，不要下发控制指令。

## 查询目标的注意事项

- rowCount:0 是「没找到」，不是错误，不要重试。
- 模糊查询受 windowMinutes 时间窗限制（默认 60 分钟）。返回空结果时，
  先考虑是不是数据时间窗的问题——可以把 windowMinutes 放大到覆盖实际数据时段（如 7200 = 5 天）。
- 仍然为空就如实告诉用户「该目标当前没有报位数据」，不要编造位置。
```

---

## 四、手工验证（不经过 Dify，先确认后端是通的）

`<pid>` 换成真实项目 ID（用户在平台上打开项目时 URL 里的那段，如 `/builder/79f3431e`）。

```bash
BASE=http://localhost:3001

# 0) 不知道 projectId 时：按项目名检索
curl -s "$BASE/api/agent/projects?q=船舶" | jq -r '.projects[] | [.projectId, .projectName, .updateTime] | @tsv'
curl -s "$BASE/api/agent/projects?limit=5" | jq    # 不传 q = 按更新时间取前 5 个

# 1) 图层清单 + 数据集清单（拿到 projectId 后第一步永远是这个）
curl -s "$BASE/api/projects/<pid>" \
  | jq -r '.spec.layers[] | [.id, .name, .type, (.dataset // .sourceConfig.datasetId), (.visConfig.visible // true)] | @tsv'
curl -s "$BASE/api/projects/<pid>" \
  | jq -r '.datasets[] | [.id, .name, (.metadata.stream // false), (.metadata.streamKey // "-")] | @tsv'

# 2) 目标查询（精确）
curl -s "$BASE/api/projects/<pid>/agent/targets?datasetId=<did>&key=245272000" | jq

# 3) 目标查询（模糊，必须给 field）
curl -s "$BASE/api/projects/<pid>/agent/targets?datasetId=<did>&q=远洋&field=name&windowMinutes=7200" | jq

# 4) 区域字典
curl -s "$BASE/api/projects/<pid>/agent/regions?q=渤海" | jq
curl -s "$BASE/api/projects/<pid>/agent/regions" | jq -r '.regions[].key'   # 全部

# 4b) 区域筛选目标：按区域 key（region= 与 bbox= 互斥，二者必给其一）
curl -s "$BASE/api/projects/<pid>/agent/targets/in-region?datasetId=<did>&region=bohai&windowMinutes=7200" | jq
# 只要数量不要明细：countOnly=true（返回 rowCount 是命中数，rows 为空）
curl -s "$BASE/api/projects/<pid>/agent/targets/in-region?datasetId=<did>&region=bohai&countOnly=true&windowMinutes=7200" | jq
# 也可以直接给矩形：bbox=minLng,minLat,maxLng,maxLat
curl -s "$BASE/api/projects/<pid>/agent/targets/in-region?datasetId=<did>&bbox=117.0,37.0,122.5,41.0&limit=50" | jq
# 看 truncated / scanned：truncated=true 说明扫描窗口(scanLimit，默认 20000 行)或条数上限用尽，结果可能不全

# 5) 下发指令并等回执（wait=true）
curl -s -X POST "$BASE/api/projects/<pid>/agent/commands?wait=true&timeoutMs=8000" \
  -H 'Content-Type: application/json' \
  -d '{"type":"layer.visibility","payload":{"layerName":"船舶实时","visible":false}}' | jq
# 期望：{"cmdId":"c_xxxxxxxx","seq":1,"status":"done","result":{"ok":true,...}}
# 若 status 仍是 "queued" → 页面上没打开「接入智能体」开关

# 5b) 批量：隐藏全部业务图层（keepTiles 默认 true → 瓦片底图不动）
curl -s -X POST "$BASE/api/projects/<pid>/agent/commands?wait=true&timeoutMs=8000" \
  -H 'Content-Type: application/json' \
  -d '{"type":"layer.visibility","payload":{"scope":"all","visible":false}}' | jq
# 回执 detail 里 changed=被改的图层、skipped=因是瓦片被跳过的（reason:"tile"）

# 5c) 只看某几层
curl -s -X POST "$BASE/api/projects/<pid>/agent/commands?wait=true&timeoutMs=8000" \
  -H 'Content-Type: application/json' \
  -d '{"type":"layer.isolate","payload":{"layerNames":["船舶实时"]}}' | jq

# 5d) 图层层级：提到最上 / 压到最下（v1.3）
curl -s -X POST "$BASE/api/projects/<pid>/agent/commands?wait=true&timeoutMs=8000" \
  -H 'Content-Type: application/json' \
  -d '{"type":"layer.bringToFront","payload":{"layerName":"船舶实时"}}' | jq '.result.detail'
# 期望：{"layerId":"layer_abc","layerName":"船舶实时","action":"front","before":0,"zIndex":41}
curl -s -X POST "$BASE/api/projects/<pid>/agent/commands?wait=true&timeoutMs=8000" \
  -H 'Content-Type: application/json' \
  -d '{"type":"layer.sendToBack","payload":{"layerId":"<lid>"}}' | jq '.result.detail'
# zIndex 由页面侧算；sendToBack 的值严格大于瓦片层，图层不会消失

# 5e) 建图层第一步：建数据集（v1.4，会写入项目！只在 Builder 页有效）
curl -s -X POST "$BASE/api/projects/<pid>/agent/commands?wait=true&timeoutMs=15000" \
  -H 'Content-Type: application/json' \
  -d '{"type":"dataset.create","payload":{
        "datasetName":"试验航迹",
        "columns":[{"name":"mmsi","type":"string","displayName":"船号"},
                   {"name":"lng","type":"number"},{"name":"lat","type":"number"}],
        "rows":[{"mmsi":"413000001","lng":121.47,"lat":31.23},
                {"mmsi":"413000002","lng":121.52,"lat":31.28}]}}' | jq '.capability, .result.detail'
# 期望：capability="config"；detail.layers[].layerId 有值（拿它做下一步）
# 列名写成 x/y、或把 lng/lat 声明成 string → detail.layers 为空 + warning（数据集建得成，只是没图层）

# 5f) 建图层第二步：用上一步的 layerId 改图层（同样只在 Builder 页有效）
curl -s -X POST "$BASE/api/projects/<pid>/agent/commands?wait=true&timeoutMs=8000" \
  -H 'Content-Type: application/json' \
  -d '{"type":"layer.update","payload":{"layerId":"<lid>","name":"试验航迹","color":"#F86624","size":12}}' \
  | jq '.result.detail'
# 期望：detail.changed 列出实际改的项、applied 是实际写入值
# 等约 1 秒（自动保存 300ms 防抖）后 GET /api/projects/<pid>，应能看到新数据集与改后的图层名
# —— 这一条与 02~08 相反：那些指令不该出现在项目里，这两个**应该**出现

# 6) 读回执
curl -s "$BASE/api/projects/<pid>/agent/commands/<cmdId>/result" | jq

# 7) 排障：指令真的入队了吗（省略 since 只看游标，不会抢走指令）
curl -s "$BASE/api/projects/<pid>/agent/commands?timeout=0" | jq
# 只看某一类执行器的取令（v1.4）：capabilities=runtime|config，省略=两类都取
curl -s "$BASE/api/projects/<pid>/agent/commands?timeout=0&capabilities=config" | jq
```

**浏览器侧的硬前提**：目标页面（`/builder/:id`）上必须有一个「智能体桥」组件，
并且用户**手动打开**了组件面板上的「接入智能体」开关。
这是 §6 约束 2 的设计——页面默认不接受任何外部控制。开关未打开时指令会一直积压为 `queued`。

**v1.4 起再加一条**：`dataset.create` / `layer.update` 这两个**会写入项目**的指令，
**只由 Builder 页的「智能体配置桥」执行**（它跟地图侧的桥共用那一个开关，**没有单独的开关**）。
所以在 Share 嵌入页/预览页下发它们会一直 `queued`——**那不是开关没开**，是那个页面没有这条通路。
排障时先看响应的 `capability` 字段。

---

## 五、注意事项与暴露面

1. **数据可能滞后**：Kafka 从 `earliest` 重放，库内数据可能落后当前时间数天。
   实测库内数据到 2026-09-07 而当天是 09-11。所以「近 1 小时」这类**相对当前时间**的查询常常为空，
   应据实际数据时段放大 `windowMinutes`。这也是 `query_targets` 把该参数写进工具描述的原因。
2. **`visible` 不是运行时真值**：`inspect_project` 返回的 `visConfig.visible` 是**上次在编辑器里保存的值**。
   智能体通过命令通道改显隐只动浏览器内存、不落库，所以这个字段会与用户眼前所见脱节。
   要确认当前实际状态，以 `send_map_command` 的**回执**为准。
3. **无鉴权 = 谁能访问 3001 谁就能控制**：`/api/projects/{id}` 与 `/agent/*` 都不校验登录态，
   CORS 也是 `allowedOrigins("*")`。命令的最终生效还受页面开关约束（用户不开启则不执行），
   但**图层与数据集清单、目标位置是可被匿名读取的**。
   生产上应把 3001 限制在内网/白名单内，或由 nginx 统一加鉴权后再对 Dify 暴露。
   ⚠️ **v1.4 起这一条的严重性上升**：`dataset.create` / `layer.update` **会真的往项目里写东西**
   （数据集 + 图层）。虽然仍然要求 Builder 页开着开关，但一旦开着，
   **能访问 3001 的人/程序就能改项目配置**——不再只是「看看」。生产部署务必收紧到内网/白名单。
4. **限流**：每项目每分钟最多 120 条指令，超出返回 `429`。指令队列上限 100 条，超出丢最旧；
   回执表 LRU 保留 200 条，被挤出的 `cmdId` 查回执返回 `404`。
5. **多开页面 = 广播**：同一项目的多个打开页面会**同时**执行同一条指令（不抢占）。
   **运行时指令**是幂等的视图操作，所以表现一致，但不要指望「只有一个页面会响应」。
   ⚠️ **配置指令（`dataset.create` / `layer.update`）不是幂等的**：两个 Builder 页同时开着开关，
   `dataset.create` 会各执行一次、**建出重复的数据集与图层**。下发前先确认只有一个 Builder 页在跑，
   且回执不明时不要重发。（与第「别重发」那条是同一个道理，见 PROMPT.md。）
6. **队列只在内存**：后端重启，未执行的指令与全部回执都会丢失。这是设计如此（视图操作必须可刷新即恢复）。

---

## 六、改动与维护

接口契约的改动流程见 [`AGENT_MAP_CONTROL_SKILL.md`](./AGENT_MAP_CONTROL_SKILL.md) 附录 D
（§2 总览表 + §4 规格 + 附录 A + 附录 C + 附录 B 五处，**外加 Dify 侧三份** = 本文件、YAML、PROMPT.md；只加不删）。

**改了接口之后，本 YAML 也必须同步改**，否则 Dify 里的工具会调出 400/404。
改完 Dify 侧要**重新导入/更新 Schema**（Dify 不会自动感知后端变化），
并同步更新 [`AGENT_MAP_CONTROL_DIFY_PROMPT.md`](./AGENT_MAP_CONTROL_DIFY_PROMPT.md) 里的依赖表与提示词
（工具增删或参数变化会让提示词教模型调错东西）。

YAML 当前版本：`1.4.0`（8 个 operationId 不变；`send_map_command` 的 `type` 枚举 9 个值、
响应多 `capability`/`hint`；`poll_map_commands` 多可选参数 `capabilities`；
`servers.url` 由 `localhost:3003` 修正为 `192.168.10.128:3001`——原来那个端口是错的）。

自检一行命令（需 `js-yaml`）：

```bash
node -e "const y=require('js-yaml'),f=require('fs');const d=y.load(f.readFileSync('doc/AGENT_MAP_CONTROL_DIFY_TOOL.yaml','utf8'));console.log(Object.keys(d.paths).length,'paths', Object.values(d.paths).flatMap(i=>Object.values(i).map(o=>o.operationId)).join(', '))"
```

期望输出：`7 paths list_projects, inspect_project, query_targets, query_targets_in_region, list_regions, send_map_command, poll_map_commands, get_command_result`

（打印的 `7` 是**路径数**，`/agent/commands` 一条路径挂 GET+POST 两个操作，所以 operationId 有 8 个。
顺序与文件里的书写顺序一致；认名字对不对即可。）
