# 数据源连接 / 地图刷新 / 图标转向（2026-09 新增）

本文承接 `CLAUDE.md` 里被压缩掉的三节详细说明。`CLAUDE.md` 已顶到工作区指令注入的字节上限
（超出部分会被截断），故把这三块的细节挪到这里，`CLAUDE.md` 只留一段索引。

---

## 一、数据源连接（达梦 / Doris / MySQL / PostgreSQL / Redis）

支持 **5 种**数据源（2026-09 新增 MySQL / PostgreSQL / Redis）。后端在
`java-server/src/main/java/com/antv/l7vp/service/DbConnectionService.java`，
Redis 的独立实现在同包的 `RedisSourceSupport.java`。
配置存 `DIG_GEO.DB_CONNECTIONS`（DDL 已并入 `schema.sql` 第 10 节）。

### 类型与元信息

- **规范类型名只有 5 个**：`Dameng` / `Doris` / `MySQL` / `PostgreSQL` / `Redis`。
  `normalizeDbType()` 收别名（`pg`/`postgres`/`postgis`→PostgreSQL、`dm`/`dm8`→Dameng、`mariadb`→MySQL），
  认不出就报错并**把可选项列进错误信息**（便于直接指导用户）。
  历史库里把 Doris 存成 `'MySQL'` 的连接仍能连（二者同走 MySQL 协议），只是列表里显示成 MySQL。
- **`GET /api/db-connections/supported-types` 是类型元信息的唯一来源**：类型值、显示名、默认端口、
  「模式名 / 库名 / 库序号」这一栏该怎么叫、以及给用户的**操作提示文案**都在后端定义。
  前端（`website/src/components/DbConnectionModal`、`li-editor/src/widgets/DatabaseDataset`）
  只负责渲染，取不到时各自有一份兜底表。**加类型只需要改后端一处。**

### JDBC 细节

| 类型 | URL 前缀 | 驱动 | 默认端口 |
|---|---|---|---|
| MySQL | `jdbc:mysql://` | `com.mysql.cj.jdbc.Driver` | 3306 |
| Doris | `jdbc:mysql://`（同协议） | `com.mysql.cj.jdbc.Driver` | **9030**（不是 8030） |
| PostgreSQL | `jdbc:postgresql://` | `org.postgresql.Driver` | 5432 |
| Dameng | `jdbc:dm://` | `dm.jdbc.driver.DmDriver` | 5236 |
| Redis | 不走 JDBC | Jedis（RESP） | 6379 |

- MySQL 系连接串固定带 `useInformationSchema=true`，否则取不到字段注释。
- **表名的引号规则各不相同**（`safeTableName`）：达梦与 PostgreSQL 用**双引号**保留大小写，
  MySQL/Doris 用反引号。统一支持「前缀.表名」两段式，各段都必须是纯标识符
  —— **这段校验是拼 SQL 前唯一的防线**。
- **PostgreSQL 的 catalog / schema 是两层，别混**：`schemaName` 字段在 PG 下语义是**数据库名**
  （连接串必须带库名），schema 取默认 `public`。
  `listTables` 刻意用 `getTables(null, null, "%", ...)` 取**全部 schema** 再在代码里筛，
  好处是数据放在非 public 模式时也能选到。筛掉的系统模式：`pg_catalog` / `information_schema` /
  `pg_toast` / **以 `_` 开头** / **以 `timescaledb` 开头**
  （TimescaleDB 的分片在 `_timescaledb_internal`，不筛会刷屏）。
  非 public 的表显示成 `schema.表名`，与上面两段式解析对齐。
- **MySQL/Doris 必须用 catalog 传库名、schemaPattern 传 null**：把库名当 schemaPattern 传给
  Connector/J 会查不到任何表（原实现就是这么写的，属潜在 bug）。

### Redis（不是 JDBC，是另一条平行实现）

只借用同一组接口形状，让前端不必为 Redis 写第二套页面：

