# L7VP Vibe Coding Prompt

> **角色**: 主 Agent（Orchestrator）  
> **目标**: 全自动完成 L7VP 功能改造，零人工参与  
> **输入**: 需求文档 + 概要设计 + 任务清单  
> **输出**: 可运行的完整代码 + 全部单元测试通过

---

## 一、系统上下文

### 1.1 项目概述

L7VP 是一个地理可视化平台（Geo-Visualization Platform），技术栈：

| 层 | 技术 | 
|---|------|
| 前端框架 | React 18 + TypeScript + Umi 4 |
| UI 组件库 | antd 5.x |
| 状态管理 | EditorState (immer + Subscribable) |
| 拖拽 | @hello-pangea/dnd |
| 前端构建 | Lerna monorepo + Father + yarn |
| 后端框架 | Java Spring Boot 2.7.14 |
| 数据库访问 | Spring JdbcTemplate |
| 数据库 | 达梦 DM8 |
| 文件存储 | 服务器本地文件系统 |

### 1.2 改造目标（四大需求）

1. **数据库重新设计** — Schema `L7VP_PROJECTS` → `DIG_GEO`，单表拆为 9 张表，解决大数据量保存失败
2. **图标库可视化管理** — 弹窗形式上传/删除/分类/排序图标，支持按库号+代号匹配
3. **瓦片地址可配置** — 全局 XYZ 瓦片 URL 配置弹窗，持久化到数据库
4. **iframe 嵌入兼容** — 除 Preview 外所有功能同 tab 跳转

### 1.3 关键文档

| 文档 | 路径 |
|------|------|
| 需求文档 | [doc/proposal.md](./proposal.md) |
| 概要设计 | [doc/high-level-design.md](./high-level-design.md) |
| 任务清单目录 | [doc/tasks/](./tasks/) |
| 总体进度 | [doc/tasks/progress.md](./tasks/progress.md) |

---

## 二、主 Agent 工作流程

### 2.1 核心职责

主 Agent 是整个改造工程的**唯一协调者**，负责：

1. **读取并理解**需求文档和概要设计
2. **按阶段顺序**派发子 Agent 执行各模块
3. **验证**每个子 Agent 的产出（代码 + 测试通过）
4. **更新** `doc/tasks/progress.md` 进度
5. **处理**模块间依赖和集成问题

### 2.2 执行流程

```
Phase 1: 数据库重建
  └─ 派发子Agent → 执行 database.md 全部任务
  └─ 验证: schema.sql 语法正确，DDL 完整

Phase 2: 后端核心 + API
  └─ 派发子Agent → 执行 backend-core.md 全部任务
  └─ 派发子Agent → 执行 backend-api.md 全部任务
  └─ 验证: mvn test 全部通过

Phase 3: 前端核心适配
  └─ 派发子Agent → 执行 frontend-project-page.md
  └─ 派发子Agent → 执行 frontend-builder.md
  └─ 验证: yarn test 全部通过

Phase 4: 图标库
  └─ 派发子Agent → 执行 frontend-icon-library.md
  └─ 派发子Agent → 执行 frontend-layer-icon.md
  └─ 验证: yarn test 全部通过

Phase 5: 瓦片配置
  └─ 派发子Agent → 执行 frontend-tile-config.md
  └─ 验证: yarn test 全部通过

Phase 6: iframe 兼容（可并行）
  └─ 派发子Agent → 执行 iframe-compatibility.md
  └─ 验证: yarn test + grep 确认无残留 window.open

Phase 7: 部署归档
  └─ 派发子Agent → 执行 deployment.md
  └─ 验证: 端到端流程确认
```

### 2.3 依赖约束

```
Phase 1 (数据库) ──→ Phase 2 (后端) ──→ Phase 3/4/5 (前端)
                                            │
Phase 6 (iframe) ──→ 可与 Phase 2-5 并行 ──┘
                                            │
Phase 7 (部署)   ──→ 所有 Phase 完成后 ────┘
```

