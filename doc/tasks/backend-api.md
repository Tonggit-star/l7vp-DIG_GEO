# 后端 API 层 任务清单

> **关联**: 概要设计 §7  
> **范围**: Controller 层、WebConfig、application.properties  
> **路径**: `java-server/src/main/java/com/antv/l7vp/`

---

## 任务列表

### ProjectController 改造

- [x] **BA-001** 改造 `GET /api/projects` — 列表接口
- [x] **BA-002** 改造 `GET /api/projects/{id}` — 返回组装后的完整 Application JSON
- [x] **BA-003** 改造 `POST /api/projects` — 接收 Application JSON，事务写入多表
- [x] **BA-004** 改造 `PUT /api/projects/{id}` — 增量更新多表
- [x] **BA-005** 改造 `DELETE /api/projects/{id}` — 级联删除
- [x] **BA-006** 保留 `PUT /api/projects/{id}/thumbnail`

### DatasetController 新增

- [x] **BA-007** 新增 `POST /api/projects/{id}/datasets/upload`
- [x] **BA-008** 新增 `GET /api/projects/{id}/datasets/{dsId}/rows?page=0&size=500`
- [x] **BA-009** 新增 `DELETE /api/projects/{id}/datasets/{dsId}`

### IconController 重写

- [x] **BA-010** 重写 `GET /api/icons/categories`
- [x] **BA-011** 新增 `POST /api/icons/categories`
- [x] **BA-012** 新增 `PUT /api/icons/categories/{id}`
- [x] **BA-013** 新增 `DELETE /api/icons/categories/{id}`
- [x] **BA-014** 新增 `PUT /api/icons/categories/reorder`
- [x] **BA-015** 重写 `GET /api/icons?categoryId={id}`
- [x] **BA-016** 新增 `POST /api/icons/upload`
- [x] **BA-017** 新增 `PUT /api/icons/{id}`
- [x] **BA-018** 新增 `DELETE /api/icons/{id}`
- [x] **BA-019** 新增 `PUT /api/icons/reorder`
- [x] **BA-020** 新增 `GET /api/icons/lookup?lib=&code=`
- [x] **BA-021** 保留兼容 `GET /api/icons`（无参数）

### TileConfigController 新增

- [x] **BA-022** 新增 `GET /api/tile-config`
- [x] **BA-023** 新增 `PUT /api/tile-config`

### 配置与静态资源

- [x] **BA-024** WebConfig 图标静态资源映射（已有）
- [x] **BA-025** 更新 `application.properties`

### 错误处理

- [x] **BA-026** 统一异常处理 — 基本 try-catch
- [x] **BA-027** 唯一约束冲突处理 — Service 层校验
