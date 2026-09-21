import type { Application } from '@antv/li-sdk';
import { cloneDeep } from 'lodash-es';
import { ANALYSIS_ASSETS_ID, DEFAULT_ANALYSIS_WIDGETS, DEFAULT_MAP_WIDGETS } from '@/constants';
import { getTileConfig } from '@/services/tile-config';

// 默认高德卫星底图 (最终兜底)
const DEFAULT_TILE_CONFIG = {
  tileUrl: 'https://webst01.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}',
  tileName: '卫星影像底图',
  minZoom: 0,
  maxZoom: 18,
};

// 获取运行时配置（优先级链: 数据库 → window.L7VP_CONFIG → 硬编码兜底）
const getRuntimeConfig = async () => {
  // 1. 优先从数据库读取瓦片配置
  try {
    const dbConfig = await getTileConfig();
    if (dbConfig && dbConfig.tileUrl) {
      return {
        tileLayerUrl: dbConfig.tileUrl,
        tileLayerName: dbConfig.tileName || '自定义底图',
        tileMinZoom: dbConfig.minZoom ?? 0,
        tileMaxZoom: dbConfig.maxZoom ?? 18,
      };
    }
  } catch {
    // 数据库读取失败，降级
  }

  // 2. 降级到 window.L7VP_CONFIG
  const runtimeConfig = (window as any).L7VP_CONFIG;
  if (runtimeConfig && runtimeConfig.tileLayerUrl) {
    return {
      tileLayerUrl: runtimeConfig.tileLayerUrl,
      tileLayerName: runtimeConfig.tileLayers?.[0]?.name || runtimeConfig.tileLayerName || '自定义底图',
      tileMinZoom: runtimeConfig.tileMinZoom ?? 0,
      tileMaxZoom: runtimeConfig.tileMaxZoom ?? 18,
    };
  }

  // 3. 最终兜底: 高德卫星影像
  return {
    tileLayerUrl: DEFAULT_TILE_CONFIG.tileUrl,
    tileLayerName: DEFAULT_TILE_CONFIG.tileName,
    tileMinZoom: DEFAULT_TILE_CONFIG.minZoom,
    tileMaxZoom: DEFAULT_TILE_CONFIG.maxZoom,
  };
};

export const creatApplication = async (applicationName: string, assetPackageIds: string[]) => {
  const widgets = assetPackageIds.includes(ANALYSIS_ASSETS_ID) ? DEFAULT_ANALYSIS_WIDGETS : DEFAULT_MAP_WIDGETS;
  const config = await getRuntimeConfig();

  const applicationConfig: Application = {
    version: 'v0.1',
    metadata: {
      name: applicationName,
    },
    datasets: [],
    spec: {
      map: {
        basemap: 'Map' as const,
        config: {
          zoom: 3,
          center: [120.153576, 30.287459] as [number, number],
          pitch: 0,
          bearing: 0,
          style: 'dark',
          WebGLParams: {
            preserveDrawingBuffer: true,
          },
        },
        logoPosition: 'leftbottom',
        logoVisible: false,
      },
      layers: [],
      widgets: cloneDeep(widgets),
    },
  };

  return applicationConfig;
};
