import type GeoJSON from 'geojson';
import { isArray } from 'lodash-es';
// @ts-ignore
import { parse as wktParse } from 'wkt';

/**
 * 是否是点坐标字符串
 * @data string "[123,29]"
 * @return boolean
 */
export const isPonitCoordinatesString = (data: string) => {
  // accepts: string start with [ and end with ]
  const isStringArray = /^\[([\s\S]*)\]$/.test(data);
  const isPonitCoordinates = isStringArray && /^\[[0-9\.]{1,},[0-9\.]{1,}\]$/.test(data);
  return isPonitCoordinates;
};

/**
 * 点坐标 转 Geometry
 * @data string "[123,29]"
 * @return GeoJSON.Point { type: 'Point', coordinates: [123,29] }
 */
export const ponitCoordinates2Geometry = (data: string | GeoJSON.Position): GeoJSON.Point => {
  const geometry: GeoJSON.Point = { type: 'Point', coordinates: [] };
  if (typeof data == 'string') {
    try {
      geometry.coordinates = JSON.parse(data);
    } catch (e) {
      // 解析失败
      // 默认忽略掉，设置为空数据
      geometry.coordinates = [];
    }
  } else {
    geometry.coordinates = data;
  }
  return geometry;
};

/**
 * 是否是 wkt
 * @data string "POINT(6 10)","POLYGON((1 1,5 1,5 5,1 5,1 1),(2 2,2 3,3 3,3 2,2 2))","MULTIPOINT(3.5 5.6, 4.8 10.5)","MULTIPOLYGON(((1 1,5 1,5 5,1 5,1 1),(2 2,2 3,3 3,3 2,2 2)),((6 3,9 2,9 4,6 3)))"
 * @return boolean
 */
export const isWkt = (data: string) => {
  // Detecting WKT in text reference: https://en.wikipedia.org/wiki/Well-known_text
  // string start with POINT|LINESTRING|POLYGON|MULTIPOINT|MULTILINESTRING|MULTIPOLYGON [Z] ( and end with )
  const regExp = /^(POINT|LINESTRING|POLYGON|MULTIPOINT|MULTILINESTRING|MULTIPOLYGON)(\sz)?\s?\(.*\)$/i;
  let iswktField = false;
  if (typeof data === 'string' && regExp.test(data)) {
    iswktField = true;
  }

  return iswktField;
};

/**
 * wkt 转 geometry
 * @data string "POLYGON((1 1,5 1,5 5,1 5,1 1),(2 2,2 3,3 3,3 2,2 2))"
 * @return geometry ep:{ type: 'Point', coordinates: [123,29] }
 */
export const wkt2Geometry = (data: string) => {
  let geometry;
  if (typeof data == 'string') {
    try {
      geometry = wktParse(data);
    } catch (e) {
      // 解析失败
      // 默认忽略掉，设置为空数据
      geometry = { type: 'Point', coordinates: [] };
    }
  }
  return geometry;
};

/**
 * 是否是字符串 geometry
 * @data string '{ "type": "Point", "coordinates": [123,29] }'
 * @return boolean
 */
export const isGeometryString = (data: string) => {
  // string start with { and end with }
  const isStringObject = /^{([\s\S]*)}$/.test(data);
  const isgeometryField = isStringObject && /type/.test(data) && /coordinates/.test(data);
  return isgeometryField;
};

/**
 * geometry 字符串转 json
 * @data string '{ "type": "Point", "coordinates": [123,29] }'
 * @return GeoJSON { type: 'Point', coordinates: [123,29] }
 */
export const geometryString2Geometry = (data: string) => {
  let geometry;
  if (typeof data == 'string') {
    try {
      geometry = JSON.parse(data);
    } catch (e) {
      // 解析失败
      // 默认忽略掉，设置为空数据
      geometry = { type: 'Point', coordinates: [] };
    }
  }
  return geometry;
};

/**
 * 是否是点坐标(数组或字符串)
 * @data "[123,29]" or [123,29]
 * @return boolean
 */
export const isPonitCoordinates = (data: any): any => {
  let result = false;
  if (typeof data === 'string' && isPonitCoordinatesString(data)) {
    result = true;
  } else if (isArray(data) && data.length === 2) {
    result = true;
  }
  return result;
};

/**
 * 是否是度分秒字符串 (DDD.MMSSss格式)
 * 整数部分=度，小数第1-2位=分，第3位及以后=秒(含小数)
 * @data string "120.105999" (120°10'59.99")
 * @return boolean
 */
