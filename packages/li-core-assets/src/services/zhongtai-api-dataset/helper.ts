import type { DatasetFilter, DatasetServiceParams } from '@antv/li-sdk';
import { applyDatasetFilter, parserDataWithGeo } from '@antv/li-sdk';

type QueryDataParams = {
  apiUrl: string;
  variableParams?: Record<string, any>;
};

type Params = DatasetServiceParams<QueryDataParams>;

/**
 * 通过后端代理调用中台 API 获取数据
 */
export const getZhongtaiApiData = async (params: Params) => {
  const { properties, filter, signal } = params;

  const response = await fetch('/api/datasource/zhongtai/fetch', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      apiUrl: properties.apiUrl,
      variableParams: properties.variableParams || {},
    }),
    signal,
  });

  if (!response.ok) {
    const err = await response.json().catch(() => ({}));
    throw new Error(err.error || '中台 API 请求失败');
  }

  const result = await response.json();

  // 解析中台 API 返回数据
  let data: Record<string, any>[] = [];
  if (result.rows && Array.isArray(result.rows)) {
    data = result.rows;
  } else if (Array.isArray(result)) {
    data = result;
  }

  if (data.length > 0) {
    data = parserDataWithGeo(data);
  }

  // 应用筛选器
  if (filter) {
    data = await applyDatasetFilter(data, filter);
  }

  return data;
};
