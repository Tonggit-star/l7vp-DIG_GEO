# 前端-Builder 编辑器 任务清单

> **关联**: 概要设计 §6.1-6.3 | 需求文档 §2.6  
> **路径**: `packages/li-editor/src/` + `website/src/pages/Builder/`

---

## 任务列表

### 数据行异步加载

- [x] **FB-001** 改造 `packages/li-sdk/src/specs/dataset.ts` — LocalDatasetSchema 添加 `_lazy`, `_rowCount`
- [x] **FB-002** 改造 Builder `index.tsx` — 新增 `loadLazyRows()` 循环分页加载
- [x] **FB-003** 改造 `website/src/pages/Builder/index.tsx` — useEffect 检测 `_lazy=true` 并异步加载
- [x] **FB-004** DatasetsPanel — 已有分页预览支持（不做大改）

### Excel 上传流程改造

- [x] **FB-005** UploadDataset — 前端解析保持，数据通过保存时持久化（不上传中POST）

### 自动保存改造

- [x] **FB-006** 新增/改造 `packages/li-editor/src/utils/application.ts` — `getApplicationSchemaForSave()` / `stripDataRowsFromApplication()`
- [x] **FB-007** RuntimeApp — 自动保存 debounce 300ms 不变，使用 `stripDataRowsFromApplication` 排除数据行

### 新建项目初始化

- [x] **FB-008** Builder 新建项目 basemap — 已在 helper.ts 使用优先级链（DB → window.L7VP_CONFIG → 高德兜底）
