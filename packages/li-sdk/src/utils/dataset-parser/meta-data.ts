import dayjs from 'dayjs';
import customParseFormat from 'dayjs/plugin/customParseFormat';
import { isValidCell } from 'h3-js';
import type { DatasetField } from '../../specs';

dayjs.extend(customParseFormat);

const SupportGeometryType = ['Point', 'MultiPoint', 'LineString', 'MultiLineString', 'Polygon', 'MultiPolygon'];

const isDateField = (value: string) => {
  // 时间戳检测：10位秒级或13位毫秒级
  if (/^\d{10}$/.test(value)) {
    const ts = parseInt(value, 10);
    // 合理范围：2000-01-01 ~ 2100-01-01 的秒级时间戳
    if (ts >= 946684800 && ts <= 4102444800) {
      return { type: 'date', format: 'timestamp_s' };
    }
  }
  if (/^\d{13}$/.test(value)) {
    const ts = parseInt(value, 10);
    // 合理范围：2000-01-01 ~ 2100-01-01 的毫秒级时间戳
    if (ts >= 946684800000 && ts <= 4102444800000) {
      return { type: 'date', format: 'timestamp_ms' };
    }
  }

  // ISO 8601: '2024-01-15T10:30:00.000Z', '2024-01-15T10:30:00+08:00'
  if (dayjs(value).isValid() && /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value)) {
    return { type: 'date', format: 'YYYY-MM-DDTHH:mm:ssZ' };
  }

  // '1970-01-01 00:00:00'
  if (dayjs(value, 'YYYY-MM-DD HH:mm:ss', true).isValid()) {
    return { type: 'date', format: 'YYYY-MM-DD HH:mm:ss' };
  }
  // '1970/01/01 00:00:00'
  if (dayjs(value, 'YYYY/MM/DD HH:mm:ss', true).isValid()) {
    return { type: 'date', format: 'YYYY/MM/DD HH:mm:ss' };
  }
  // '1970-01-01'
  if (dayjs(value, 'YYYY-MM-DD', true).isValid()) {
    return { type: 'date', format: 'YYYY-MM-DD' };
  }
  // '1970/01/01'
  if (dayjs(value, 'YYYY/MM/DD', true).isValid()) {
    return { type: 'date', format: 'YYYY/MM/DD' };
  }
  // '1970-01'
  if (dayjs(value, 'YYYY-MM', true).isValid()) {
    return { type: 'date', format: 'YYYY-MM' };
  }
  // '1970/01'
  if (dayjs(value, 'YYYY/MM', true).isValid()) {
    return { type: 'date', format: 'YYYY/MM' };
  }
  // '01/15/2024' (MM/DD/YYYY, 美国格式)
  if (dayjs(value, 'MM/DD/YYYY', true).isValid()) {
    return { type: 'date', format: 'MM/DD/YYYY' };
  }
  // '2024.01.15' (点分隔)
  if (dayjs(value, 'YYYY.MM.DD', true).isValid()) {
    return { type: 'date', format: 'YYYY.MM.DD' };
  }
  // '01.15.2024' (点分隔 美国格式)
  if (dayjs(value, 'MM.DD.YYYY', true).isValid()) {
    return { type: 'date', format: 'MM.DD.YYYY' };
  }
  // '2024年1月15日' / '2024年01月15日' (中文日期)
  if (/^\d{4}年\d{1,2}月\d{1,2}日$/.test(value)) {
    return { type: 'date', format: 'YYYY年MM月DD日' };
  }
  // '15 Jan 2024' / 'Jan 15, 2024' (带英文月份)
  if (dayjs(value, 'DD MMM YYYY', true).isValid()) {
    return { type: 'date', format: 'DD MMM YYYY' };
  }
  if (dayjs(value, 'MMM DD, YYYY', true).isValid()) {
    return { type: 'date', format: 'MMM DD, YYYY' };
  }
  // 紧凑格式：'20240115'
  if (/^\d{8}$/.test(value)) {
    const d = dayjs(value, 'YYYYMMDD', true);
    if (d.isValid() && d.year() >= 1970 && d.year() <= 2100) {
      return { type: 'date', format: 'YYYYMMDD' };
    }
  }
  // 紧凑格式：'20240115103030' (14位数字)
  if (/^\d{14}$/.test(value)) {
    const d = dayjs(value, 'YYYYMMDDHHmmss', true);
    if (d.isValid() && d.year() >= 1970 && d.year() <= 2100) {
      return { type: 'date', format: 'YYYYMMDDHHmmss' };
    }
  }

  return false;
};

const isNumberField = (value: string | number) => {
  return typeof value === 'number' || (/^(-?\d+)(\.\d+)?$/.test(String(value)) && !Number.isNaN(+value));
};

const isBooleanField = (value: string) => {
  return /^(true|false)$/.test(value);
};

const isGeoJsonGeometryObject = (value: Record<string, any>) => {
  return (
    value.hasOwnProperty('type') &&
    value.hasOwnProperty('coordinates') &&
    Array.isArray(value.coordinates) &&
    SupportGeometryType.includes(value.type)
  );
};

const isGeoField = (value: Record<string, any>) => {
  return isGeoJsonGeometryObject(value);
};

const findFirstNonNullOrUndefinedValue = (data: Record<string, any>[], key: string, sampleCount = 50) => {
  const numberOfRows = data.length;
  const sampleStep = Math.max(Math.floor(numberOfRows / sampleCount), 1);

  for (let i = 0; i < numberOfRows; i += sampleStep) {
    if (data[i][key] !== null && data[i][key] !== undefined) {
      return data[i][key];
    }
  }

  return null;
};

/**
 *  获取数据集表头元数据信息
 */
export const getDatasetColumns = (data: Record<string, any>[]) => {
  if (data.length === 0) return [];

  const fields = Object.keys(data[0]);
  const columns: DatasetField[] = [];

  fields.forEach((key) => {
    const value = findFirstNonNullOrUndefinedValue(data, key);
    if (value === null) {
      // 值不存在的情况，忽略掉列头
    } else if (typeof value === 'number') {
      columns.push({
        type: 'number',
        name: key,
      });
    } else if (typeof value === 'boolean') {
      columns.push({
        type: 'boolean',
        name: key,
      });
    } else if (typeof value === 'string') {
      const dateField = isDateField(value);
      if (dateField) {
        columns.push({
          type: 'date',
          name: key,
          format: dateField.format,
        });
      } else if (isNumberField(value)) {
        // 字符串形式的数值（如 API 返回的 "123"）也应识别为数值
        columns.push({
          type: 'number',
          name: key,
        });
      } else if (isValidCell(value)) {
        columns.push({
          type: 'h3',
          name: key,
        });
      } else {
        columns.push({
          type: 'string',
          name: key,
        });
      }
    } else if (typeof value === 'object') {
      if (value && isGeoField(value)) {
        columns.push({
          type: 'geo',
          name: key,
        });
      } else {
        columns.push({
          type: 'string',
          name: key,
        });
      }
    }
  });

  return columns;
};
