import { implementEditorWidget } from '@antv/li-editor';
import Share from './Share';

export default implementEditorWidget({
  version: 'v0.1',
  component: Share,
  metadata: {
    name: 'Share',
    displayName: '共享',
    description: '生成共享链接用于嵌入大屏',
  },
  container: {
    type: 'SideNav',
    slot: 'bottom',
  },
});
