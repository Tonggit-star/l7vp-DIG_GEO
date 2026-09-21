import { implementService } from '@antv/li-sdk';
import { getDatabaseData } from './helper';

export default implementService({
  version: 'v0.1',
  metadata: {
    name: 'GET_DATABASE_DATA_LIST',
    displayName: '通过数据库查询获取数据',
    type: 'Dataset',
  },
  service: getDatabaseData,
});