- Phase 1 必须先完成（所有 DDL 就绪）
- Phase 2 必须在 Phase 3/4/5 之前（前端依赖 API）
- Phase 6 可独立并行执行
- Phase 7 最后执行

---

## 三、子 Agent 规范

### 3.1 每个子 Agent 的输入

主 Agent 派发子 Agent 时，必须传递：

```
任务: <模块名称>
目标: <一句话目标>
任务清单: <对应的 doc/tasks/<module>.md 完整内容>
技术约束:
  - Java 测试: JUnit 5 + Mockito，纯 Mock（无真实数据库）
  - 前端测试: Jest + React Testing Library
  - 必须阅读现有代码，保持代码风格一致
  - 必须确保现有功能不受影响（回归安全）
相关文档:
  - doc/proposal.md §<相关章节>
  - doc/high-level-design.md §<相关章节>
```

### 3.2 每个子 Agent 的产出要求

子 Agent 完成后，**必须**产出以下全部内容：

| 产出 | 说明 |
|------|------|
| ✅ **实现代码** | 新增/改造的源文件，代码风格与现有代码一致 |
| ✅ **单元测试** | 每个公开方法 ≥1 个测试用例，覆盖率 ≥80% |
| ✅ **测试通过** | `mvn test` 或 `yarn test` 该模块测试全部绿色 |
| ✅ **回归安全** | 现有测试（如有）仍然通过，不影响已有功能 |
| ✅ **完成报告** | 列出修改的文件清单，每个文件做了什么，测试覆盖了哪些场景 |

### 3.3 测试要求细则

#### Java 测试 (JUnit 5 + Mockito)

```
要求:
- 所有 Service 类方法 ≥1 个 @Test
- 所有 Repository 类方法 ≥1 个 @Test
- 所有 Controller 类端点 ≥1 个 @MockMvc 测试
- ApplicationAssembler 每个转换方法 ≥3 个测试（正常/空值/边界）
- 使用 @ExtendWith(MockitoExtension.class)
- Repository 测试: Mock JdbcTemplate，验证 SQL 和参数
- Controller 测试: @WebMvcTest + MockMvc
- 测试类命名: {ClassName}Test.java
- 测试方法命名: should_{预期行为}_when_{条件}
```

#### 前端测试 (Jest + React Testing Library)

```
要求:
- 每个组件 ≥1 个 render 测试
- 每个自定义 Hook ≥2 个测试（正常/异常）
- 每个 API service 函数 ≥1 个测试（mock fetch/axios）
- 弹窗组件: 测试打开/关闭/表单交互/提交
- 使用 jest.mock() mock API 调用
- 使用 @testing-library/react 渲染和断言
- 测试文件命名: {ComponentName}.test.tsx
- 测试文件位置: 与源文件同目录或在 __tests__/ 下
```

### 3.4 子 Agent 产物验证

主 Agent 收到子 Agent 完成报告后，必须执行：

1. **编译验证**
   ```bash
   # Java
   cd java-server && mvn compile
   # 前端
   yarn build:package  # 或对应包构建
   ```

2. **测试验证**
   ```bash
   # Java
   cd java-server && mvn test
   # 前端
   npx jest <模块路径> --coverage
   ```

3. **回归验证** — 确认全量测试依然通过

4. **进度更新** — 勾选 `doc/tasks/<module>.md` 中已完成的任务，更新 `doc/tasks/progress.md`

---

## 四、模块派发顺序与细节

### 4.1 Phase 1: 数据库重建

**派发: 1 个子 Agent**  
**任务文件**: [doc/tasks/database.md](./tasks/database.md)  
**目标**: 编写完整的 `schema.sql`（9 张表 + 索引 + 约束）

关键产出:
- `java-server/src/main/resources/schema.sql` 完整 DDL
- 验证达梦 DM8 语法兼容

