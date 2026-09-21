import { useSyncExternalStore } from 'react';
import { streamDatasetStats } from '../utils/stream-stats';

/**
 * L7_INTEGRATION: 流式数据集实时行数（只读）。
 * 与 useStreamDatasets 维护的前端滑动窗口同源；非流式/无数据写入时稳定为 0。
 * useSyncExternalStore：仅当本数据集计数变化才触发重渲染（getSnapshot 返回原始值，按 Object.is 比较）。
 */
export const useDatasetStreamCount = (datasetId: string): number => {
  return useSyncExternalStore(
    (onStoreChange) => streamDatasetStats.subscribe(onStoreChange),
    () => streamDatasetStats.getCount(datasetId),
    () => streamDatasetStats.getCount(datasetId),
  );
};
