import { implementEditorWidget } from '../../utils';
import DatabaseDataset from './DatabaseDataset';

export default implementEditorWidget({
  version: 'v0.1',
  component: DatabaseDataset,
  metadata: {
    name: 'DatabaseDataset',
    displayName: '数据库连接',
    description: '通过数据库连接查询数据',
  },
  container: {
    type: 'Datasets',
    slot: 'addDataset',
  },
});
