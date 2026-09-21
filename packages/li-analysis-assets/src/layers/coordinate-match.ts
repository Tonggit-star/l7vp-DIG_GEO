import type { FieldSelectOptionType } from '@antv/li-p2';

const LON_PATTERNS = ['经度', 'lng', 'longitude', 'lon', 'jd'];
const LAT_PATTERNS = ['纬度', 'lat', 'latitude', 'wd'];

export const getDefaultLongitude = (fieldList: FieldSelectOptionType[]): string | undefined => {
  for (const p of LON_PATTERNS) {
    const match = fieldList.find(f => String(f.value || f.label || '').toLowerCase().includes(p));
    if (match) return (match.value || match.label) as string;
  }
  return undefined;
};

export const getDefaultLatitude = (fieldList: FieldSelectOptionType[]): string | undefined => {
  for (const p of LAT_PATTERNS) {
    const match = fieldList.find(f => String(f.value || f.label || '').toLowerCase().includes(p));
    if (match) return (match.value || match.label) as string;
  }
  return undefined;
};
