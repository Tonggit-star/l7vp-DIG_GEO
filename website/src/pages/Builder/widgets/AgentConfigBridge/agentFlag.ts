/**
 * 「智能体已获授权」旗标的**读取侧**（Builder 页）。
 *
 * <p>旗标本身由**地图侧的「智能体桥」**写入，那份实现在
 * `packages/li-analysis-assets/src/widgets/AgentBridge/agentFlag.ts`（写入侧用 setAgentEnabled）。
 * `packages/li-*` 在 website 侧是 `node_modules` 里的**独立拷贝**，改包后不重建就仍是旧 dist——
 * 那种情况下 `import { readAgentEnabled } from '@antv/li-analysis-assets'` 会拿到 `undefined`
 * 而**不会报错**，直接在组件里炸成白屏。所以这里**不跨包 import**，改为各读各的 window 全局，
 * 与项目里既有的 `window.__L7VP_PROJECT_ID__`（li-editor 写、website 与 li-analysis-assets 各读一次）
 * 是同一套路子。
 *
 * <p>**两侧的字段名与事件名必须一致**：改任一侧都要同步改另一侧
 * （`AGENT_ENABLED_FLAG` / `AGENT_ENABLED_EVENT` 两个常量）。
 *
 * <p>语义：true = 用户已在本页显式打开「接入智能体」开关 → 允许智能体下发会**写入项目配置**的指令。
 */

/** 旗标对应的 window 字段名（与 packages/li-analysis-assets/.../agentFlag.ts 保持一致） */
const AGENT_ENABLED_FLAG = '__L7VP_AGENT_ENABLED__';

/** 旗标变化时派发的 window 事件名（与写入侧保持一致） */
const AGENT_ENABLED_EVENT = 'l7vp-agent-enabled';

/** 读当前授权状态（未设置 / 非 true 一律算未授权） */
export const readAgentEnabled = (): boolean => {
  if (typeof window === 'undefined') return false;
  return (window as any)[AGENT_ENABLED_FLAG] === true;
};

/** 订阅授权状态变化，返回退订函数 */
export const subscribeAgentEnabled = (handler: (enabled: boolean) => void): (() => void) => {
  if (typeof window === 'undefined') return () => {};
  const listener = (event: Event) => {
    handler((event as CustomEvent)?.detail?.enabled === true);
  };
  window.addEventListener(AGENT_ENABLED_EVENT, listener);
  return () => window.removeEventListener(AGENT_ENABLED_EVENT, listener);
};
