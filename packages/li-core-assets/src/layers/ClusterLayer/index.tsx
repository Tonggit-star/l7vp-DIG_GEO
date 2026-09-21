import { implementLayer } from '@antv/li-sdk';
import React from 'react';
import component from './Component';
import registerForm from './register-form';

const ICON = () => {
  return (
    <svg viewBox="0 0 64 64" width="1em" height="1em" style={{ fill: 'currentcolor' }}>
      {/* 簇图标：中间大圆(聚合) + 两个小圆(裂解后的目标)，示意可随缩放细分 */}
      <circle cx="32" cy="31" r="14" />
      <circle cx="52" cy="14" r="6" />
      <circle cx="12" cy="48" r="4.5" />
    </svg>
  );
};

export default implementLayer({
  version: 'v0.1',
  metadata: {
    name: 'ClusterLayer',
    displayName: '聚合图层(点)',
    description:
      '将点数据按空间聚合并显示数量，随缩放逐级细分至单点，适用于海量目标(船/飞机/人)概览。可切换为图标显示(默认圆点)，流式实时数据集同样支持',
    type: 'Layer',
    icon: ICON,
    color: 'orange',
  },
  // renderer: 'dot'=圆点(Bubble) / 'icon'=整层图标(IconImageLayer)，默认圆点。
  // 默认即按聚合数量 point_count 做 radius/fillColor 字段映射（WYSIWYG，勿在 Component 里兜底改写）。
  // label.field=point_count_abbreviated：该属性只存在于聚合节点（叶子仅有 point_count=1），
  // 因此数字只显示在聚合圆上，叶子自动无字。
  defaultVisConfig: {
    renderer: 'dot',
    radius: { field: 'point_count', value: [6, 60] },
    fillColor: {
      field: 'point_count',
      value: ['#edf8fb', '#ccece6', '#99d8c9', '#66c2a4', '#2ca25f', '#006d2c'],
      scale: { type: 'log' },
    },
    opacity: 0.8,
    strokeColor: '#a9abb1',
    lineWidth: 1,
    lineOpacity: 1,
    label: {
      field: 'point_count_abbreviated',
      style: {
        fill: '#3b3b3b',
        fontSize: 14,
        textAnchor: 'center' as const,
        textOffset: [0, 0] as [number, number],
        // 白描边保证数字在深色底图 / 深绿聚合圆上都可读（与图标模式的文本样式看齐）
        stroke: '#ffffff',
        strokeWidth: 1,
      },
    },
    state: {
      active: { fillColor: false, strokeColor: 'yellow' },
      select: { fillColor: false, strokeColor: 'red' },
    },
    minZoom: 0,
    maxZoom: 24,
    blend: 'normal' as const,
  },
  component,
  registerForm,
});
