# L7VP 功能改造需求文档 V2

> **版本**: v2.0  
> **日期**: 2026-07-08  
> **状态**: 待确认  
> **关联文档**: [proposal.md](./proposal.md)（V1 数据库重构/图标库/瓦片配置/iframe）  
> **本文档**: 在 V1 基础上新增/改动的 7 项需求

---

## 目录

- [1. 需求一：图标"基于字段"改为库号+代号双字段匹配](#1-需求一图标基于字段改为库号代号双字段匹配)
- [2. 需求二：内置经纬度格式转换（点度/度分秒）](#2-需求二内置经纬度格式转换点度度分秒)
- [3. 需求三：中台 API 数据集类型](#3-需求三中台-api-数据集类型)
- [4. 需求四：分析布局侧边栏透明](#4-需求四分析布局侧边栏透明)
- [5. 需求五：8000端口自动跳转 /project](#5-需求五8000端口自动跳转-project)
- [6. 需求六：数据库类数据源（MySQL/Doris + 达梦）](#6-需求六数据库类数据源mysqldoris--达梦)
- [7. 需求七：新增数据库表 DDL](#7-需求七新增数据库表-ddl)
- [8. 数据刷新统一机制](#8-数据刷新统一机制)
- [9. 实施计划](#9-实施计划)
- [10. 确认事项汇总](#10-确认事项汇总)

---

## 1. 需求一：图标"基于字段"改为库号+代号双字段匹配

### 1.1 现状

- ICONS 表已有 `LIBRARY_CODE` + `CODE_NAME` 字段，联合唯一约束已建
- `IconImageLayerStyleAttributeValue` 类型中已预留 `iconLibraryField` / `iconCodeField` / `fallbackIconUrl` 字段，但值为 `undefined`，功能未实现
- 当前"基于字段"模式是选**一个字段**直接对应图标 ID
- 存在 6 个硬编码的内置图标（`BuiltInImageList`：红/黄/绿/蓝旗帜 + 警告 + 默认标记），不在图标库中，无法删除
- 默认图层样式使用 `BuiltInImageList[0]`（红色旗帜）作为默认图标

### 1.2 改造目标

| 项目 | 改造前 | 改造后 |
|------|--------|--------|
| 图标匹配方式 | 选 1 个字段匹配图标 ID | 选 2 个字段分别匹配 `library_code` + `code_name` |
| 内置图标 | 6 个硬编码图标 | **全部删除**，所有图标来自数据库图标库 |
| 默认图标(fallback) | 红色旗帜（内置） | 复用"固定图标"中选中的图标；未配置则不上图 |
| library_code 默认值 | 无（上传后为空） | 取该分类下**出现次数最多**的库号 |
| code_name 默认值 | 无（上传后为空） | 在**同一 library_code** 内最大数字 +1 |
| 手动编辑校验 | 后端抛异常 | `Modal.warning` 弹窗提示重复 |

### 1.3 交互设计

#### 1.3.1 图层样式面板——图标配置

```
┌─────────────────────────────────────────────┐
│  图标类型:  ○ 固定图标    ● 基于字段         │
├─────────────────────────────────────────────┤
│                                             │
│  [固定图标模式]                              │
│  图标形状: [IconSelector 下拉选择]           │
│                                             │
│  [基于字段模式]                              │
│  库号字段:  [FieldSelect 选数据字段]         │
│  代号字段:  [FieldSelect 选数据字段]         │
│  未匹配时:  使用"固定图标"中选中的图标        │
│            （如未选固定图标，则不上图）        │
│                                             │
└─────────────────────────────────────────────┘
```

#### 1.3.2 图标上传默认值规则

```
上传图标到分类 "通信"：
  1. 统计该分类下各 library_code 出现次数
     - "TX": 15个图标
     - "JS": 8个图标
     - "HQ": 3个图标
  2. library_code 默认填 "TX"（最多）
  3. code_name 默认填 "TX" 下的最大数字 +1
     例如 "TX" 下已有 1,2,3,5 → 默认填 6
```

#### 1.3.3 重复校验

```
用户手动修改库号/代号 → 点击保存 → 后端检测 UNQ_ICON_CODE 冲突
  → 前端捕获 409 响应
  → Modal.warning({
       title: '库号+代号重复',
       content: '该分类下已存在 库号="通信"、代号="antenna" 的图标，请修改后重试。',
     })
```

### 1.4 前端改造范围

| 文件 | 改动 |
|------|------|
| `packages/li-p2/.../IconScaleSelector/constant.ts` | **删除** `DEFAULT_ICON_CATEGORY` 和 `BuiltInImageList` 硬编码 |
| `packages/li-p2/.../IconImageLayerStyle/constant.ts` | `DEFAULT_ICON` 不再引用 BuiltInImageList，改为空/null |
| `packages/li-p2/.../IconImageLayerStyle/schema.tsx` | "基于字段"模式改为两个 FieldSelect（库号字段 + 代号字段） |
| `packages/li-p2/.../IconImageLayerStyle/types.ts` | `fallbackIconUrl` 逻辑调整 |
| `packages/li-p2/.../components/IconSelector/index.tsx` | 图标列表不再含有 BuiltInImageList，改为从 API 获取 |
| `packages/li-p2/.../components/IconScaleSelector/index.tsx` | 移除 `DEFAULT_ICON_CATEGORY` 兜底 |
| `packages/li-core-assets/.../IconLayer/` | 图层渲染时处理库号+代号匹配 + fallback 逻辑 |
| `website/src/components/IconLibraryModal/` | 上传默认值填充逻辑 + 重复弹窗 |

### 1.5 后端改造范围

| 位置 | 改动 |
|------|------|
| `IconController.java` | 重复校验返回 409 + 错误信息（JSON） |
| `IconService.java` | 上传时自动填充库号/代号默认值逻辑 |
| 图标查找 API | 已有 `GET /api/icons/lookup?lib=&code=`，图层渲染调用 |

### 1.6 渲染逻辑

```
对每条数据：
  1. 取 库号字段值 和 代号字段值
  2. 调用 GET /api/icons/lookup?lib={库号}&code={代号}
  3. 找到 → 渲染该图标 URL
  4. 未找到 → 检查"固定图标"是否配置
     a. 已配置 → 使用该固定图标
     b. 未配置 → 跳过该数据点（不上图）
```

---

## 2. 需求二：内置经纬度格式转换（点度/度分秒）

### 2.1 现状

- 所有图层 coordinate-schema 中"类型"有 `经纬度`（选经度字段+纬度字段）和 `Geometry`（选空间字段）
- 数据解析在 `geo-parser.ts` 中处理

### 2.2 改造目标

| 项目 | 改造前 | 改造后 |
|------|--------|--------|
| 类型选项 | `经纬度`、`Geometry` | `点度`、`度分秒`、`Geometry`（不变） |
| 点度 | 即原来的 `经纬度`，十进制经纬度 | 改名为"点度"，逻辑不变 |
| 度分秒 | 不支持 | **新增**，一个字段存储度分秒拼接字符串 |
| Geometry | 保留 | 不变 |

### 2.3 度分秒格式定义

#### 存储格式

```
120°10'59.99" → 存储为 "120.105999"

编码规则：DDD.MMSSsss
  - 整数部分 = 度 (120)
  - 小数第1-2位 = 分 (10)
  - 小数第3位及以后 = 秒，含小数部分 (59.99)

位数不固定时补零到小数点后 6 位：
  3°5'9"     → "3.050900"
  120°10'6"  → "120.100600"
```

#### 转换算法

```typescript
/**
 * 度分秒字符串 → 十进制
 * @param dms "120.105999" (120°10'59.99")
 * @returns 120.183331 (十进制)
 */
function dmsToDecimal(dms: string): number {
  const str = dms.padEnd(dms.includes('.') ? dms.length : dms.length + 1, '0');
  const parts = str.split('.');
  const degrees = parseInt(parts[0], 10);
  
  let minutes = 0, seconds = 0;
  if (parts[1]) {
    const fracStr = parts[1].padEnd(6, '0'); // 补零到6位
    minutes = parseInt(fracStr.substring(0, 2), 10);   // 第1-2位
    seconds = parseFloat(fracStr.substring(2));          // 第3位往后
  }
  
  // 校验：分/秒不能 ≥ 60
  if (minutes >= 60 || seconds >= 60) {
    throw new Error('非法度分秒值');
  }
  
  return degrees + minutes / 60 + seconds / 3600;
}
```

#### 脏数据处理

- 分 ≥ 60 或秒 ≥ 60：略过该数据点，**不上图**
- 无法解析的字符串：略过

### 2.4 坐标配置 Schema 变更

每个图层的 `coordinate-schema.ts` 中：

```typescript
// 改造前
enum: [
  { label: '经纬度', value: 'table' },
  { label: 'Geometry', value: 'geometry' },
],

// 改造后
enum: [
  { label: '点度', value: 'table' },       // 原"经纬度"，改名为"点度"
  { label: '度分秒', value: 'dms' },       // 新增
  { label: 'Geometry', value: 'geometry' }, // 不变
],
```

当选择 `dms` 时，只显示**一个字段选择器**（度分秒字段），无需分别选经纬度。

### 2.5 改动范围

| 文件 | 改动 |
|------|------|
| `packages/li-core-assets/src/layers/*/register-form/coordinate-schema.ts` | 7-8 个图层的类型选项同步修改 |
| `packages/li-analysis-assets/src/layers/*/register-form/coordinate-schema.ts` | FlowLayer, ChinaAdminLayer 等 |
| `packages/li-sdk/src/utils/dataset-parser/geo-parser.ts` | 新增 `dmsToDecimal()` + 解析逻辑 |
| `packages/li-sdk/src/specs/dataset.ts` | `DatasetField.type` 可考虑新增 `dms` 类型 |

---

## 3. 需求三：中台 API 数据集类型

### 3.1 现状

- 已有"HTTP 请求"数据集类型（`FetchDataset`），前端直接发 HTTP 请求获取数据
- 没有 token 认证机制，无法直接用于中台 API

### 3.2 改造目标

新增"中台 API"数据集类型。与 HTTP 请求的核心区别：

| | HTTP 请求 | 中台 API |
|------|-----------|----------|
| 请求方式 | 前端直接调 | **后端代理**（需先获取 SSO token） |
| 认证 | 无标准认证 | SSO OAuth2 token |
| Token 管理 | 无 | 后端自动获取 + 缓存（30分钟） |
| 配置项 | URL、method、headers、body | API 完整 URL、variableParams |

### 3.3 中台认证流程

参考文档：[示例2.txt](./数据中台/示例2.txt)

```
┌──────────────────────────────────────────────────────────┐
│                     中台 API 调用流程                      │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  前端                    Java后端              中台系统    │
│   │                        │                     │        │
│   │  请求数据集数据          │                     │        │
│   │──────────────────────→│                     │        │
│   │                        │                     │        │
│   │                        │  1. 检查 token 缓存   │        │
│   │                        │     ├─ 有效 → 直接用  │        │
│   │                        │     └─ 过期 → 重新获取 │        │
│   │                        │                     │        │
│   │                        │  2. POST /sso/oauth2/token
│   │                        │─────────────────────→│        │
│   │                        │  (username, password, │        │
│   │                        │   grant_type,         │        │
│   │                        │   client_id,          │        │
│   │                        │   client_secret)      │        │
│   │                        │←─────────────────────│        │
│   │                        │  { access_token }     │        │
│   │                        │                     │        │
│   │                        │  3. POST /daasDMS/ssoapi/
│   │                        │     ApiDataResource/{code}
│   │                        │  Header: Authorization,    │        │
│   │                        │          spaceId, scopeType│        │
│   │                        │  Body: { rowType,          │        │
│   │                        │          dataServiceParams }│        │
│   │                        │─────────────────────→│        │
│   │                        │←─────────────────────│        │
│   │                        │  { resultCode,        │        │
│   │                        │    columnNames,       │        │
│   │                        │    rows }             │        │
│   │                        │                     │        │
│   │  返回表格数据            │                     │        │
│   │←──────────────────────│                     │        │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### 3.4 配置文件新增（application.properties）

```properties
# ========================================
# 数据中台 SSO 认证配置
# ========================================
zhongtai.sso.url=http://10.16.1.6:8081/sso/oauth2/token
zhongtai.sso.username=sjzt_admin
zhongtai.sso.password=sjzt_admin@4321
zhongtai.sso.grant-type=password
zhongtai.sso.client-id=clientId
zhongtai.sso.client-secret=clientSecret

# 中台 API 默认 Header 参数
zhongtai.api.space-id=default
zhongtai.api.scope-type=Space
# zhongtai.api.tenant-id=           # 项目工作区时启用

# Token 缓存时间（分钟）
zhongtai.token.cache-minutes=30
```

### 3.5 数据集配置（建数据集时填写）

```
新建"中台 API"数据集弹窗：

┌─────────────────────────────────────────────┐
│  新建中台 API 数据集                    [X]   │
├─────────────────────────────────────────────┤
│                                             │
│  数据集名称:  [                    ]        │
│                                             │
│  API 地址:    [                    ]        │
│  (完整 URL，如 http://10.16.1.6:8081/       │
│   daasDMS/ssoapi/ApiDataResource/           │
│   test_1017001)                             │
│                                             │
│  输入参数 (variableParams):                 │
│  ┌─────────────────────────────────────┐    │
│  │  [+ 添加参数]                        │    │
│  │  参数名1: [______]  参数值1: [______] [×]│    │
│  │  参数名2: [______]  参数值2: [______] [×]│    │
│  └─────────────────────────────────────┘    │
│                                             │
│  刷新周期:  [30] 分钟 (0=不自动刷新)          │
│                                             │
│              [取消]    [确定]                │
└─────────────────────────────────────────────┘
```

### 3.6 数据返回格式

使用 `rowType="map"` 模式：

```json
// 请求体
{
  "sqlMode": "normal",
  "rowType": "map",
  "dataServiceParams": {
    "variableParams": {
      "Id": "1"
    }
  }
}

// 返回数据
{
  "resultCode": 200,
  "resultDesc": "OK",
  "columnNames": ["name", "lon", "lat", "value"],
  "rows": [
    { "name": "站点1", "lon": 120.5, "lat": 30.2, "value": 100 },
    { "name": "站点2", "lon": 121.3, "lat": 31.5, "value": 200 }
  ]
}
```

前端将 `rows` 直接作为数据行，`columnNames` 作为列名，构建数据集。

### 3.7 后端新增 API

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/datasource/zhongtai/fetch` | 代理调用中台 API，返回表格数据 |

### 3.8 数据集存储

中台 API 数据集作为 `DATASETS` 表的一条记录：

- `type` = `zhongtai-api`（新增枚举值）
- `metadata` JSON：
  ```json
  {
    "apiUrl": "http://10.16.1.6:8081/daasDMS/ssoapi/ApiDataResource/test_1017001",
    "variableParams": { "Id": "1" },
    "refreshInterval": 30
  }
  ```
- 数据不存入 `DATASET_ROWS`，每次请求实时获取
- 数据不存入 `DATASET_ROWS`，每次请求实时获取

### 3.9 改造范围

| 位置 | 改动 |
|------|------|
| `application.properties` | 新增中台 SSO 配置 |
| `ZhongtaiConfig.java` | 新增配置类，读取中台配置 |
| `ZhongtaiApiController.java` | 新增 Controller，代理中台 API |
| `ZhongtaiApiService.java` | 新增 Service，token 缓存 + API 调用 |
| 前端 `FetchDataset/` 同级 | 新增 `ZhongtaiApiDataset` 组件 |
| `website/src/pages/Builder/editor-widgets.ts` | 注册新数据集类型 |
| `packages/li-sdk/src/specs/dataset.ts` | 新增 `zhongtai-api` 类型 |
| `DATASETS.TYPE` 枚举 | 新增 `zhongtai-api` |

---

## 4. 需求四：分析布局侧边栏透明

### 4.1 现状

`AnalysisLayout` 的侧边栏（`sidePanel`）是 400px 宽的不透明背景（`background-color: ${colorBgLayout}`）。

### 4.2 改造目标

- 侧边栏背景支持半透明，可看到下方的地图
- 侧边栏内的文字、按钮等控件**保持不透明**
- 增加透明度控制滑块
- 默认不透明（opacity = 100%）

### 4.3 实现方式

```css
/* 只让背景透明，控件不受影响 */
sidePanel: css`
  width: 400px;
  padding: 15px 10px;
  overflow: hidden;
  overflow-y: auto;
  background-color: rgba(变理, 变理, 变理, ${opacity / 100});
  backdrop-filter: blur(4px);  /* 可选：背景模糊 */
`
```

### 4.4 透明度控制位置

在布局组件的**属性配置面板**中增加：

```
┌─────────────────────────────┐
│  布局配置                    │
├─────────────────────────────┤
│  显示侧边栏:  [Switch]      │
│  侧边栏透明度: [──────●──] 80% │
│  显示底部面板: [Switch]      │
│  显示浮动面板: [Switch]      │
└─────────────────────────────┘
```

### 4.5 改造范围

| 文件 | 改动 |
|------|------|
| `packages/li-analysis-assets/.../AnalysisLayout/Component/style.ts` | 背景色改为 rgba + opacity 变量 |
| `packages/li-analysis-assets/.../AnalysisLayout/registerForm.ts` | 新增 `sidePanelOpacity` 属性（默认 100） |
| `packages/li-analysis-assets/.../AnalysisLayout/Component/index.tsx` | 读取 opacity 属性应用到样式 |

---

## 5. 需求五：8000端口自动跳转 /project

### 5.1 现状

访问 `http://localhost:8000`（即 `/#/`）显示 Home 首页（功能介绍页）。

### 5.2 改造方案

修改前端路由配置，`/` 自动重定向到 `/project`。

在 [routes.ts](website/config/routes.ts) 中修改：

```typescript
// 改造前
{ path: '/*', redirect: '/' },

// 改造后
{ path: '/*', redirect: '/project' },
```

### 5.3 改造范围

| 文件 | 改动 |
|------|------|
| `website/config/routes.ts` | 将 `redirect: '/'` 改为 `redirect: '/project'` |

---

## 6. 需求六：数据库类数据源（MySQL/Doris + 达梦）

### 6.1 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                    数据库数据源架构                            │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────┐     ┌──────────────────────────────┐    │
│  │  Project 页面     │     │  Builder 页面                 │    │
│  │                  │     │                              │    │
│  │ [数据库配置]按钮   │     │ 新增数据集 → "中台数据库"       │    │
│  │   ↓              │     │   ↓                          │    │
│  │ 弹窗配置连接       │     │ 下拉选连接 → 下拉选表          │    │
│  │ (IP/端口/用户名/   │     │  → 预览前20行                │    │
│  │  密码/类型/模式)   │     │  → 创建数据集                │    │
│  └────────┬────────┘     └──────────────┬───────────────┘    │
│           │                             │                    │
│           │  存储连接配置                 │  读取连接配置        │
│           ▼                             ▼                    │
│  ┌─────────────────────────────────────────────────────┐     │
│  │              DIG_GEO.DB_CONNECTIONS                  │     │
│  │  (连接名/IP/端口/用户名/密码/数据库类型/模式名)         │     │
│  └─────────────────────────────────────────────────────┘     │
│                                                              │
│  数据流：                                                     │
│  前端 ←── Java后端 ──→ Doris/MySQL/达梦 (JDBC 直连)          │
│         (实时查询)                                            │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### 6.2 数据库连接配置

#### 6.2.1 Project 页面入口

在 project 页面右上角按钮区，新增"数据库配置"按钮：

```
┌─────────────────────────────────────────────────────────┐
│  L7VP                           [中台数据库] [图标库]    │
│                                 [瓦片配置] [+新建项目]    │
│  ┌─────────────┐  ┌─────────────┐                      │
│  │  项目卡片1   │  │  项目卡片2   │  ...                 │
│  └─────────────┘  └─────────────┘                      │
└─────────────────────────────────────────────────────────┘
```

#### 6.2.2 配置弹窗

```
┌──────────────────────────────────────────────────────┐
│  中台数据库连接配置                              [X]   │
├──────────────────────────────────────────────────────┤
│                                                      │
│  连接列表 (左侧)        连接详情 (右侧)                 │
│  ┌────────────┐    ┌──────────────────────────────┐  │
│  │ 中台Doris   │    │  连接名称: [中台Doris      ]  │  │
│  │ 达梦业务库   │    │  数据库类型: ○ MySQL  ● 达梦  │  │
│  │            │    │  主机IP:   [10.16.1.6     ]  │  │
│  │            │    │  端口:     [9030          ]  │  │
│  │            │    │  用户名:   [root          ]  │  │
│  │            │    │  密码:     [••••••        ]  │  │
│  │            │    │  模式名:   [test_db       ]  │  │
│  │            │    │                              │  │
│  │            │    │  [测试连接]                    │  │
│  │            │    │                              │  │
│  │            │    │  [删除连接]  [保存]            │  │
│  └────────────┘    └──────────────────────────────┘  │
│                                                      │
│  [+ 新增连接]                                         │
│                                                      │
├──────────────────────────────────────────────────────┤
│                          [关闭]                       │
└──────────────────────────────────────────────────────┘
```

### 6.3 新建"中台数据库"数据集

#### 6.3.1 Builder 页面流程

```
新增数据集 → 选择"中台数据库"类型 →
  ┌──────────────────────────────────────┐
  │  新建中台数据库数据集            [X]   │
  ├──────────────────────────────────────┤
  │                                      │
  │  数据集名称: [___________________]    │
  │                                      │
  │  数据库连接: [▼ 中台Doris        ]    │
  │  数据表:     [▼ table_name       ]    │
  │                                      │
  │  ── 数据预览 (前20行) ──              │
  │  ┌──────────────────────────────┐    │
  │  │ name  │ lon    │ lat  │ val  │    │
  │  │ 站点1  │ 120.5  │ 30.2 │ 100  │    │
  │  │ 站点2  │ 121.3  │ 31.5 │ 200  │    │
  │  │ ...                          │    │
  │  └──────────────────────────────┘    │
  │                                      │
  │  数据行数: 15,000 行                  │
  │                                      │
  │  刷新周期: [10] 分钟 (0=不自动刷新)    │
  │                                      │
  │               [取消]  [创建]          │
  └──────────────────────────────────────┘
```

#### 6.3.2 数据量限制

- 创建时执行 `SELECT COUNT(*) FROM tableName` 探查数据量
- **> 20,000 行**：弹窗提示"数据量超过 2 万行，不支持创建"，**直接禁止**创建
- ≤ 20,000 行：正常创建，`SELECT * FROM tableName` 全表读取

#### 6.3.3 连接测试

- 点击"测试连接"按钮 → 后端执行 `SELECT 1` → 返回成功/失败
- 失败时显示具体错误信息（连接超时、认证失败等）

### 6.4 驱动支持

| 数据库类型 | JDBC 驱动 | 驱动位置 |
|-----------|----------|---------|
| MySQL 协议 (含 Doris) | `mysql-connector-java` | `deploy/backend/lib/` |
| 达梦 DM8 | `dm.jdbc.driver.DmDriver` | 项目已有 |

> Doris 兼容 MySQL 协议，使用同一驱动。连接 Doris 时选"MySQL"类型即可。

### 6.5 数据集存储

中台数据库数据集作为 `DATASETS` 表的一条记录：

- `type` = `database`（新增枚举值）
- `metadata` JSON：
  ```json
  {
    "connectionId": "conn_xxx",
    "schemaName": "test_db",
    "tableName": "site_data",
    "rowCount": 15000,
    "refreshInterval": 10
  }
  ```
- 数据不存入 `DATASET_ROWS`，每次请求实时查询

### 6.6 后端新增 API

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/db-connections` | 获取所有连接配置（密码脱敏） |
| `POST` | `/api/db-connections` | 新增连接配置 |
| `PUT` | `/api/db-connections/{id}` | 更新连接配置 |
| `DELETE` | `/api/db-connections/{id}` | 删除连接配置 |
| `POST` | `/api/db-connections/{id}/test` | 测试连接 |
| `GET` | `/api/db-connections/{id}/tables?schema=xxx` | 获取指定模式下的表列表 |
| `GET` | `/api/db-connections/{id}/tables/{tableName}/preview?schema=xxx` | 预览表数据（前20行） |
| `POST` | `/api/datasource/database/fetch` | 查询数据库表全量数据（用于数据集加载/刷新） |

### 6.7 密码存储

内网部署，密码**明文存储**在 `DB_CONNECTIONS` 表中。

### 6.8 改造范围

| 位置 | 改动 |
|------|------|
| `java-server/` 新增 `DbConnectionController.java` | 连接 CRUD + 测试 + 表列表 + 预览 |
| `java-server/` 新增 `DbConnectionService.java` | JDBC 动态连接管理 |
| `java-server/` 新增 `DatabaseDataSourceService.java` | 执行查询、数据探查 |
| `java-server/` 新增 `model/DbConnection.java` | 连接配置 Model |
| `java-server/` 新增 `repository/DbConnectionRepository.java` | 连接配置持久化 |
| `website/src/components/DbConnectionModal/` | 新增，数据库连接配置弹窗 |
| 前端 `FetchDataset/` 同级 | 新增 `DatabaseDataset` 数据集创建组件 |
| `website/src/pages/Project/index.tsx` | 新增"数据库配置"按钮 |
| `packages/li-sdk/src/specs/dataset.ts` | 新增 `database` 类型 |
| `DATASETS.TYPE` 枚举 | 新增 `database` |
| 新增 DDL | `DIG_GEO.DB_CONNECTIONS` 表 |

---

## 7. 需求七：新增数据库表 DDL

### 7.1 表清单

本次新增以下表：

| # | 表名 | 说明 |
|---|------|------|
| 1 | `DIG_GEO.DB_CONNECTIONS` | 数据库连接配置表 |

### 7.2 DB_CONNECTIONS 表结构

```sql
-- ============================================================
-- 10. DB_CONNECTIONS — 外部数据库连接配置表
-- ============================================================
CREATE TABLE IF NOT EXISTS DIG_GEO.DB_CONNECTIONS (
  CONN_ID       VARCHAR(50)  PRIMARY KEY,
  CONN_NAME     VARCHAR(200) NOT NULL,
  DB_TYPE       VARCHAR(50)  NOT NULL,   -- MySQL / Dameng
  HOST          VARCHAR(200) NOT NULL,
  PORT          INT          NOT NULL,
  USERNAME      VARCHAR(200) NOT NULL,
  PASSWORD      VARCHAR(500) NOT NULL,   -- 明文存储（内网环境）
  SCHEMA_NAME   VARCHAR(200),            -- 模式名
  CREATE_TIME   VARCHAR(50),
  UPDATE_TIME   VARCHAR(50)
);

COMMENT ON TABLE DIG_GEO.DB_CONNECTIONS IS '外部数据库连接配置表';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.CONN_ID IS '连接ID (UUID)';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.CONN_NAME IS '连接名称 (用户自定义)';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.DB_TYPE IS '数据库类型: MySQL / Dameng';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.HOST IS '主机IP地址';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.PORT IS '端口号';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.USERNAME IS '数据库用户名';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.PASSWORD IS '数据库密码 (内网明文)';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.SCHEMA_NAME IS '模式名 (Schema/Database)';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.CREATE_TIME IS '创建时间';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.UPDATE_TIME IS '最后修改时间';
```

### 7.3 DATASETS 表 TYPE 枚举扩展

现有 TYPE 枚举值：`local` / `remote` / `raster-tile` / `vector-tile` / `doris`（预留）

本次新增：

```sql
-- DATASETS.TYPE 字段注释更新（非DDL，仅文档说明）
-- 完整枚举: local / remote / raster-tile / vector-tile / zhongtai-api / database

COMMENT ON COLUMN DIG_GEO.DATASETS.TYPE IS 
  '数据源类型: local / remote / raster-tile / vector-tile / zhongtai-api / database';
```

> 注：达梦 DM8 使用 VARCHAR 存储 TYPE，无需 ALTER TABLE，仅需更新注释。`doris` 预留值不再使用，由 `database` 替代。

### 7.4 ICONS 表改动

```sql
-- 更新注释（非DDL，仅文档说明）
-- CODE_NAME 字段仅允许数字类型（前端校验 + 后端校验）
```

### 7.5 配置文件新增项

```properties
# ========================================
# 数据中台 SSO 认证配置
# ========================================
zhongtai.sso.url=http://10.16.1.6:8081/sso/oauth2/token
zhongtai.sso.username=sjzt_admin
zhongtai.sso.password=sjzt_admin@4321
zhongtai.sso.grant-type=password
zhongtai.sso.client-id=clientId
zhongtai.sso.client-secret=clientSecret

# 中台 API 默认 Header
zhongtai.api.space-id=default
zhongtai.api.scope-type=Space
# zhongtai.api.tenant-id=

# Token 缓存（分钟）
zhongtai.token.cache-minutes=30

# Doris/MySQL 驱动路径（如需动态加载）
# jdbc.driver.mysql.path=deploy/backend/lib/mysql-connector-java.jar
```

---

## 8. 数据刷新统一机制

### 8.1 概述

以下数据集类型支持**定时自动刷新**：

| 数据集类型 | 默认刷新周期 | 可配置 | 刷新方式 |
|-----------|-------------|--------|---------|
| HTTP 请求 (`remote`) | 30 分钟 | ✅ | 前端重新发起 HTTP 请求 |
| 中台 API (`zhongtai-api`) | 30 分钟 | ✅ | 前端调用后端代理 → 后端调中台 API |
| 中台数据库 (`database`) | 10 分钟 | ✅ | 前端调用后端 → 后端 JDBC 查询数据库 |
| 本地数据 (`local`) | 不刷新 | — | 静态数据，无需刷新 |

### 8.2 刷新机制

- **计时起点**：从 Builder 页面打开时统一开始计时，所有数据集共享同一个刷新时钟
- 例如页面在 10:00 打开，第一个刷新周期在 10:10/10:30 触发（取决于各数据集的 refreshInterval）

```
前端 DataSetManager (页面打开时初始化)
  │
  ├── 为每个需要刷新的 dataset 注册定时器
  │
  ├── setInterval(() => {
  │     │
  │     ├── HTTP请求: 直接 fetch(url, options)
  │     │
  │     ├── 中台API:  POST /api/datasource/zhongtai/fetch
  │     │
  │     └── 中台数据库: POST /api/datasource/database/fetch
  │     │
  │     ├── 更新 EditorState 中对应 dataset 的 data
  │     │
  │     └── 所有引用该 dataset 的图层自动重渲染
  │   }, refreshInterval * 60 * 1000)
  │
```

> **注意**：刷新时图层会出现短暂闪烁（数据替换），属正常现象，可接受。

### 8.3 手动刷新

用户可通过**刷新浏览器页面**（F5）手动触发所有数据集重新加载。

---

## 9. 实施计划

### 9.1 分阶段实施

| 阶段 | 内容 | 预估 | 依赖 |
|------|------|------|------|
| **Phase 1** | 需求2：经纬度格式转换（点度/度分秒） | 1天 | — |
| **Phase 2** | 需求5：8000端口跳转 /project | 5分钟 | — |
| **Phase 4** | 需求1：图标库号+代号双字段匹配 + 去除内置图标 | 2天 | — |
| **Phase 5** | 需求3：中台 API 数据集类型（后端代理+token） | 2天 | — |
| **Phase 6** | 需求6：数据库数据源（DB连接配置+中台数据库数据集） | 3天 | — |
| **Phase 7** | 需求4：分析布局侧边栏透明 | 0.5天 | — |
| **Phase 8** | 需求7：DDL + 配置文件整理 | 0.5天 | — |
| **Phase 9** | 数据刷新统一机制（现有HTTP请求+中台API+中台数据库） | 1.5天 | Phase 3,5,6 |

### 9.2 改造文件汇总

```
前端:
  website/config/routes.ts                                     [改] redirect → /project
  website/src/pages/Project/index.tsx                          [改] 新增"数据库配置"按钮
  website/src/pages/Builder/editor-widgets.ts                  [改] 注册 ZhongtaiApi + Database 数据集
  website/src/components/DbConnectionModal/                    [新] 数据库连接配置弹窗
  website/src/components/ZhongtaiApiDataset/                   [新] 中台API数据集创建表单
  website/src/components/DatabaseDataset/                      [新] 中台数据库数据集创建表单
  packages/li-p2/.../IconScaleSelector/constant.ts             [改] 删除 BuiltInImageList
  packages/li-p2/.../IconImageLayerStyle/constant.ts           [改] 默认图标不再引用内置
  packages/li-p2/.../IconImageLayerStyle/schema.tsx            [改] 双字段选择 + 重复弹窗
  packages/li-p2/.../IconImageLayerStyle/types.ts              [改] fallback 逻辑
  packages/li-p2/.../components/IconSelector/index.tsx         [改] 移除 BuiltInImageList
  packages/li-p2/.../components/IconScaleSelector/index.tsx    [改] 移除 DEFAULT_ICON_CATEGORY
  packages/li-core-assets/.../IconLayer/                       [改] 渲染逻辑（库号+代号匹配+fallback）
  packages/li-core-assets/.../*/register-form/coordinate-schema.ts  [改] 7-8个图层的"类型"选项
  packages/li-sdk/src/utils/dataset-parser/geo-parser.ts       [改] 新增 dmsToDecimal()
  packages/li-sdk/src/specs/dataset.ts                         [改] 新增类型 + refreshInterval
  packages/li-analysis-assets/.../AnalysisLayout/Component/style.ts  [改] 侧边栏半透明
  packages/li-analysis-assets/.../AnalysisLayout/registerForm.ts    [改] 新增 opacity 属性
  packages/li-analysis-assets/.../AnalysisLayout/Component/index.tsx [改] opacity 生效
  website/src/components/IconLibraryModal/                     [改] 上传默认值+重复弹窗

后端:
  java-server/.../application.properties                       [改] 新增中台SSO配置
  java-server/.../controller/IconController.java               [改] 409 错误返回
  java-server/.../service/IconService.java                     [改] 上传默认值逻辑
  java-server/.../controller/ZhongtaiApiController.java        [新] 中台API代理
  java-server/.../service/ZhongtaiApiService.java              [新] Token缓存+API调用
  java-server/.../config/ZhongtaiConfig.java                   [新] 配置类
  java-server/.../controller/DbConnectionController.java       [新] 连接CRUD+测试+预览
  java-server/.../service/DbConnectionService.java             [新] JDBC动态连接管理
  java-server/.../service/DatabaseDataSourceService.java       [新] 数据库查询+数据探查
  java-server/.../model/DbConnection.java                      [新] 连接配置Model
  java-server/.../repository/DbConnectionRepository.java       [新] 连接配置Repository
  java-server/.../schema.sql                                   [改] 新增 DB_CONNECTIONS 表

部署:
  deploy/backend/config/application.properties                 [改] 同步新增配置
  deploy/backend/config/schema.sql                             [改] 同步 DDL
  deploy/backend/lib/                                          [已有] mysql-connector + doris 驱动
```

---

## 10. 确认事项汇总

| # | 问题 | 状态 |
|---|------|------|
| 1 | "基于字段"选两个字段（库号+代号），未匹配时用固定图标的图标做 fallback，无固定图标则不上图 | ✅ |
| 2 | library_code 默认值：取该分类下出现最多的库号 | ✅ |
| 3 | code_name 默认值：同一 library_code 内最大数字 +1 | ✅ |
| 4 | code_name / library_code 仅允许数字（前端+后端校验） | ✅ |
| 5 | 重复校验：`Modal.warning` 弹窗 | ✅ |
| 6 | 删除所有硬编码内置图标（`BuiltInImageList`） | ✅ |
| 7 | 度分秒格式：`DDD.MMSSsss`，补零到6位小数 | ✅ |
| 8 | 脏数据（分≥60 或 秒≥60）不上图 | ✅ |
| 9 | "经纬度"改名为"点度"，Geometry 保留不变 | ✅ |
| 10 | 中台 SSO 配置放 application.properties | ✅ |
| 11 | 中台 spaceId/scopeType 放配置文件，tenantId 注释保留 | ✅ |
| 12 | rowType="map"，返回带列名的对象数组 | ✅ |
| 13 | Token 缓存 30 分钟 | ✅ |
| 14 | 中台 API 数据集刷新：默认 30 分钟，可配置 | ✅ |
| 15 | HTTP 请求数据集也增加刷新机制：默认 30 分钟 | ✅ |
| 16 | 侧边栏半透明：背景透明，控件不透明 | ✅ |
| 17 | 透明度滑块在布局属性配置面板中，默认不透明 | ✅ |
| 18 | `/` redirect 到 `/project`（前端路由） | ✅ |
| 19 | 数据库连接配置在 Project 页面弹窗 | ✅ |
| 20 | 数据库连接密码明文存储 | ✅ |
| 21 | 支持 MySQL(Doris) + 达梦 DM8 两种 | ✅ |
| 22 | 全表读取，>2W行直接禁止创建 | ✅ |
| 23 | 创建时预览前 20 行 | ✅ |
| 24 | 中台数据库刷新：默认 10 分钟，每个数据集单独配置 | ✅ |
| 25 | "中台API"和"中台数据库"是两个独立的数据集类型 | ✅ |
| 26 | 数据库数据不存 DATASET_ROWS，只存配置信息 | ✅ |
| 27 | 中台 API 地址：用户在建数据集时手动填完整 URL | ✅ |
| 28 | 刷新计时：从页面打开时统一计时，闪烁可接受 | ✅ |

---

> **下一步**: 请评审本文档，确认后进入详细设计阶段。
