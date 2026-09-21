import { LineLayer, PolygonLayer } from '@antv/larkmap';
import type { ImplementLayerProps } from '@antv/li-sdk';
import { destination, difference } from '@turf/turf';
import React, { useEffect, useMemo, useState } from 'react';

const UNIT_TO_METERS: Record<string, number> = {
  meters: 1, kilometers: 1000, miles: 1609.344, nauticalmiles: 1852,
};

const getCircleSteps = (radiusMeters: number): number => {
  const steps = Math.round(360 * (radiusMeters / 2000000));
  return Math.max(90, Math.min(720, steps));
};

/** 加载 china_boundary.json + china_province.json，各取第一个多边形，合并为 MultiPolygon */
let cachedChinaGeom: any = null;
let chinaLoading = false;
const chinaCallbacks: Array<(g: any) => void> = [];

function extractFirstPolygon(geom: any): any[] {
  if (!geom) return [];
  if (geom.type === 'Polygon') return [geom.coordinates];
  if (geom.type === 'MultiPolygon' && geom.coordinates?.length) return [geom.coordinates[0]];
  return [];
}

function loadChinaGeom(): Promise<any> {
  if (cachedChinaGeom) return Promise.resolve(cachedChinaGeom);
  return new Promise((resolve) => {
    chinaCallbacks.push(resolve);
    if (chinaLoading) return;
    chinaLoading = true;
    console.log('[GeoCircleLayer] loading china boundary + hainan...');
    Promise.all([
      fetch('/data/china_boundary.json').then(r => r.json()),
      fetch('/data/china_province.json').then(r => r.json()),
    ]).then(([boundary, province]) => {
      const mainlandFeature = boundary?.features?.[0];
      const chinaCoords = extractFirstPolygon(mainlandFeature?.geometry);
      const hainanFeat = province?.features?.find((f: any) =>
        f.properties?.adcode === 460000 || f.properties?.name === '海南省'
      );
      const hainanCoords = extractFirstPolygon(hainanFeat?.geometry);
      cachedChinaGeom = { type: 'MultiPolygon', coordinates: [...chinaCoords, ...hainanCoords] };
      console.log('[GeoCircleLayer] china geom ready, polyCount:', cachedChinaGeom.coordinates.length);
      chinaCallbacks.forEach(cb => cb(cachedChinaGeom));
      chinaCallbacks.length = 0;
    }).catch((err) => {
      console.error('[GeoCircleLayer] failed to load china:', err);
      chinaCallbacks.forEach(cb => cb(null));
      chinaCallbacks.length = 0;
    });
  });
}

export interface GeoCircleLayerWrapperProps extends ImplementLayerProps {
  source?: { data?: Record<string, any>[]; parser?: { x?: string; y?: string; longitude?: string; latitude?: string; geometry?: string } };
  outerRadiusField?: string;
  innerRadiusField?: string;
  radiusUnit?: string;
  fillColor?: string;
  fillOpacity?: number;
  strokeColor?: string;
  lineWidth?: number;
  lineType?: 'solid' | 'dash';
  clipChina?: boolean;
  visible?: boolean;
  label?: { field?: string; visible?: boolean; style?: Record<string, any> };
  minZoom?: number; maxZoom?: number; blend?: string; zIndex?: number;
}

