import type { DatasetServiceParams } from '@antv/li-sdk';
import { applyDatasetFilter, parserDataWithGeo } from '@antv/li-sdk';

type QueryDataParams = {
  datasourceId: string;
  resourceName: string;
};

type Params = DatasetServiceParams<QueryDataParams>;

/**
 * 通过后端代理获取中台数据资源（JDBC 直连数据库）
 */
export const getZhongtaiTableData = async (params: Params) => {
  const { properties, filter, signal } = params;

  console.log('[zhongtai-table-dataset] getZhongtaiTableData 被调用, properties:', JSON.stringify(properties));

  const response = await fetch('/api/zhongtai/resources/data/full', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      datasourceId: properties.datasourceId,
      resourceName: properties.resourceName,
    }),
    signal,
  });

  console.log('[zhongtai-table-dataset] fetch 响应状态:', response.status);

  if (!response.ok) {
    const err = await response.json().catch(() => ({}));
    throw new Error(err.error || '中台数据表查询失败');
  }

  const result = await response.json();
  let data: Record<string, any>[] = result.rows || [];

  if (data.length > 0) {
    data = parserDataWithGeo(data);
  }

  // 应用筛选器
  if (filter) {
    data = await applyDatasetFilter(data, filter);
  }

  return data;
};