| 关系库概念 | Redis 里的对应物 |
|---|---|
| 表列表 | **键族**：抽样 SCAN 后按第一个 `:` 前的公共前缀聚成 `aircraft:*`，附「键数 · 类型」 |
| 表名 | 键的 **glob 模式**（前端用 `mode="tags"` 的自由输入 Select，既可选键族也可手输） |
| 一行 | 一个键（hash / JSON 字符串）或一个成员（list / set / zset） |

- **必须用 SCAN 不能用 KEYS**（KEYS 会阻塞 Redis 主线程）。
- 上限：单次最多扫 20000 键、取 5000 键、list/set/zset 每键最多展开 200 个成员、列数 200；
  超限回 `truncated:true` 提示收窄模式。
- **值还原是成败关键**（`coerceScalar`）：RESP 只给字符串，不还原成数字的话经纬度/航向列会被判成
  `string`，前端**选不出坐标字段**、图上不出点。规则：纯整数→Long、纯小数→Double、`true`/`false`→Boolean；
  **但前导零的整数当编号不转**（mmsi/呼号转了会丢零）。
  注意「**带小数点就不算编号**」—— 否则 `-0.25`、`0.5` 会被误判成字符串
  （这个坑是写单测时当场发现的，见 `RedisSourceSupportTest`）。
- zset 会试 `GEOPOS` 探测是不是 GEO 集合，命中就补 `lng`/`lat` 两列
  （列名刻意对齐前端经纬度自动配对规则）。
- 行内附加 `redis_key` 列（键名）。
- **`schemaName` 在 Redis 下是库序号**（db index 0-15，留空按 0），不是模式名。
- 连接用 **Jedis 4.4.6 直连**（`new Jedis(HostAndPort, JedisClientConfig)`，per-request +
  try-with-resources，与既有「每次 JDBC 取连接」的形态一致）。
  **刻意不引 `spring-boot-starter-data-redis`** —— 那会带自动配置与健康检查，
  而这里只把 Redis 当「按需连接的外部数据源」。
  *注意：Maven 加新依赖后第一次编译要联网（jedis 会带 gson），`mvn -o` 离线模式会失败。*

### 选列与行数上限（`/data` 的两个参数，2026-09 新增）

**为什么必须能选列**：`/data` 是整表查询（`SELECT *`，无 LIMIT）。实测 `ship_latest` 只有 4000 行、
28 列，一点都不大，但其中 `raw_message`（jsonb 原文，约 4.5KB/行）**一个人占了响应体积的 95.6%**，
整表响应 **23.28 MB**。前端那条请求被反复中断——实测发了 7 次才成功一次（前 6 次 `size:0`）——
结果就是**数据集预览一直显示空**、用户以为"没有数据"。排掉该列后：**2.62 MB / 0.21s**（全列是 1.79s）。

> 关键结论：**这类问题是"列太宽"，不是"行太多"。** 所以光加行数上限救不了，必须能选列。

接口：

```
GET /api/db-connections/{id}/tables/{table}/data?columns=a,b,c&limit=N
```

- `columns` 留空 = 全列（`SELECT *`），**完全向后兼容**；历史数据集不带这个参数，行为不变。
- 列名与表名过**同一条标识符白名单**（`^[a-zA-Z0-9_]+$`），在拼 SQL 前统一校验。
  实测 `columns=lat,lon;DROP TABLE x` 被拒：`非法字段名: lon;DROP TABLE x`。
- 列名引号规则与表名一致：达梦 / PostgreSQL 双引号，MySQL / Doris 反引号。
- `limit` 覆盖默认行数上限；默认取 `l7vp.db-connection.max-rows`（**20000**，可用 `L7VP_DB_MAX_ROWS` 覆盖，
  硬顶 200000 —— 请求里要更多也不越过）。实现是**多取一行**来判断是否截断，命中则丢掉落并返回 `truncated:true`。