const GeoCircleLayerWrapper: React.FC<GeoCircleLayerWrapperProps> = (props) => {
  const { source, outerRadiusField, innerRadiusField, radiusUnit = 'meters',
    fillColor = 'rgb(90, 216, 166)', fillOpacity = 0.8, minZoom = 0, maxZoom = 24, blend = 'normal',
    strokeColor, lineWidth, lineType = 'solid', clipChina, label, zIndex, visible } = props;

  const [chinaGeom, setChinaGeom] = useState<any>(cachedChinaGeom);

  useEffect(() => {
    if (clipChina && !cachedChinaGeom) loadChinaGeom().then(setChinaGeom);
  }, [clipChina]);

  const lngField = source?.parser?.x || source?.parser?.longitude;
  const latField = source?.parser?.y || source?.parser?.latitude;

  const geoSource = useMemo(() => {
    const data = source?.data;
    if (!data || !Array.isArray(data) || data.length === 0) return null;
    if (!lngField || !latField || !outerRadiusField) return null;
    if (clipChina && !chinaGeom) return null;

    const unitMult = UNIT_TO_METERS[radiusUnit] || 1;
    let clipSuccess = 0, clipFailed = 0, clipSkipped = 0;

    const features = data.map((row: Record<string, any>) => {
      const lng = parseFloat(row[lngField]);
      const lat = parseFloat(row[latField]);
      const outerR = parseFloat(row[outerRadiusField]);
      if (isNaN(lng) || isNaN(lat) || isNaN(outerR) || outerR <= 0) return null;

      const outerRMeters = outerR * unitMult;
      const steps = getCircleSteps(outerRMeters);
      const center: [number, number] = [lng, lat];

      const outerCoords: [number, number][] = [];
      for (let i = 0; i < steps; i++) {
        const bearing = (i * -360) / steps;
        const pt = destination(center, outerRMeters, bearing, { units: 'meters' });
        outerCoords.push(pt.geometry.coordinates as [number, number]);
      }
      outerCoords.push(outerCoords[0]);

      let resultGeom: any = { type: 'Polygon', coordinates: [outerCoords] };

      // 内圈
      const innerR = innerRadiusField ? parseFloat(row[innerRadiusField]) : NaN;
      if (!isNaN(innerR) && innerR > 0 && innerR < outerR) {
        const innerRMeters = innerR * unitMult;
        const innerCoords: [number, number][] = [];
        for (let i = steps - 1; i >= 0; i--) {
          const bearing = (i * -360) / steps;
          const pt = destination(center, innerRMeters, bearing, { units: 'meters' });
          innerCoords.push(pt.geometry.coordinates as [number, number]);
        }
        innerCoords.push(innerCoords[0]);
        resultGeom = { type: 'Polygon', coordinates: [outerCoords, innerCoords] };
      }

      // 裁剪中国大陆
      if (clipChina && chinaGeom) {
        try {
          const circleFeature = { type: 'Feature' as const, properties: {}, geometry: resultGeom };
          const diffResult = difference(circleFeature as any, chinaGeom);
          if (diffResult) { resultGeom = diffResult.geometry; clipSuccess++; }
          else { clipSkipped++; return null; }
        } catch (e: any) { console.warn('[GeoCircleLayer] diff err:', e?.message); clipFailed++; }
      }

      return { type: 'Feature' as const, properties: row, geometry: resultGeom };
    }).filter(Boolean) as any[];

    console.log('[GeoCircleLayer] built:', features.length, 'features, clip:', clipSuccess, 'ok', clipFailed, 'err', clipSkipped, 'skip');
    if (features.length === 0) return null;

    return { data: { type: 'FeatureCollection', features }, parser: { type: 'geojson' as const } };
  }, [source, lngField, latField, outerRadiusField, innerRadiusField, radiusUnit, clipChina, chinaGeom]);

  const labelConfig = label?.visible && label?.field
    ? { field: label.field, visible: true, style: label.style || {} } : undefined;

  // 公共 source：有数据时用 geoSource，否则用空 FeatureCollection（保证图层始终存在，支持可见性切换）
  const commonSource = geoSource || { data: { type: 'FeatureCollection', features: [] }, parser: { type: 'geojson' as const } };

  const showStroke = Boolean(lineWidth) && Boolean(strokeColor);
  // dashArray: solid → undefined, dash → [10, 5]
  const dashArray = lineType === 'dash' ? [10, 5] as [number, number] : undefined;

  return (
    <>
      <PolygonLayer {...({
        source: commonSource,
        shape: 'fill' as const, color: fillColor, style: { opacity: fillOpacity },
        minZoom, maxZoom, blend, zIndex, visible, label: labelConfig,
      } as any)} />
      {showStroke && (
        <LineLayer {...({
          source: commonSource,
          shape: 'line' as const,
          color: strokeColor,
          size: lineWidth,
          style: { lineType, dashArray, opacity: 1 },
          minZoom, maxZoom, blend, zIndex: (zIndex || 0) + 0.01, visible,
        } as any)} />
      )}
    </>
  );
};

export default GeoCircleLayerWrapper;
