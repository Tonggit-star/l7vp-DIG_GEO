import { implementEditorWidget } from '../../utils';
import ZhongtaiApiDataset from './ZhongtaiApiDataset';

export default implementEditorWidget({
  version: 'v0.1',
  component: ZhongtaiApiDataset,
  metadata: {
    name: 'ZhongtaiApiDataset',
    displayName: '中台 API',
    description: '通过中台 API 获取数据',
  },
  container: {
    type: 'Datasets',
    slot: 'addDataset',
  },
});
