import * as Layers from './layers';
import * as Widgets from './widgets';

const widgets = Object.values(Widgets);
const layers = Object.values(Layers);

export * from './widgets';

// 注：授权旗标（`widgets/AgentBridge/agentFlag.ts`）**有意不从包根导出**。
// 它是「写入侧在包内、读取侧在 website」的 window 契约，而 website 用的是 node_modules 里的
// 独立拷贝——跨包 import 一个新增的具名导出，若包没重建会拿到 undefined 而不报错，
// 读侧组件直接白屏。故两侧各读各的 window 全局（见 website/.../AgentConfigBridge/agentFlag.ts）。

export default {
  version: 'v0.1',
  layers,
  widgets,
};
