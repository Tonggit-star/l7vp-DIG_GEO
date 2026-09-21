import { implementEditorWidget } from '@antv/li-editor';
import AgentConfigBridge from './AgentConfigBridge';

/**
 * 挂 `SideNav/bottom`：编辑器控件**必须**有 container 才会被挂载
 * （`resolveContainerSlotMap` 会跳过没有 container 的控件，组件永远不渲染、也就没法轮询）。
 * 授权关闭时组件返回 null，因此在导航栏底部不占位置。
 */
export default implementEditorWidget({
  version: 'v0.1',
  component: AgentConfigBridge,
  metadata: {
    name: 'AgentConfigBridge',
    displayName: '智能体配置桥',
    description: '按地图侧「接入智能体」开关的授权，执行会写入项目配置的智能体指令（建数据集/图层、改图层属性）',
  },
  container: {
    type: 'SideNav',
    slot: 'bottom',
  },
});
