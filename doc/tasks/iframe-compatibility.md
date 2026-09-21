# iframe 嵌入兼容 任务清单

> **关联**: 需求文档 §5 | 概要设计 §6.7  
> **原则**: 除 Preview 外，所有功能在同一 tab 内完成

---

## 任务列表

### 排查 window.open 调用

- [x] **IC-001** 全局搜索所有 `window.open(` 调用 — 发现 6 处
- [x] **IC-002** 排查所有 `history.createHref` + `window.open` 组合 — 全部 6 处

### 逐一改造

- [x] **IC-003** 改造 `website/src/pages/Project/index.tsx` 新建项目 — `window.open` → `history.push('/new')`
- [x] **IC-004** 审查 `website/src/pages/Builder/widgets/Preview/` — 使用 `<Link target="_blank">`，保留新 tab 行为
- [x] **IC-005** 审查 Export 功能 — 无 window.open 调用
- [x] **IC-006** 改造其他 window.open:
  - Project/index.tsx:196 (project card preview) → `history.push`
  - Case/index.tsx:56 → `history.push`
  - Home/index.tsx:160 → `history.push`
  - AssetMarket/index.tsx:67 → `history.push`
  - Builder/widgets/Docs/Docs.tsx:19 → `history.push`

### 验证

- [x] **IC-007** 验证无残留 `window.open` — `grep -rn "window.open" website/src/ packages/` 返回空
- [x] **IC-008** iframe 兼容确认 — Preview 保留新 tab，其余全用 `history.push` 同 tab 跳转
