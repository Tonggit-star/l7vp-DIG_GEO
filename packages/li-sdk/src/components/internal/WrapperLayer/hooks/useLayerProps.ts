import { omit } from 'lodash-es';
import { useEffect, useMemo, useState } from 'react';
import { useDataset } from '../../../../hooks/useDataset';
import type { LayerSchema, LayerSourceConfig } from '../../../../specs';
import type { Dataset } from '../../../../types';
import { isLocalOrRemoteDataset } from '../../../../utils';
import { dmsToDecimal } from '../../../../utils/dataset-parser/geo-parser';

/**
 * 为数据行预填充图标 URL（基于库号+代号查找）
 */
async function enrichIconUrls(
  data: Record<string, any>[],
  visConfig: Record<string, any>,
): Promise<Record<string, any>[]> {
  const { iconType, iconLibraryField, iconCodeField, fallbackIconUrl } = visConfig;
  console.log('[enrichIconUrls] called, iconType:', iconType, 'libField:', iconLibraryField, 'codeField:', iconCodeField, 'dataLen:', data?.length);
  if (iconType !== 'field' || !iconLibraryField || !iconCodeField || !data.length) {
    console.log('[enrichIconUrls] skipped: iconType!=field or missing fields or no data');
    return data;
  }

  // 收集所有唯一的库号+代号组合
  const combos = new Map<string, string>();
  for (const row of data) {
    const lib = row[iconLibraryField];
    const code = row[iconCodeField];
    console.log('[enrichIconUrls] row:', iconLibraryField, '=', lib, ',', iconCodeField, '=', code);
    if (lib != null && code != null) {
      const key = `${lib}|${code}`;
      if (!combos.has(key)) combos.set(key, '');
    }
  }
  console.log('[enrichIconUrls] 待查找组合:', Array.from(combos.keys()));

  // 批量查找图标 URL
  await Promise.all(
    Array.from(combos.keys()).map(async (key) => {
      const [lib, code] = key.split('|');
      try {
        const resp = await fetch(`/api/icons/lookup?lib=${encodeURIComponent(lib)}&code=${encodeURIComponent(code)}`);
        console.log('[enrichIconUrls] lookup', lib, code, 'status:', resp.status);
        if (resp.ok) {
          const result = await resp.json();
          console.log('[enrichIconUrls] lookup result:', JSON.stringify(result));
          if (result.url) combos.set(key, result.url);
        }
      } catch (e) { console.error('[enrichIconUrls] lookup error:', e); }
    }),
  );

  // 为每行数据添加 _iconUrl
  const enriched = data.map((row) => {
    const lib = row[iconLibraryField];
    const code = row[iconCodeField];
    const url = lib != null && code != null ? combos.get(`${lib}|${code}`) || '' : '';
    return { ...row, _iconUrl: url || fallbackIconUrl || '' };
  });
  console.log('[enrichIconUrls] 完成, 示例 _iconUrl:', enriched[0]?._iconUrl);
  return enriched;
}

const getLayerSource = (dataset: Dataset, sourceConfig: LayerSourceConfig, visConfig: Record<string, any>, enrichedData?: Record<string, any>[]) => {
  if (isLocalOrRemoteDataset(dataset)) {
    const restSourceConfig = omit(sourceConfig, ['datasetId', 'parser']);
    let data = enrichedData || (dataset as any).data;
    // DMS 转换：仅当 coordinateType === 'dms' 时，按用户配置转换指定字段
    // 注意：坐标字段名在 sourceConfig.parser 中（x=经度, y=纬度），不是在 visConfig 中
    if (visConfig?.coordinateType === 'dms' && data?.length) {
      const lonField = sourceConfig?.parser?.x;
      const latField = sourceConfig?.parser?.y;
      data = data.map((row: Record<string, any>) => {
        const newRow = { ...row };
        if (lonField && newRow[lonField] != null) {
          newRow[lonField] = dmsToDecimal(newRow[lonField]);
        }
        if (latField && newRow[latField] != null) {
          newRow[latField] = dmsToDecimal(newRow[latField]);
        }
        return newRow;
      });
    }
    return {
      data,
      parser: { type: 'json', ...sourceConfig?.parser },
      ...restSourceConfig,
    };
  }

  const tileProperties = dataset.properties;
  const restTileProperties = omit(tileProperties, ['type', 'url']);
  const restLayerSourceConfig = omit(sourceConfig, ['datasetId', 'parser']);
  return {
    data: dataset.properties.url,
    parser: { ...restTileProperties, ...sourceConfig.parser },
    ...restLayerSourceConfig,
  };
};

export const useLayerProps = (visConfig: LayerSchema['visConfig'], sourceConfig: LayerSchema['sourceConfig']) => {
  const datasetId = sourceConfig.datasetId;
  const [dataset] = useDataset(datasetId);
  const [enrichedData, setEnrichedData] = useState<Record<string, any>[]>();

  // 当数据或图标字段配置变化时，异步获取图标 URL
  useEffect(() => {
    if (dataset && isLocalOrRemoteDataset(dataset)) {
      const dsData = (dataset as any).data;
      if (dsData?.length) {
        enrichIconUrls(dsData, visConfig as Record<string, any>).then(setEnrichedData);
      }
    }
  }, [(dataset as any)?.data, (visConfig as any)?.iconType, (visConfig as any)?.iconLibraryField, (visConfig as any)?.iconCodeField]);

  const layerProps = useMemo(() => {
    if (sourceConfig.datasetId && dataset) {
      const source = getLayerSource(dataset, sourceConfig, visConfig as Record<string, any>, enrichedData);
      // 基于字段模式下，从 enrichedData 的 _iconUrl 构建动态 iconAtlas
      let mergedVisConfig = { ...visConfig };
      if ((visConfig as any)?.iconType === 'field' && enrichedData?.length) {
        const dynamicAtlas: Record<string, string> = {};
        for (const row of enrichedData) {
          const url = row._iconUrl;
          if (url && !dynamicAtlas[url]) {
            dynamicAtlas[url] = url;
          }
        }
        if (Object.keys(dynamicAtlas).length > 0) {
          mergedVisConfig = { ...mergedVisConfig, iconAtlas: dynamicAtlas };
          console.log('[useLayerProps] 动态 iconAtlas:', Object.keys(dynamicAtlas));
        }
      }
      return {
        ...mergedVisConfig,
        source,
      };
    }
    return visConfig;
  }, [visConfig, sourceConfig, dataset, enrichedData]);

  return layerProps;
};