### 4.2 Phase 2: 后端核心层 + API 层

**派发: 2 个子 Agent**（可部分并行）

#### Agent 2A: 后端核心层
**任务文件**: [doc/tasks/backend-core.md](./tasks/backend-core.md)  
**目标**: Model + Repository + Service + ApplicationAssembler

关键产出:
- 9 个 Model 类
- 9 个 Repository 类（JdbcTemplate + RowMapper）
- 7 个 Service 类
- 1 个 ApplicationAssembler（核心转换器，含 assemble() 和 disassemble()）
- **全部单元测试**（JUnit 5 + Mockito）

#### Agent 2B: 后端 API 层
**任务文件**: [doc/tasks/backend-api.md](./tasks/backend-api.md)  
**目标**: Controller + WebConfig + application.properties  
**依赖**: Agent 2A 完成

关键产出:
- 3 个 Controller（Project/Dataset/Icon/TileConfig）
- WebConfig 静态资源映射
- application.properties 更新
- **全部单元测试**（@WebMvcTest + MockMvc）

### 4.3 Phase 3: 前端核心适配

**派发: 2 个子 Agent**（可部分并行）

#### Agent 3A: 前端-项目管理页面
**任务文件**: [doc/tasks/frontend-project-page.md](./tasks/frontend-project-page.md)  
**目标**: 项目列表页按钮 + API 适配

#### Agent 3B: 前端-Builder 编辑器
**任务文件**: [doc/tasks/frontend-builder.md](./tasks/frontend-builder.md)  
**目标**: 数据行异步加载 + Excel 上传改造 + 自动保存脱数据

### 4.4 Phase 4: 图标库

**派发: 2 个子 Agent**

#### Agent 4A: 前端-图标库管理
**任务文件**: [doc/tasks/frontend-icon-library.md](./tasks/frontend-icon-library.md)  
**目标**: IconLibraryModal 完整弹窗（CategoryPanel + IconGrid + IconCard + UploadProgress）

#### Agent 4B: 前端-图层图标匹配
**任务文件**: [doc/tasks/frontend-layer-icon.md](./tasks/frontend-layer-icon.md)  
**目标**: 图标图层支持按数据字段 (库号, 代号) → 图标 URL 匹配

### 4.5 Phase 5: 瓦片配置

**派发: 1 个子 Agent**  
**任务文件**: [doc/tasks/frontend-tile-config.md](./tasks/frontend-tile-config.md)  
**目标**: TileConfigModal + API service + 瓦片加载优先级链

### 4.6 Phase 6: iframe 兼容

**派发: 1 个子 Agent**  
**任务文件**: [doc/tasks/iframe-compatibility.md](./tasks/iframe-compatibility.md)  
**目标**: 全局排查并替换 window.open → navigate()（Preview 除外）

### 4.7 Phase 7: 部署归档

**派发: 1 个子 Agent**  
**任务文件**: [doc/tasks/deployment.md](./tasks/deployment.md)  
**目标**: 配置更新 + 图标迁移 + 旧代码归档 + 端到端验证

---

## 五、进度追踪协议

### 5.1 进度文件

主 Agent 维护以下文件的实时状态：

- **`doc/tasks/progress.md`** — 模块级 checklist（已完成/进行中/未开始）
- **`doc/tasks/<module>.md`** — 子任务级 checklist（`[ ]` → `[x]`）

### 5.2 更新规则

| 事件 | 操作 |
|------|------|
| 子 Agent 开始执行某模块 | 该模块状态 → "🔄 进行中" |
| 子 Agent 完成并验证通过 | 该模块状态 → "✅ 已完成"，全部子任务勾选 `[x]` |
| 子 Agent 失败 | 该模块状态 → "❌ 失败"，记录失败原因 |
| 整个 Phase 完成 | Phase checklist 勾选 `[x]` |

### 5.3 进度更新命令

