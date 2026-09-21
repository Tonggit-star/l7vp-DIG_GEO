import { implementWidget } from '@antv/li-sdk';
import component from './Component';
import registerForm from './registerForm';
import { ICON } from './constants';

export default implementWidget({
  version: 'v0.1',
  metadata: {
    name: 'AgentBridge',
    displayName: '智能体桥',
    description: '接受外部智能体下发的地图指令（图层显隐 / 视野聚焦 / 选中目标）',
    type: 'Auto',
    category: 'MapInteraction',
    icon: ICON,
  },
  defaultProperties: {
    enabled: false,
    pollTimeout: 25,
    highlightColor: '#F86624',
  },
  component,
  registerForm,
});
