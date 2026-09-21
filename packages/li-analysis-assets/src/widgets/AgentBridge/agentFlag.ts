/**
 * 「智能体已获授权」的跨包旗标。
 *
 * <p>为什么是 window 全局而不是 import：授权开关在<b>地图侧的「智能体桥」</b>组件里
 * （{@code packages/li-analysis-assets}），而按此授权行事的是 <b>Builder 页的「智能体配置桥」</b>
 * （{@code website}）——两个不同的包，且 website 走 node_modules 里的<b>独立拷贝</b>。
 * 用 window 全局 + 自定义事件互通，与项目里既有的 {@code window.__L7VP_PROJECT_ID__} 是同一套路子，
 * 不引入新的包依赖、也不用担心构建顺序。
 *
 * <p><b>写入侧</b>（{@code AgentBridge/Component.tsx}）在开关变化与组件卸载时调用 {@link setAgentEnabled}；
 * <b>读取侧</b>（{@code website/src/pages/Builder/widgets/AgentConfigBridge}）读 {@link readAgentEnabled}
 * 并用 {@link subscribeAgentEnabled} 订阅变化。<b>两侧共用本模块，别各自复制一份字符串。</b>
 *
 * <p>语义：旗标为 true = 用户已在本页显式打开「接入智能体」开关 → 允许智能体下发会
 * <b>写入项目配置</b>的指令（建数据集/图层、改图层属性）。开关关闭或组件卸载即失效。
 */

/** 旗标对应的 window 字段名 */
export const AGENT_ENABLED_FLAG = '__L7VP_AGENT_ENABLED__';

/** 旗标变化时派发的 window 事件名 */
export const AGENT_ENABLED_EVENT = 'l7vp-agent-enabled';

/** 读当前授权状态（未设置 / 非 true 一律算未授权） */
export const readAgentEnabled = (): boolean => {
  if (typeof window === 'undefined') return false;
  return (window as any)[AGENT_ENABLED_FLAG] === true;
};

/** 写授权状态并广播（写入侧用） */
export const setAgentEnabled = (enabled: boolean): void => {
  if (typeof window === 'undefined') return;
  (window as any)[AGENT_ENABLED_FLAG] = enabled;
  try {
    window.dispatchEvent(new CustomEvent(AGENT_ENABLED_EVENT, { detail: { enabled } }));
  } catch (err) {
    // 极老浏览器没有 CustomEvent 构造器：旗标本身已写入，订阅方首帧也能读到，不影响功能
  }
};

/** 订阅授权状态变化（读取侧用），返回退订函数 */
export const subscribeAgentEnabled = (handler: (enabled: boolean) => void): (() => void) => {
  if (typeof window === 'undefined') return () => {};
  const listener = (event: Event) => {
    handler((event as CustomEvent)?.detail?.enabled === true);
  };
  window.addEventListener(AGENT_ENABLED_EVENT, listener);
  return () => window.removeEventListener(AGENT_ENABLED_EVENT, listener);
};
