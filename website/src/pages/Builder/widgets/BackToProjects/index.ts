import { implementEditorWidget } from '@antv/li-editor';
import BackToProjects from './BackToProjects';

export default implementEditorWidget({
  version: 'v0.1',
  component: BackToProjects,
  metadata: {
    name: 'BackToProjects',
    displayName: '返回项目列表',
    description: '返回项目管理页面',
  },
  container: {
    type: 'SideNav',
    slot: 'bottom',
  },
});