export const isDmsString = (data: any) => {
  if (data == null || data === '') return false;
  // 支持 number 类型（数据库 DECIMAL 字段）和 string 类型
  const str = typeof data === 'number' ? String(data) : typeof data === 'string' ? data : '';
  if (!str) return false;
  // 匹配 DDD.MMSSsss 格式: 1-3位整数(可负) + 小数点 + 4-8位小数
  const dmsRegExp = /^-?\d{1,3}\.\d{4,8}$/;
  if (!dmsRegExp.test(str)) return false;

  // 校验分/秒值必须在合法范围内，排除普通十进制数被误判
  try {
    const negative = str.startsWith('-');
    const s = negative ? str.substring(1) : str;
    const fracStr = (s.split('.')[1] || '').padEnd(6, '0');
    const minutes = parseInt(fracStr.substring(0, 2), 10);
    // 秒编码：前2位=整数秒，后续=小数秒
    const secStr = fracStr.substring(2);
    const secInt = parseInt(secStr.substring(0, 2), 10) || 0;
    const secFrac = parseFloat('0.' + (secStr.substring(2) || '0'));
    const seconds = secInt + secFrac;
    return minutes < 60 && seconds < 60;
  } catch {
    return false;
  }
};

/**
 * 度分秒字符串 → 十进制
 * @param dms "120.105999" (120°10'59.99")
 * @returns 120.183331 (十进制)
 */
export const dmsToDecimal = (dms: any): number => {
  try {
    // 支持 number 类型（数据库 DECIMAL 字段）
    const dmsStr = typeof dms === 'number' ? String(dms) : dms;
    if (typeof dmsStr !== 'string') return Number(dmsStr);

    const negative = dmsStr.startsWith('-');
    const str = negative ? dmsStr.substring(1) : dmsStr;
    const parts = str.split('.');
    const degrees = parseInt(parts[0], 10);

    let minutes = 0;
    let seconds = 0;
    if (parts[1]) {
      const fracStr = parts[1].padEnd(6, '0');
      minutes = parseInt(fracStr.substring(0, 2), 10);
      // 秒编码：前2位=整数秒，后续=小数秒（如"5999"→59秒+0.99秒=59.99）
      const secStr = fracStr.substring(2);
      const secInt = parseInt(secStr.substring(0, 2), 10) || 0;
      const secFrac = parseFloat('0.' + (secStr.substring(2) || '0'));
      seconds = secInt + secFrac;
    }

    if (minutes >= 60 || seconds >= 60) {
      return Number(dmsStr);
    }

    const decimal = degrees + minutes / 60 + seconds / 3600;
    return negative ? -decimal : decimal;
  } catch {
    return Number(dms);
  }
};

/**
 * 解析带有地理类型的行数据
 */
export const parserDataWithGeo = (data: Record<string, any>[]): Record<string, any>[] => {
  if (!data || data.length === 0) return data;

  const result: Record<string, any>[] = [];
  const fristRow = data[0];
  const convertColumnsMap = new Map<string, (...args: any) => any>();
  for (const key of Object.keys(fristRow)) {
    const value = fristRow[key];
    // 如果是点坐标(数组或字符串)列
    if (isPonitCoordinates(value)) {
      console.log('[geo-parser] 检测到点坐标列:', key, value);
      convertColumnsMap.set(key, ponitCoordinates2Geometry);
      // 如果是 wkt 列
    } else if (isWkt(value)) {
      console.log('[geo-parser] 检测到 WKT 列:', key, value);
      convertColumnsMap.set(key, wkt2Geometry);
      // 如果是字符串 geometry 列
    } else if (isGeometryString(value)) {
      console.log('[geo-parser] 检测到 Geometry 列:', key, value);
      convertColumnsMap.set(key, geometryString2Geometry);
      // 如果是度分秒字符串
    }
    // 注意：DMS 不在 parserDataWithGeo 自动检测，由图层级别根据 coordinateType 显式转换
  }
  console.log('[geo-parser] 转换映射:', Array.from(convertColumnsMap.keys()));

  for (let index = 0; index < data.length; index++) {
    const row = data[index];
    const convertRow: Record<string, any> = {};
    // 变量需要转换的列
    convertColumnsMap.forEach((convert, key) => {
      // 执行数据格式转换
      convertRow[key] = convert(row[key]);
    });
    const resultRow = { ...row, ...convertRow };
    result.push(resultRow);
  }
  return result;
};
