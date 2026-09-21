import { implementLayer } from '@antv/li-sdk';
import React from 'react';
import component from './Component';
import registerForm from './register-form';

const ICON = () => {
  return (
    <svg viewBox="0 0 64 64" width="1em" height="1em" style={{ fill: 'currentcolor' }}>
      <circle cx="32" cy="32" r="24" fill="none" stroke="currentColor" strokeWidth="3" />
      <circle cx="32" cy="32" r="4" />
      <line x1="32" y1="8" x2="32" y2="14" stroke="currentColor" strokeWidth="2" />
      <line x1="32" y1="50" x2="32" y2="56" stroke="currentColor" strokeWidth="2" />
      <line x1="8" y1="32" x2="14" y2="32" stroke="currentColor" strokeWidth="2" />
      <line x1="50" y1="32" x2="56" y2="32" stroke="currentColor" strokeWidth="2" />
    </svg>
  );
};

export default implementLayer({
  version: 'v0.1',
  metadata: {
    name: 'GeoCircleLayer',
    displayName: '地理圆图层',
    description: '根据点坐标和物理半径绘制地理圆，支持描边、文本标注等功能',
    type: 'Layer',
    icon: ICON,
    color: 'cyan',
  },
  defaultVisConfig: {
    visible: true,
    outerRadiusField: '',
    innerRadiusField: '',
    radiusUnit: 'meters' as const,
    fillColor: 'rgb(90, 216, 166)',
    fillOpacity: 0.8,
    strokeColor: '#a9abb1',
    lineWidth: 1,
    lineType: 'solid' as const,
    clipChina: false,
    label: {
      visible: false,
      field: undefined,
      style: {
        fill: '#c0c0c0',
        fontSize: 15,
        textAnchor: 'center' as const,
        textOffset: [0, 0] as [number, number],
      },
    },
    minZoom: 0,
    maxZoom: 24,
    blend: 'normal' as const,
  },
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  component: component as any,
  registerForm,
});
