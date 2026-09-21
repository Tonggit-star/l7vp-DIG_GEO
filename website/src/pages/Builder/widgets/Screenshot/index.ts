import { implementEditorWidget } from '@antv/li-editor';
import Screenshot from './Screenshot';

export default implementEditorWidget({
  version: 'v0.1',
  component: Screenshot,
  metadata: {
    name: 'Screenshot',
    displayName: '截取封面',
    description: '截取当前地图视角作为封面',
  },
  container: {
    type: 'SideNav',
    slot: 'bottom',
  },
});
