import type { Application } from '@antv/li-sdk';

export const APP_SCHEMA_VERSION = 'v0.1';

export const Empty_App_Schema: Application = {
  version: APP_SCHEMA_VERSION,
  metadata: { name: 'li application', description: 'li empty application' },
  datasets: [],
  spec: {
    map: {
      basemap: 'Gaode' as const,
      config: {
        zoom: 3,
        center: [120.153576, 30.287459] as [number, number],
        pitch: 0,
        bearing: 0,
        style: 'light',
        dragRotate: false,
        pitchWithRotate: false,
        jogEnable: false,
        touchRotate: false,
        doubleClickZoom: true,
        scrollZoom: true,
        // 保持绘图缓冲区，确保 canvas.toDataURL() 截图可用
        WebGLParams: { preserveDrawingBuffer: true },
      },
    },
    layers: [],
    widgets: [],
  },
};
