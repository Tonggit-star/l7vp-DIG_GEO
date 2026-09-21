import * as LIAnalysisAssets from '@antv/li-analysis-assets';
import { version as LIAnalysisAssetsVersion } from '@antv/li-analysis-assets/package.json';
import * as LICoreAssets from '@antv/li-core-assets';
import { version as LICoreAssetsVersion } from '@antv/li-core-assets/package.json';
import * as LISDK from '@antv/li-sdk';
import dayjs from 'dayjs';
import { isDevelopment } from './env';
import type { AssetPackage } from '@/services';

// 开放环境下，将 LI SDK、CoreAssets、AnalysisAssets 挂载到 window 上，与生产环境统一，方便调试
// 生产环境下，会 tree shaking 掉以下代码
if (process.env.NODE_ENV === 'development') {
  (window as any).LISDK = LISDK;
  (window as any).LIAnalysisAssets = LIAnalysisAssets;
}

// 离线部署模式：将所有资产包直接打包到本地，不通过外部URL动态加载
(window as any).LICoreAssets = LICoreAssets;
(window as any).LIAnalysisAssets = LIAnalysisAssets;

export const CORE_ASSETS_ID = '@antv/li-core-assets';
export const ANALYSIS_ASSETS_ID = '@antv/li-analysis-assets';

export const DefaultAssetPackageIds = [CORE_ASSETS_ID, ANALYSIS_ASSETS_ID];

// 平台内置资产包（离线模式：全部打包到本地，禁用动态加载）
export const BUILTIN_ASSET_PACKAGES: AssetPackage[] = [
  {
    assetId: CORE_ASSETS_ID,
    creatTime: dayjs().format('YYYY-MM-DD HH:mm:ss'),
    name: '可视化核心资产',
    description: '官方可视化核心资产, 包含 L7VP 核心可视化图层、组件、服务',
    package: CORE_ASSETS_ID,
    version: LICoreAssetsVersion,
    global: 'LICoreAssets',
    urls: [],  // 离线模式：不使用外部URL
    enable: false,  // 禁用动态加载，已打包到本地
  },
  {
    assetId: ANALYSIS_ASSETS_ID,
    creatTime: dayjs().format('YYYY-MM-DD HH:mm:ss'),
    name: '分析场景资产包',
    description: '官方分析场景资产包，用于数据可视分析场景，包含分析图层、组件等',
    package: ANALYSIS_ASSETS_ID,
    version: LIAnalysisAssetsVersion,
    global: 'LIAnalysisAssets',
    urls: [],  // 离线模式：不使用外部URL
    enable: false,  // 禁用动态加载，已打包到本地
  },
  // 离线模式：移除外部资产包
  // {
  //   assetId: '@antv/li-sam-assets',
  //   ...
  // },
  // {
  //   assetId: '@lvisei/li-zelda-assets',
  //   ...
  // },
];
