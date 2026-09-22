// 前端运行时配置 —— 挂载到容器 /opt/l7vp/web/config.js，由 index.html 的
// <script src="/config.js"> 在【浏览器】里加载，写入 window.L7VP_CONFIG。
//
// ⚠️ 为什么必须是文件而不是环境变量：读它的是浏览器，不是容器里的 Java 进程。
//    容器环境变量只活在服务端，浏览器看不到 —— 把 wsPort 之类放进 .env 不会有任何效果。
//
// 改完这个文件【不需要重启容器】（浏览器每次加载都重新请求 /config.js），
// 但浏览器可能缓存它 —— 验证时用 Ctrl+F5 强刷。
//
// 底图另有一条优先级更高的路：数据库 TILE_CONFIG 表（页面「底图配置」里改一次存库），
// 优先级 DB → 本文件 → 硬编码兜底。改底图优先用页面改，不必碰这个文件。

window.L7VP_CONFIG = {
  // 瓦片底图服务地址。
  // ⚠️ 默认指向高德公网。纯内网环境必须换成内网瓦片服务，否则地图是空白。
  tileLayerUrl: 'https://webst01.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}',

  // 底图预设列表。
  // ⚠️ 注意：当前版本【只读取 tileLayers[0].name】当底图名显示，
  //    各项的 url 字段并没有被消费（真正生效的是上面的 tileLayerUrl 或 DB 里的配置）。
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

  // 数据中台服务基地址。留空时中台数据源会报「未配置中台服务地址」。
  // 仅在后端 l7vp.auth.mode=sso 时可用；local 模式下 /api/zhongtai/* 一律 401。
  zhongtaiBaseUrl: 'http://10.16.1.6:8081',

  // 流式数据集 WebSocket 地址。留空则按下面的 wsPort 自动拼：
  //   ws://<当前页面hostname>:<wsPort>/ws/datasets/{datasetId}
  // 走 nginx/wss 时填完整地址覆盖（https 会自动转 wss）
  wsBaseUrl: '',

  // WebSocket 端口。容器内是 3002（对应 compose 的 L7VP_WS_PORT）。
  // ⚠️ 这里写的是【宿主机上对外暴露的映射端口】：compose 默认把容器 3002 映射到
  //    宿主机 3002，所以两边一致。如果你改了 L7VP_WS_PORT_HOST，这里要同步改。
  wsPort: 3002,

  // 流式数据集前端滑动窗口默认行数（数据集自带 metadata.maxWindow 时以后者为准）
  streamMaxWindow: 1000,

  // 注意：以下两个键在当前版本【无任何消费方】，改了不会有任何效果，
  // 保留只是为了和 website/public/config.js 保持一致，别浪费时间调它们。
  // GeoJSON 实际走硬编码的 /data/...，图标前缀实际由后端 l7vp.icons.url-prefix 决定。
  dataPath: '/data',
  iconPath: '/icons',
};
