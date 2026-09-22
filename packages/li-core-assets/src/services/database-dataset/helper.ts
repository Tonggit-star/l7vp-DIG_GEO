import type { DatasetFilter, DatasetServiceParams } from '@antv/li-sdk';
import { applyDatasetFilter, parserDataWithGeo } from '@antv/li-sdk';

type QueryDataParams = {
  connectionId: string;
  tableName: string;
  /**
   * 只取这些列。留空 = 全列（SELECT *）。
   *
   * 宽表务必传：后端是无 LIMIT 的整表查询，一个几 KB 的文本/jsonb 列就足以让响应膨胀十倍以上
   * ——实测 4000 行的一张表因为带了个 4.5KB 的原文列，响应 23MB、前端反复中断，数据集一直显示空。
   */
  columns?: string[];
};

type Params = DatasetServiceParams<QueryDataParams>;

/**
 * 通过后端查询外部数据库表获取数据
 */
export const getDatabaseData = async (params: Params) => {
  const { properties, filter, signal } = params;

  console.log('[database-dataset] getDatabaseData 被调用, properties:', JSON.stringify(properties));

  const columnsQuery =
    properties.columns && properties.columns.length > 0
      ? `?columns=${encodeURIComponent(properties.columns.join(','))}`
      : '';

  const response = await fetch(
    `/api/db-connections/${properties.connectionId}/tables/${encodeURIComponent(properties.tableName)}/data${columnsQuery}`,
    { signal },
  );

  console.log('[database-dataset] fetch 响应状态:', response.status);

  if (!response.ok) {
    const err = await response.json().catch(() => ({}));
    throw new Error(err.error || '数据库查询失败');
  }

  const result = await response.json();
  let data: Record<string, any>[] = result.rows || [];

  // 后端有行数上限；被截断时行数会少于 rowCount，这里明确提示一次，避免误以为「数据就这么多」
  if (result.truncated) {
    console.warn(
      `[database-dataset] 结果被截断：实际返回 ${data.length} 行，表内约 ${result.rowCount} 行，上限 ${result.maxRows} 行`,
    );
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