- 响应额外回显 `selectedColumns` / `maxRows`，便于排查「为什么只有这几列 / 这几行」。

前端（建数据集弹窗 `DatabaseDataset.tsx`）：

- 「预览数据」之后出现**字段勾选表**，每列显示 **整表预估体积**，并给出勾选后的总预估。
  - 单列预估 >1MB 标「大字段」；总计 >2MB 把提示升级成警告语气并说明后果（加载慢、易被中断、数据集显示空）。
  - **预估必须把 key 名开销算进去**：JSON 行对象里每个值都要重复一遍列名，
    27 列 × 约 20 字节 × 4000 行 ≈ 2MB —— 窄列多的时候它比值本身还大，
    漏算会把「排掉大字段之后」的体积低估好几倍。
- 有「全选 / 全不选 / 反选」；预览表格只显示勾选中的列。
- 提交时：**全选则不写 `properties.columns`**（与历史数据集保持完全一致，也免得往项目里塞一长串列名）；
  **部分选择才写入**，随项目保存、`getDatabaseData` 会带上它。

服务层只有一个入口：`DbConnectionService.queryTableWithColumns(conn, table, columns, maxRows)`，
旧的单参重载保留（等价于全列 + 不限行）。Redis 分支忽略 `columns`（它的列是从数据里推出来的）。

**另一个理由**：被中断的请求服务端**照样做完了整表读取与序列化**（先把结果构造完再往外写），
所以重试很贵。把体积压下来不只是让浏览器舒服，也是在省数据库和 JVM 的开销。

### 落库校验与一个已修 bug

- **落库前统一过 `sanitize()`**：类型收敛、必填校验（Redis 之外必须有用户名与库名/模式名）、
  **null 一律收敛成空串**（`USERNAME`/`PASSWORD` 是 NOT NULL）。
- **编辑连接时密码留空 = 不修改**：`updateConnection` 会沿用原口令。
  此前实现是直接把 `''` 写回去，于是「点开连接 → 改个名字 → 保存」就会**静默清空口令**，
  而界面上明明写着「留空不修改」。

### 页面上的操作提示

- `DbConnectionModal`：类型 `Alert`（文案随类型变）、各字段 `tooltip`、
  深色页面上的**明文口令告警**。
- `DatabaseDataset`（建数据集弹窗）：类型提示 + 「Redis 没有表、选的是键模式」的说明；
  Redis 下「数据表」这一栏改叫「键模式」，预览计数显示「共 N 个键」。

---

## 二、地图刷新（按间隔重新从数据源拉数）

需求形态：配好的地图按 1s / 5s / 10s / 1min 等频率自动重新查库、重绘图层。

- **唯一真值是数据集的 `metadata.refreshInterval`（秒）**，不另起计时器：
  两边的数据集查询各自把它转成 react-query 的 `refetchInterval`，每到周期重跑一次数据服务
  → **重新走 HTTP 打后端 → 重新查库**。好处是只存一份配置、随项目落库、不会出现「两套刷新逻辑打架」。
- **两条路径都要认这个字段，缺一条就是「配了没反应」**：
  1. 编辑器侧 `packages/li-editor/src/services/editor-dataset-manager.ts` 的 `getQueryOptions`
     —— Builder 内生效；
  2. **运行时侧 `packages/li-sdk/src/hooks/internal/useRemoteDataset.ts`**
     —— 预览页 `/app/:id`、嵌入页 `/share/:id` 生效。
     **后者是 2026-09 才补上的**：此前运行时不认这个字段，导致「在编辑器里配好了刷新间隔，
     真正看地图的那两页一动不动」。**改这块时两边都要看。**
- `setSchema()` 里对已存在的 observer 调 `setOptions(...)`，所以**改了 `refreshInterval` 是立刻生效的**，
  不需要刷新页面（前提是数据集 schema 是**新对象引用**，`EditorDatasetManager.update` 按引用判等）。
