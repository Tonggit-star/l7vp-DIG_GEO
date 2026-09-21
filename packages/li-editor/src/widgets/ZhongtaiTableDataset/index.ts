import { implementEditorWidget } from '../../utils';
import ZhongtaiTableDataset from './ZhongtaiTableDataset';

export default implementEditorWidget({
  version: 'v0.1',
  component: ZhongtaiTableDataset,
  metadata: {
    name: 'ZhongtaiTableDataset',
    displayName: '中台数据表',
    description: '从数据中台获取有权限的数据表',
  },
  container: {
    type: 'Datasets',
    slot: 'addDataset',
  },
});
