import { implementService } from '@antv/li-sdk';
import { getZhongtaiTableData } from './helper';

export default implementService({
  version: 'v0.1',
  metadata: {
    name: 'GET_ZHONGTAI_TABLE_DATA_LIST',
    displayName: '通过中台数据表获取数据',
    type: 'Dataset',
  },
  service: getZhongtaiTableData,
});
