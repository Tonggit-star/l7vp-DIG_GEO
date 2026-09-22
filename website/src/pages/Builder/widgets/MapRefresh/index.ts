import { implementEditorWidget } from '@antv/li-editor';
import MapRefresh from './MapRefresh';

export default implementEditorWidget({
  version: 'v0.1',
  component: MapRefresh,
  metadata: {
    name: 'MapRefresh',
    displayName: '地图刷新',
    description: '设置地图按固定频率重新从数据源拉取数据',
  },
  container: {
    type: 'SideNav',
    slot: 'bottom',
  },
});
