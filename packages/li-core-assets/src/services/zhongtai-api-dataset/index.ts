import { implementService } from '@antv/li-sdk';
import { getZhongtaiApiData } from './helper';

export default implementService({
  version: 'v0.1',
  metadata: {
    name: 'GET_ZHONGTAI_API_DATA_LIST',
    displayName: '通过中台 API 获取数据',
    type: 'Dataset',
  },
  service: getZhongtaiApiData,
});
