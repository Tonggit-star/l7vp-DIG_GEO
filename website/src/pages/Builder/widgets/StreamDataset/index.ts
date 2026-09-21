import { implementEditorWidget } from '@antv/li-editor';
import StreamDataset from './StreamDataset';

export default implementEditorWidget({
  version: 'v0.1',
  component: StreamDataset,
  metadata: {
    name: 'StreamDataset',
    displayName: '流式数据',
    description: '通过 WebSocket 接入 Flink 实时流式数据',
  },
  container: {
    type: 'Datasets',
    slot: 'addDataset',
  },
});