主 Agent 在以下时机更新进度：
1. **派发前**: 标记模块为 "🔄 进行中"
2. **验证后**: 标记模块为 "✅ 已完成" 或 "❌ 失败"
3. **每个 Phase 结束时**: 汇总更新 progress.md 统计表

---

## 六、关键设计决策（子 Agent 必须遵守）

### 6.1 ApplicationAssembler — 核心转换器

这是整个改造的**最关键模块**。子 Agent 实现时必须：

```
assemble(projectId):
  输入: projectId
  输出: Application JSON（前端兼容格式）
  local datasets: data=[], _lazy=true, _rowCount=<来自metadata>

disassemble(projectId, json):
  输入: Application JSON（data 已排除）
  输出: void（写入各表）
  对比策略: 基于 ID 集合运算 → toInsert / toUpdate / toDelete
  不操作 DATASET_ROWS
```

### 6.2 数据行处理（独立于 Assembler）

- 上传时: 单事务写入 DATASETS + COLUMNS + ROWS（JDBC batch, 500条/批）
- 查询时: 分页 API `GET /rows?page=0&size=500`
- 保存时: 自动保存**不包含**数据行

### 6.3 图标存储

- 文件: UUID 重命名 → `{l7vp.icons.path}/{categoryId}/{uuid}.{ext}`
- 元数据: ICON_CATEGORIES + ICONS 表
- URL: `/icons/{categoryId}/{uuid}.{ext}`
- 唯一约束: `(category_id, library_code, code_name)`

### 6.4 瓦片配置优先级链

```
数据库 TILE_CONFIG (最优先)
  ↓ 无则
config.js window.L7VP_CONFIG (兜底)
  ↓ 无则
herlper.ts 硬编码高德 URL (最终兜底)
```

### 6.5 iframe 兼容原则

| 场景 | 行为 |
|------|------|
| Preview 预览 | ✅ 保留 `window.open` 新开 tab |
| 新建项目 | 🔧 `window.open` → `navigate('/new')` |
| 其他所有跳转 | 🔧 同 tab 跳转或 Modal/Drawer |

---

## 七、常见陷阱与注意事项

### 7.1 达梦 DM8 特殊性

- 达梦 SQL 语法与 MySQL/Oracle 有差异
- `CREATE SCHEMA IF NOT EXISTS` — 确认达梦兼容写法
- 自增主键策略: 本项目使用 VARCHAR UUID，避免达梦自增差异
- `MERGE INTO` 语法与 Oracle 类似但可能有细微差异
- CLOB 类型: 使用 `setCharacterStream` / `getString`

### 7.2 前端兼容性

- 现有代码期望接收完整 `Application` JSON → Assembler 组装时保证结构兼容
- 自动保存 debounce 300ms 不变
- Excel 解析复用现有 `xlsx` 库逻辑，只改上传方式
- `@hello-pangea/dnd` 已引入，图标库拖拽排序直接复用

### 7.3 测试陷阱

- **不要**试图启动真实达梦数据库做测试
- Repository 测试: Mock `JdbcTemplate`，用 `ArgumentCaptor` 验证 SQL
- Controller 测试: 使用 `@WebMvcTest`，Mock Service 层
- 前端测试: Mock 所有 API 调用，用 `waitFor` 处理异步渲染
- ApplicationAssembler 测试: 构造假数据覆盖 assemble/disassemble 各项分支

---

## 八、主 Agent 工作指令（完整 Prompt）

> **以下为主 Agent 的完整操作指令。主 Agent 应逐阶段执行，每阶段确认通过后再进入下一阶段。**

---

### Step 0: 环境准备

```
1. 确认 java-server/ 可编译 (mvn compile)
2. 确认 frontend 可构建 (yarn install && yarn build:package)
3. 确认 doc/tasks/ 目录及所有任务文件存在
4. 初始化 progress.md 状态
```

### Step 1: Phase 1 — 数据库重建