- 运行时侧另设 `refetchIntervalInBackground: true`：大屏/嵌入场景页面常年处于后台标签，
  而 react-query 默认「不可见就暂停轮询」，那样地图恰恰在最需要刷新的时候停下。
- **设置入口有两处**：
  1. Builder SideNav 的「地图刷新」（`website/src/pages/Builder/widgets/MapRefresh/`）——
     预设档位 + 自定义秒数，一次把项目里**全部 `type === 'remote'` 数据集**设成同一个值，
     经 `useEditorState().updateState` 写入 → 触发自动保存落库；
  2. 数据集面板「编辑数据集」里的「刷新间隔」（预设 Select + 自定义，
     档位定义在 `EditDataset/index.tsx` 顶部）。
- **流式数据集（`metadata.stream`）不在其列**：它有 WebSocket 实时推送，轮询是多余的，
  两处入口都按 `type === 'remote'` 过滤。
- 持久化链路无需后端改动：`ApplicationAssembler.fillDatasetFields` 把前端传来的 `metadata`
  合并进 `DATASETS.METADATA`（CLOB），`assemble()` 再解析回来；已验证往返成立。

---

## 三、图标转向（按字段旋转图标）

需求形态：图标在「选字段定经纬度」之外，还能**选一个字段定旋转角**，
让飞机/船舶图标的头指向航向（对齐 Grafana geomap 的 Rotation angle）。

- **面板只存字段名**：`visConfig.iconRotationField`
  （`packages/li-p2/src/LayerAttribute/IconImageLayerStyle/` 的 `schema.tsx` 表单 +
  `helper.ts` 的 flat↔config + `types.ts`）。
  **固定图标与「基于字段」两种模式共用这份配置**（rotation 是独立于 shape 的另一个样式通道）。
  字段候选用**全量 `fieldList`**，**不能复用 `iconFieldList`** ——
  后者被过滤成只剩 `string` 列，而航向列（如 `heading`）是 number，复用会一个都选不出来。
- **角度换算放在渲染前做**，因为归一化要用函数、而项目配置是 JSON 存不下：
  实现在 `packages/li-core-assets/src/layers/icon-rotation.ts`，
  由 `IconLayer/Component.tsx` 与 `ClusterLayer/Component.tsx` 调用（两个图层都支持转向）。
- **`normalizeRotation` 的收敛规则**（不是可选的讲究，是踩过数据得出的）：
  非数值 / 越界 / **360 一律回落到 0**。原因：实测船舶数据里 12.4% 的行 `heading = 360`
  （上游「艏向不可用」的哨兵值），且 L7 的 attribute update 是 `const { rotation = 0 } = feature`，
  **默认值只对 `undefined` 生效、对 null 不生效**，null 会原样写进 Float32 缓冲。
  不收敛这批点会全部渲染成朝正北的假信号。
- 实现要点：给每行补一个归一化后的 `__iconRotation` 列（`ROTATION_DATA_FIELD`），
  再把 `iconStyle.rotation` 设成 `{ field: '__iconRotation' }`
  —— **刻意不传 `value`**，L7 的 `FeatureScalePlugin` 在 `values === undefined` 时
  会选 `ScaleTypes.IDENTITY`（原样透传），正是「数据里是什么角度就转多少度」。
- **图标素材必须「朝正北」为 0 度**；L7 正角度在屏幕上为顺时针，
  与「从正北起顺时针」的航向定义同构，所以航向字段可以直接用、不需要做减法。
  换素材方向反了就改 `icon-rotation.ts` 顶部的 `ARTWORK_OFFSET` / `HEADING_SIGN` 两个常量，
  **不要动换算逻辑**。
- 组件侧还要 **`billboard: false` + 用 `iconRotationField` 参与 React `key`**：
  让改字段时图层重建，模型重建才会重新编译着色器（否则改了不生效）。
- 无前端单测：换算逻辑是纯函数，本可单测，但该包没有前端测试框架
  （同 `AlertNotify` 的处理方式）。
