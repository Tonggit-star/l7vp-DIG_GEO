import { implementWidget } from '@antv/li-sdk';
import component from './Component';
import { ICON } from './constants';
import registerForm from './registerForm';

export default implementWidget({
  version: 'v0.1',
  metadata: {
    name: 'AlertNotify',
    displayName: '告警通知',
    description: '地图上的滚动告警面板：紧急置顶、告警靠前，按级别配色，自动轮播',
    type: 'Auto',
    category: 'MapControl',
    icon: ICON,
  },
  defaultProperties: {
    enabled: true,
    position: 'topright',
    title: '实时告警',
    displayCount: 10,
    queueSize: 100,
    width: 320,
    maxHeight: 420,
    intervalSec: 5,
    pauseOnHover: true,
    urgentJumpTop: true,
    showLevelCount: true,
    urgentColor: '#FF4D4F',
    urgentBg: '#FFF1F0',
    warningColor: '#D48806',
    warningBg: '#FFF7CC',
    noticeColor: '#1F1F1F',
    noticeBg: '#FFFFFF',
    simulate: true,
    simulateIntervalSec: 10,
  },
  component,
  registerForm,
});
