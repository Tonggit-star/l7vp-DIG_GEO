import type { LayerSchema } from '@antv/li-sdk';
import type { DemoDataSource } from './types';

export const DEMO_CASE: {
  imgUrl: string;
  dataSources: DemoDataSource[];
  demoName: string;
  layerList: LayerSchema[];
}[] = [
  {
    imgUrl: 'https://gw.alipayobjects.com/zos/antfincdn/3Cb1AYfvlq/44e305b2-498e-4ff3-924b-bba7c8fdafda.png',
    dataSources: [
      {
        url:
          'https://mdn.alipayobjects.com/afts/file/A*aMdOS56bGT8AAAAAAAAAAAAADrd2AQ/Most-photographed-attractions-in-the-world.json',
        id: 'most-photographed-scenic',
        name: '全球拍照最多的景点',
        type: 'json',
      },
    ],
    demoName: '全球拍照最多的景点',
    layerList: [
      {
        id: 'most-photographed-scenic',
        type: 'BubbleLayer',
        metadata: {
          name: '全球拍照最多的景点',
        },
        sourceConfig: {
          datasetId: 'most-photographed-scenic',
          parser: { type: 'json', x: 'lng', y: 'lat' },
        },
        visConfig: {
          visible: true,
          radius: 3,
          fillColor: {
            field: 'value',
            value: [
              'rgb(102,37,6)',
              'rgb(153,52,4)',
              'rgb(204,76,2)',
              'rgb(236,112,20)',
              'rgb(254,153,41)',
              'rgb(254,196,79)',
              'rgb(254,227,145)',
            ],
            scale: { type: 'quantize' },
          },
          opacity: 1,
          lineWidth: 0,
          state: false,
          blend: 'additive',
          label: {
            field: undefined,
            visible: true,
            style: { fill: '#a9abb1', fontSize: 14, textAnchor: 'center' as const },
          },
        },
      },
    ],
  },
  {
    imgUrl: 'https://gw.alipayobjects.com/mdn/rms_e7e1c6/afts/img/A*8lyORIRMDNYAAAAAAAAAAAAAARQnAQ',
    dataSources: [
      {
        url: 'https://gw.alipayobjects.com/os/bmw-prod/5c4fdc5c-5cf7-46da-a361-f377938553dc.json',
        id: 'heat-demo-1',
        name: '全球地震热力分布',
        type: 'json',
      },
    ],
    demoName: '全球地震热力分布',
    layerList: [
      {
        id: 'heat-demo-1',
        type: 'HeatmapLayer',
        metadata: {
          name: '全球地震热力图层',
        },
        sourceConfig: {
          datasetId: 'heat-demo-1',
          parser: { type: 'json', x: 'lon', y: 'lat' },
        },
        visConfig: {
          visible: true,
          size: {
            field: 'mag',
            value: [0, 1],
          },
          style: {
            intensity: 4,
            radius: 4,
            opacity: 1,
            rampColors: {
              colors: ['#FF4818', '#F7B74A', '#FFF598', '#F27DEB', '#8C1EB2', '#421EB2'],
              positions: [0, 0.2, 0.4, 0.6, 0.8, 1.0],
            },
          },
        },
      },
    ],
  },
  {
    imgUrl: 'https://gw.alipayobjects.com/mdn/rms_e7e1c6/afts/img/A*SLbgR72KKFsAAAAAAAAAAAAAARQnAQ',
    dataSources: [
      {
        url: 'https://gw.alipayobjects.com/os/bmw-prod/0a544b66-a04b-4b98-9b69-d71258f5f577.json',
        id: 'arc-line-data',
        name: '国内外航班线数据',
        type: 'json',
      },
    ],
    demoName: '国内外航班线',
    layerList: [
      {
        id: 'arc-point-layer-examsple',
        type: 'BubbleLayer',
        metadata: {
          name: '国内外机场图层',
        },
        sourceConfig: {
          datasetId: 'arc-line-data',
          parser: { type: 'json', x: 'to_lon', y: 'to_lat' },
        },
        visConfig: {
          visible: true,
          zIndex: 1,
          radius: 5,
          fillColor: '#1890ff',
          opacity: 1,
          strokeColor: '#fff',
          lineWidth: 1,
          state: {
            active: { fillColor: false, strokeColor: 'yellow' },
            select: { fillColor: false, strokeColor: 'red' },
          },
        },
      },
      {
        id: 'arc-line-example',
        type: 'ArcLayer',
        metadata: {
          name: '国内外航班弧线图层',
        },
        sourceConfig: {
          datasetId: 'arc-line-data',
          parser: { type: 'json', x: 'from_lon', y: 'from_lat', x1: 'to_lon', y1: 'to_lat' },
        },
        visConfig: {
          zIndex: 2,
          visible: true,
          size: 1,
          style: {
            opacity: 1,
            sourceColor: '#1890ff',
            targetColor: '#1890ff',
          },
          state: { active: { color: 'yellow' } },
        },
      },
    ],
  },
];
