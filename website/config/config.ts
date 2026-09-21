import { defineConfig } from 'umi';
import { AnalyticsScripts } from './analytics';
import { getAssetDepExternal } from './external';
import routes from './routes';

// 离线部署模式：设置为true禁用外部CDN资源
const OFFLINE_MODE = true;

export default defineConfig({
  title: '地理可视化',
  // 部署于中台 nginx /l7vp 子路径（单 jar，前端静态内嵌后端）
  publicPath: '/l7vp/',
  metas: [
    {
      name: 'keywords',
      content:
        'L7VP, LocationInsight, L7, Location, 地理, 地图, 地理可视化, 可视化, 可视分析, 地理可视分析, 地图研发, 地图应用，可视分析工具',
    },
    {
      name: 'description',
      content: 'L7VP is an geospatial intelligent visualization analysis tools and development platform.',
    },
    // 离线模式移除CSP强制HTTPS升级
    ...(OFFLINE_MODE ? [] : [{ 'http-equiv': 'Content-Security-Policy', content: 'upgrade-insecure-requests' }]),
  ],
  history: {
    type: 'hash',
  },
  routes,
  // 增量发布和避免浏览器加载缓存
  hash: true,
  mfsu: false,
  // jsMinifier 默认为 esbuild，esbuild minify 污染全局变量 L7 问题
  esbuildMinifyIIFE: true,
  // 代理配置：开发环境将 /api 和 /thumbnails 请求转发到后端
  proxy: {
    '/api': {
      target: 'http://localhost:3001',
      changeOrigin: true,
      pathRewrite: { '^/api': '/api' },
    },
    '/thumbnails': {
      target: 'http://localhost:3001',
      changeOrigin: true,
    },
    // 流式数据集 WebSocket（开发环境代理到后端 3001）
    '/ws': {
      target: 'ws://localhost:3001',
      changeOrigin: true,
      ws: true,
    },
  },
  // 离线模式禁用外部CDN资源，全部打包到本地
  ...(OFFLINE_MODE ? {} : getAssetDepExternal()),
  // 离线模式禁用统计脚本
  scripts: OFFLINE_MODE ? [] : AnalyticsScripts,
  // 运行时配置 window.L7VP_CONFIG：从 public/config.js 加载（部署改该文件即可，无需重新构建）
  // 必须在应用代码前执行，故放 headScripts；之前 config.js 从未被引用，window.L7VP_CONFIG 一直是 undefined
  headScripts: [{ src: '/config.js' }],
  // 离线模式使用本地favicon
  favicons: OFFLINE_MODE
    ? ['/favicon.ico']
    : ['https://mdn.alipayobjects.com/huamei_qa8qxu/afts/img/A*WCVLT5Dp5oYAAAAAAAAAAAAADmJ7AQ/original'],
});
