export interface TileConfig {
  id?: string;
  tileUrl: string;
  tileName: string;
  minZoom: number;
  maxZoom: number;
  isDefault?: string; // "1" = default
  defaultNum?: number; // 默认排序编号
  crs?: string;       // EPSG:3857 或 EPSG:4326
  tileScheme?: string; // XYZ 或 TMS
  origin?: string;     // 原点: "lng,lat" 格式，如 "-180,90"
  updateTime?: string;
}
