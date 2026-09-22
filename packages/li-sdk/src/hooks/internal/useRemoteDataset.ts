import type { QueryFunctionContext } from '@tanstack/react-query';
import { useQuery } from '@tanstack/react-query';
import { isArray, isUndefined } from 'lodash-es';
import { useMemo } from 'react';
import type { DatasetField, DatasetFilter, RemoteDatasetSchema } from '../../specs';
import type { DatasetServiceParams, ImplementService, RemoteDataset } from '../../types';
import { getDatasetColumns, getValidFilterWithMeta, queryServiceClient } from '../../utils';
import { useRegistryManager } from './useRegistryManager';

const mergeColumns = (columns: DatasetField[], originColumns?: DatasetField[]) => {
  if (!originColumns) {
    return columns;
  }

  const originColumnsMap = new Map<string, DatasetField>();
  originColumns.forEach((column) => {
    originColumnsMap.set(column.name, column);
  });

  const mergedColumns = columns.map((column) => {
    const originColumn = originColumnsMap.get(column.name);
    if (originColumn) {
      return {
        ...originColumn,
        type: column.type,
      };
    }
    return column;
  });

  return mergedColumns;
};

export const NOOP_REMOTE_DATASET: RemoteDatasetSchema = {
  id: 'noop',
  type: 'remote',
  serviceType: 'noop',
  properties: {},
  metadata: { name: 'noop' },
};

const NOOP_SERVICE: ImplementService = {
  version: 'noop',
  metadata: { name: 'noop', displayName: 'noop', type: 'Dataset' },
  service: () => Promise.resolve([]),
};

export function useRemoteDataset(datasetSchema: RemoteDatasetSchema, pickFilter?: DatasetFilter) {
  const registryManager = useRegistryManager();
  const serviceType = datasetSchema.serviceType;
  const implementService: ImplementService<[DatasetServiceParams], Record<string, any>[]> =
    serviceType === NOOP_REMOTE_DATASET.serviceType ? NOOP_SERVICE : registryManager.getService(serviceType);
  const service = implementService.service;

  const filter = useMemo(() => {
    const _columns = datasetSchema.columns || [];
    const _filter = pickFilter || datasetSchema.filter;
    if (isUndefined(_filter)) return _filter;

    return getValidFilterWithMeta(_filter, _columns);
  }, [datasetSchema.filter, pickFilter, datasetSchema.columns]);
  const datasetProperties = datasetSchema.properties;

  // const params: Omit<DatasetServiceParams, 'signal'> = useMemoDeep(() => ({
  //   properties: datasetProperties,
  //   filter,
  // }));
  // 防抖
  // const [debouncedParams, setDebouncedParams] = useState<Omit<DatasetServiceParams, 'signal'>>(params);
  // useThrottleEffect(() => setDebouncedParams(params), [params], { wait: 100 });

  const queryFn = (context: QueryFunctionContext) => {
    const serviceParams: DatasetServiceParams = { filter, properties: datasetProperties, signal: context.signal };
    return service(serviceParams);
  };

  // 刷新周期（秒）：>0 时启用轮询，每到周期重跑一次 queryFn，即重新从数据源拉最新数据。
  //
  // 预览页(/app)与嵌入页(/share)走的就是这条路径 —— 编辑器侧（li-editor 的 editor-dataset-manager）
  // 早就认 metadata.refreshInterval 了，但**运行时不认**的话，「在编辑器里配好的刷新间隔」
  // 在真正看地图的那两页是不生效的，表现为「设置明明保存了却不动」。
  const refreshIntervalSec = Number((datasetSchema.metadata as any)?.refreshInterval) || 0;
  const polling = refreshIntervalSec > 0;

  const { data } = useQuery(
    {
      queryKey: [implementService.metadata.name, filter, datasetProperties],
      queryFn,
      placeholderData: [],
      refetchInterval: polling ? refreshIntervalSec * 1000 : false,
      // 半周期内视为新鲜，避免同一份数据被重复请求
      staleTime: polling ? (refreshIntervalSec / 2) * 1000 : 0,
      // 大屏/嵌入场景页面常年处于后台标签，而 react-query 默认「页面不可见就暂停轮询」，
      // 那样地图恰恰在最需要它刷新的时候停下，所以这里显式打开。
      refetchIntervalInBackground: true,
    },
    queryServiceClient,
  );

  const dataset: RemoteDataset = useMemo(() => {
    const columns = isArray(data) ? mergeColumns(getDatasetColumns(data), datasetSchema.columns) : [];
    return {
      ...datasetSchema,
      data: isArray(data) ? data : [],
      columns,
    };
  }, [datasetSchema, data]);

  return dataset;
}
