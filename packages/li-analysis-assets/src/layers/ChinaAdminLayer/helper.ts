import type { FeatureCollection } from '@turf/turf';
import { getAdministrativeBoundary } from '../../services/join-adcode/helper';
import type { ChinaAdminLayerSource } from './type';

export const geChinaAdminBoundaryData = async () => {
  const geojson = await getAdministrativeBoundary('chinaCountryBoundary');
  
  // 如果无法加载数据，返回空的FeatureCollection
  if (!geojson) {
    console.warn('无法加载中国行政边界数据，请在 public/data 目录下放置 china_boundary.json 文件');
    return { 
      chinaBoundary: { type: 'FeatureCollection' as const, features: [] },
      hkmBoundary: { type: 'FeatureCollection' as const, features: [] },
      disputeBoundary: { type: 'FeatureCollection' as const, features: [] }
    };
  }

  const chinaBoundary: FeatureCollection = {
    type: 'FeatureCollection',
    features: geojson.features.filter(({ properties }) => ['coast', 'national'].includes(properties?.type)),
  };
  const hkmBoundary: FeatureCollection = {
    type: 'FeatureCollection',
    features: geojson.features.filter(({ properties }) => properties?.type === 'hkm'),
  };
  const disputeBoundary: FeatureCollection = {
    type: 'FeatureCollection',
    features: geojson.features.filter(({ properties }) => properties?.type === 'dispute'),
  };

  return { chinaBoundary, hkmBoundary, disputeBoundary };
};

export const getAdminBoundaryData = async (
  data: Record<string, any>[],
  countryAdConfig: ChinaAdminLayerSource['countryAdConfig'],
) => {
  const { countryGranularity, countryAdType, countryAdField } = countryAdConfig;
  const boundaryGeoJSON = await getAdministrativeBoundary(countryGranularity);
  
  // 如果无法加载数据，返回空数据
  if (!boundaryGeoJSON) {
    console.warn(`无法加载 ${countryGranularity} 的地理边界数据`);
    return { 
      data: [], 
      joinBy: { type: 'join' as const, sourceField: countryAdField, targetField: countryAdType === 'adname' ? 'name' : 'adcode', data }, 
      labelData: [] 
    };
  }
  
  const boundaryData = boundaryGeoJSON.features.map((feature) => ({
    ...feature.properties,
    _geometry: feature.geometry,
  }));
  
  // 生成标签数据，优先使用 centroid，如果没有则使用 center
  const labelData: Record<string, any>[] = [];
  for (const feature of boundaryGeoJSON.features) {
    const props = feature.properties as Record<string, any>;
    // 确保 centroid 字段存在，如果没有则使用 center 字段
    const centroid = props.centroid || props.center;
    if (centroid) {
      labelData.push({
        ...props,
        centroid: centroid,
      });
    } else {
      console.warn(`Feature ${props.name || props.adcode} 缺少 centroid/center 字段`);
    }
  }

  const joinBy = {
    type: 'join',
    sourceField: countryAdField,
    targetField: countryAdType === 'adname' ? 'name' : 'adcode',
    data,
  };

  return { data: boundaryData, joinBy, labelData };
};
