import type { LayerRegisterForm, LayerRegisterFormProps, LayerRegisterFormResultType } from '@antv/li-sdk';
import getSchema from './schema';

export type GeoCircleLayerVisConfig = {
  visible?: boolean;
  outerRadiusField: string;
  innerRadiusField?: string;
  radiusUnit: 'meters' | 'kilometers' | 'miles' | 'nauticalmiles';
  fillColor: string;
  fillOpacity: number;
  strokeColor: string;
  lineWidth: number;
  lineType: 'solid' | 'dash';
  clipChina?: boolean;
  label: {
    field?: string;
    visible: boolean;
    style: {
      fill: string;
      fontSize: number;
      textAnchor: 'center' | 'left' | 'right';
      textOffset: [number, number];
    };
  };
  minZoom: number;
  maxZoom: number;
  blend: string;
  coordinateType?: string;
};

const toValues = (config: LayerRegisterFormResultType<GeoCircleLayerVisConfig>) => {
  const { sourceConfig, visConfig } = config;
  const { parser } = sourceConfig;
  const coordinateType = (visConfig as any)?.coordinateType
    || (sourceConfig.parser?.geometry ? 'geometry' : 'table');
  const pointCoordinate = parser?.geometry
    ? { geometry: parser.geometry }
    : { longitude: parser?.x, latitude: parser?.y };

  const { outerRadiusField, innerRadiusField, radiusUnit, fillColor, fillOpacity, strokeColor, lineWidth, lineType, clipChina, label, minZoom, maxZoom, blend } =
    visConfig || {};

  return {
    coordinateType,
    ...pointCoordinate,
    outerRadiusField: outerRadiusField || '',
    innerRadiusField: innerRadiusField || '',
    radiusUnit: radiusUnit || 'meters',
    fillColor: fillColor || 'rgb(90, 216, 166)',
    fillOpacity: fillOpacity ?? 0.8,
    strokeColor: strokeColor || '#a9abb1',
    lineWidth: lineWidth ?? 1,
    lineType: lineType || 'solid',
    clipChina: clipChina || false,
    labelField: label?.field,
    labelColor: label?.style?.fill || '#c0c0c0',
    labelFontSize: label?.style?.fontSize || 15,
    labelTextAnchor: label?.style?.textAnchor || 'center',
    zoom: [minZoom || 0, maxZoom || 24],
    blend: blend || 'normal',
  };
};

const fromValues = (values: Record<string, any>): LayerRegisterFormResultType<GeoCircleLayerVisConfig> => {
  const coordinateType = values.coordinateType || 'table';
  const pointCoordinate = coordinateType === 'geometry'
    ? { geometry: values.geometry }
    : { x: values.longitude, y: values.latitude };
  const sourceConfig = {
    parser: {
      type: 'json' as const,
      ...pointCoordinate,
    },
  };

  const visConfig: GeoCircleLayerVisConfig = {
    outerRadiusField: values.outerRadiusField || '',
    innerRadiusField: values.innerRadiusField || '',
    radiusUnit: values.radiusUnit || 'meters',
    fillColor: values.fillColor || 'rgb(90, 216, 166)',
    fillOpacity: values.fillOpacity ?? 0.8,
    strokeColor: values.strokeColor || '#a9abb1',
    lineWidth: values.lineWidth ?? 1,
    lineType: values.lineType || 'solid',
    clipChina: values.clipChina || false,
    label: {
      field: values.labelField,
      visible: Boolean(values.labelField),
      style: {
        fill: values.labelColor || '#c0c0c0',
        fontSize: values.labelFontSize || 15,
        textAnchor: values.labelTextAnchor || 'center',
        textOffset: [0, 0],
      },
    },
    minZoom: values.zoom?.[0] ?? 0,
    maxZoom: values.zoom?.[1] ?? 24,
    blend: values.blend || 'normal',
    coordinateType,
  };

  return { sourceConfig, visConfig };
};

export default (props: LayerRegisterFormProps): LayerRegisterForm<GeoCircleLayerVisConfig> => {
  const schema = getSchema(props.datasetFields);
  return { schema, toValues, fromValues };
};
