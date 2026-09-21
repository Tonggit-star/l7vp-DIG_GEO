export default [
  {
    name: 'workspace',
    path: '/',
    component: '@/layouts/WorkSpace',
    routes: [
      {
        name: '首页',
        path: '/',
        redirect: '/project',
        disabled: false,
        hideInMenu: true,
      },
      {
        name: '项目管理',
        path: '/project',
        component: './Project',
        disabled: false,
        hideInMenu: true,
      },
      {
        name: '案例市场',
        path: '/case',
        component: './Case',
        disabled: false,
        hideInMenu: true,
      },
    ],
  },
  {
    name: 'asset-market',
    path: '/asset-market',
    component: '@/pages/AssetMarket',
  },
  {
    name: 'new-project',
    path: '/new',
    component: '@/pages/Project/New',
  },
  {
    name: 'map',
    path: '/',
    component: '@/layouts/Map',
    routes: [
      {
        name: 'builder',
        path: '/builder/:id',
        component: '@/pages/Builder',
      },
      {
        name: 'app',
        path: '/app/:id',
        component: '@/pages/Preview/App',
      },
      {
        // 用于预览编辑态示例，进入探索分析态
        name: 'template',
        path: '/template/:id',
        component: '@/pages/Preview/Template',
      },
      {
        // 共享/嵌入页面：纯地图，无编辑器UI
        name: 'share',
        path: '/share/:id',
        component: '@/pages/Share',
      },
    ],
  },
  {
    // 文档
    name: 'docs',
    path: '/docs',
    component: '@/pages/Docs',
  },
  { path: '/*', redirect: '/project' },
];
