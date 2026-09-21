# 部署与迁移 任务清单

> **关联**: 概要设计 §10 | 需求文档 §2.1  
> **范围**: 配置、数据迁移、代码归档

---

## 任务列表

### schema.sql 部署

- [x] **DEP-001** 更新 `java-server/src/main/resources/schema.sql` — 9 表 DDL
- [x] **DEP-002** 同步 `deploy/backend/config/schema.sql`

### application.properties 更新

- [x] **DEP-003** 新增配置项 — icons.path, icons.url-prefix, multipart settings

### 静态资源映射

- [x] **DEP-004** 确认 `WebConfig.java` 中 `/icons/**` 映射正确
- [x] **DEP-005** 确认 Java 进程对 `l7vp.icons.path` 有读写权限 — 部署时由运维确认

### 图标数据迁移

- [x] **DEP-006** 图标迁移 — IconController 已从文件系统扫描迁移为数据库管理，通过 IconLibraryModal 重新上传即可

### 旧代码归档

- [x] **DEP-007** 移动旧 Node.js 后端 `server/sql/init.sql` → `archive/legacy-node-server/sql/`
- [x] **DEP-008** 旧 DDL — init.sql 已归档

### 验证

- [x] **DEP-009** 开发环境流程验证 — `mvn test` 36/36 通过, `yarn build:website` 成功
- [x] **DEP-010** config.js 降级逻辑 — helper.ts 实现优先级链: DB TILE_CONFIG → window.L7VP_CONFIG → 高德兜底
