import type { Feature, FeatureCollection } from '@turf/turf';
import { point } from '@turf/turf';
import { isString, isUndefined } from 'lodash-es';

const Chache = new Map<string, FeatureCollection>();

// 本地数据路径 - 将GeoJSON文件放在 /data 目录下
const LOCAL_DATA_PATH = '/data';
// 远程数据源（备用）
const BASE_URL = 'https://npm.elemecdn.com/static-geo-atlas';

const getAdministrativeCentroidList = () => {
  return fetch(`${BASE_URL}@0.0.2/geo-data/administrative-data/area-list.json`).then<Record<string, any>[]>((data) =>
    data.json(),
  );
};

// 获取本地GeoJSON文件
const fetchLocalGeoJSON = async (filename: string): Promise<FeatureCollection | null> => {
  try {
    const response = await fetch(`${LOCAL_DATA_PATH}/${filename}`);
    if (response.ok) {
      return await response.json();
    }
    return null;
  } catch {
    return null;
  }
};

// 获取远程GeoJSON文件
const fetchRemoteGeoJSON = async (url: string): Promise<FeatureCollection | null> => {
  try {
    const response = await fetch(url);
    if (response.ok) {
      return await response.json();
    }
    return null;
  } catch {
    return null;
  }
};

// 统一的GeoJSON获取函数，优先本地，失败则尝试远程
const fetchGeoJSON = async (localFilename: string, remoteUrl: string): Promise<FeatureCollection | null> => {
  const cacheKey = remoteUrl;
  
  // 先检查缓存
  if (Chache.has(cacheKey)) {
    return Chache.get(cacheKey)!;
  }

  // 优先尝试本地
  let data = await fetchLocalGeoJSON(localFilename);
  
  // 本地没有则尝试远程
  if (!data) {
    data = await fetchRemoteGeoJSON(remoteUrl);
  }

  // 如果都获取失败，返回null而不是抛出错误
  if (!data) {
    console.warn(`GeoJSON数据加载失败: ${localFilename}，请确保在 public/data 目录下放置了对应的GeoJSON文件`);
    return null;
  }

  // 存入缓存
  Chache.set(cacheKey, data);
  return data;
};

export const getAdministrativeBoundary = async (
  adminBoundaryGranularity: 'country' | 'province' | 'city' | 'district' | 'chinaCountryBoundary',
): Promise<FeatureCollection | null> => {
  const prefixUrl = `${BASE_URL}/geo-data/choropleth-data`;
  
  // 定义本地文件名和远程URL的映射
  const adminBoundaryConfigMap = {
    country: {
      local: 'world_country.json',
      remote: `${prefixUrl}/world/all_world_country.json`
    },
    chinaCountryBoundary: {
      local: 'china_boundary.json',
      remote: `${prefixUrl}/country/100000_country_boundary.json`
    },
    province: {
      local: 'china_province.json',
      remote: `${prefixUrl}/country/100000_country_province.json`
    },
    city: {
      local: 'china_city.json',
      remote: `${prefixUrl}/country/100000_country_city.json`
    },
    district: {
      local: 'china_district.json',
      remote: `${prefixUrl}/country/100000_country_district.json`
    },
  };
  
  const config = adminBoundaryConfigMap[adminBoundaryGranularity];
  return await fetchGeoJSON(config.local, config.remote);
};

const getAdministrativeBoundaryMap = (geojson: FeatureCollection, adminBoundaryType: 'name' | 'adcode') => {
  const AdministrativeMap = new Map<string, Feature>();

  geojson.features.forEach((feature) => {
    const { name, adcode } = feature.properties!;
    const key = adminBoundaryType === 'name' ? name : adcode;
    AdministrativeMap.set(key, feature);
  });

  return AdministrativeMap;
};

export type JoinAdcodeDataParams = {
  dataset: Record<string, any>[];
  adminBoundaryField: string;
  adminBoundaryGranularity: 'country' | 'province' | 'city' | 'district';
  adminBoundaryType?: 'name' | 'adcode';
  geometryType: 'centroid' | 'boundary';
  adminBoundaryGeometryField?: string;
};

/**
 * 将数据集的行政元数据信息与地理数据关联，返回插入新的地理数据列的数据集
 */
export const joinAdcodeData = async (params: JoinAdcodeDataParams) => {
  const {
    dataset,
    adminBoundaryField,
    adminBoundaryGranularity,
    adminBoundaryType = 'name',
    geometryType,
    adminBoundaryGeometryField = adminBoundaryField + '_geometry',
  } = params;

  const geojson = await getAdministrativeBoundary(adminBoundaryGranularity);
  
  // 如果无法加载地理数据，返回原始数据
  if (!geojson) {
    console.warn(`无法加载 ${adminBoundaryGranularity} 的地理边界数据`);
    return Promise.resolve(dataset.map(datum => ({ ...datum, [adminBoundaryGeometryField]: null })));
  }
  
  const administrativeMap = getAdministrativeBoundaryMap(geojson, adminBoundaryType);

  const _dataset = [];

  for (let i = 0; i < dataset.length; i++) {
    const datum = dataset[i];
    const adminBoundaryValue = datum[adminBoundaryField];

    if (isUndefined(adminBoundaryValue) || (isString(adminBoundaryValue) && adminBoundaryValue === '')) {
      _dataset.push({ ...datum, [adminBoundaryGeometryField]: null });
      continue;
    }

    const adminBoundary = administrativeMap.get(adminBoundaryValue);
    if (!adminBoundary) {
      _dataset.push({ ...datum, [adminBoundaryGeometryField]: null });
      continue;
    }

    const geometry =
      geometryType === 'boundary' ? adminBoundary.geometry : point(adminBoundary?.properties?.centroid).geometry;
    // const adminProperties = {
    //   [adminBoundaryField + '_name']: adminBoundary.properties?.name,
    //   [adminBoundaryField + '_adcode']: adminBoundary.properties?.adcode,
    // };
    _dataset.push({ ...datum, [adminBoundaryGeometryField]: geometry });
  }

  return Promise.resolve(_dataset);
};
