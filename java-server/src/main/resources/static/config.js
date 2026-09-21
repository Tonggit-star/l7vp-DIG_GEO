// 内网部署配置文件
// 直接修改此文件即可生效，无需重新构建
window.L7VP_CONFIG = {
  // 瓦片底图服务地址
  tileLayerUrl: 'https://webst01.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}',
  
  // 可配置多个底图
  tileLayers: [
    {
      id: 'satellite',
      name: '卫星影像底图',
      url: 'https://webst01.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}',
    },
    {
      id: 'normal',
      name: '普通地图底图',
      url: 'https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
    },
  ],
  
  // GeoJSON数据目录路径
  dataPath: '/data',
  
  // 图标目录路径
  iconPath: '/icons',

  // 数据中台服务基地址（含端口），用于拼接中台 API / 数据接口地址
  // 封闭环境部署时改成实际中台地址，改完无需重新构建
  zhongtaiBaseUrl: 'http://10.16.1.6:8081',

  // 流式数据集 WebSocket 服务地址（含端口）。
  // 留空则按 wsPort 拼：ws://<当前页面hostname>:<wsPort>/ws/datasets/{datasetId}
  // 后端走 nginx/wss 时填写完整地址覆盖，如 https://10.16.1.6（会自动转 wss）
  wsBaseUrl: '',

  // WebSocket 服务端口（单 jar 双端口：HTTP=3001，WS=3002）。
  // 仅当 wsBaseUrl 留空时生效；与后端 L7VP_WS_PORT 保持一致。
  wsPort: 3002,

  // 流式数据集前端滑动窗口默认行数（数据集 metadata.maxWindow 优先于此值）
  streamMaxWindow: 1000,
};
