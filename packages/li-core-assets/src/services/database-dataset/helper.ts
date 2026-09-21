import type { DatasetFilter, DatasetServiceParams } from '@antv/li-sdk';
import { applyDatasetFilter, parserDataWithGeo } from '@antv/li-sdk';

type QueryDataParams = {
  connectionId: string;
  tableName: string;
};

type Params = DatasetServiceParams<QueryDataParams>;

/**
 * 通过后端查询外部数据库表获取数据
 */
export const getDatabaseData = async (params: Params) => {
  const { properties, filter, signal } = params;

  console.log('[database-dataset] getDatabaseData 被调用, properties:', JSON.stringify(properties));

  const response = await fetch(
    `/api/db-connections/${properties.connectionId}/tables/${encodeURIComponent(properties.tableName)}/data`,
    { signal },
  );

  console.log('[database-dataset] fetch 响应状态:', response.status);

  if (!response.ok) {
    const err = await response.json().catch(() => ({}));
    throw new Error(err.error || '数据库查询失败');
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