```
派发 1 个子 Agent:
  输入: doc/tasks/database.md 全部内容 + doc/proposal.md §2 + doc/high-level-design.md §8
  目标: 编写完整 schema.sql（9 张表 DDL + 索引 + 约束）
  产出: java-server/src/main/resources/schema.sql
  验证: 
    - DDL 语法兼容达梦 DM8
    - 9 张表、全部索引、唯一约束、外键完整
    - deploy/backend/config/schema.sql 同步更新
  确认后: 更新 doc/tasks/database.md 勾选全部，progress.md 标记 M1 完成
```

### Step 2: Phase 2 — 后端核心 + API

```
Step 2A: 先派发 1 个子 Agent 执行 backend-core.md
  输入: doc/tasks/backend-core.md + proposal.md §2 + high-level-design.md §5
  产出: 9 Model + 9 Repository + 7 Service + 1 ApplicationAssembler + 全部单元测试
  验证: cd java-server && mvn test → 全部通过
  确认后: 更新 progress.md

Step 2B: 再派发 1 个子 Agent 执行 backend-api.md（依赖 2A 完成）
  输入: doc/tasks/backend-api.md + high-level-design.md §7
  产出: 4 Controller + WebConfig + application.properties + 全部单元测试
  验证: cd java-server && mvn test → 全部通过
  确认后: 更新 progress.md，标记 M2+M3 完成
```

### Step 3: Phase 3 — 前端核心适配

```
并行派发 2 个子 Agent:
  Agent 3A: doc/tasks/frontend-project-page.md
  Agent 3B: doc/tasks/frontend-builder.md
  
  验证: npx jest --passWithNoTests → 全部通过
  确认后: 更新 progress.md，标记 M6+M7 完成
```

### Step 4: Phase 4 — 图标库

```
先派发 Agent 4A (frontend-icon-library.md)
验证通过后派发 Agent 4B (frontend-layer-icon.md)

验证: npx jest --passWithNoTests → 全部通过
确认后: 更新 progress.md，标记 M4+M8 完成
```

### Step 5: Phase 5 — 瓦片配置

```
派发 1 个子 Agent 执行 frontend-tile-config.md
验证: npx jest --passWithNoTests → 全部通过
确认后: 更新 progress.md，标记 M5 完成
```

### Step 6: Phase 6 — iframe 兼容

```
派发 1 个子 Agent 执行 iframe-compatibility.md
验证: 
  - grep -rn "window.open" website/src/ packages/ | grep -v Preview → 无输出
  - yarn build:website → 成功
确认后: 更新 progress.md，标记 M9 完成
```

### Step 7: Phase 7 — 部署归档

```
派发 1 个子 Agent 执行 deployment.md
验证: 
  - archive/ 目录存在
  - schema.sql 在正确位置
  - application.properties 已更新
确认后: 更新 progress.md，标记 M10 完成
```

### Step 8: 最终验证

```
1. cd java-server && mvn test → 全部通过
2. npx jest --passWithNoTests → 全部通过
3. yarn build:website → 构建成功
4. 检查 progress.md 全部模块 ✅
5. 输出最终完成报告
```

---

## 九、最终完成标准

| 检查项 | 标准 |
|--------|------|
| 数据库 | schema.sql 含 9 张表 DDL，可在达梦 DM8 上执行 |
| 后端编译 | `mvn compile` 零错误 |
| 后端测试 | `mvn test` 全部通过，覆盖率 ≥80% |
| 前端编译 | `yarn build:package && yarn build:website` 零错误 |
| 前端测试 | `npx jest` 全部通过，覆盖率 ≥80% |
| 代码风格 | 与现有代码一致（命名、注释密度、模式） |
| 回归 | 现有功能不受影响 |
| 进度 | progress.md 全部 10 个模块 ✅ |

---

> **主 Agent 就绪。开始从 Phase 1 执行。**
