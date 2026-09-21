import type { AlertSource } from './source';
import type { AlertItem, AlertLevel } from './types';

/**
 * 演示用告警数据（数据来源未定前先用这个）。
 * 20 条种子数据：3 紧急 / 6 告警 / 11 通知，时间在最近 40 分钟内递推；
 * 打开「模拟新信息」后按 simulateIntervalSec 继续随机产生新告警，用来演示排序与滚动。
 */

const MINUTE = 60_000;

const VESSELS = [
  '鲁荣渔 51234',
  '辽渔 18026',
  '浙岱渔 03511',
  '闽狮渔 07788',
  '粤电渔 10293',
  '远洋 3 号',
  '海巡 1103',
  '中远海运之星',
];

const MMSI = ['413256789', '412345678', '245272000', '477123456', '563098765', '209876543'];

type SeedSpec = {
  level: AlertLevel;
  title: string;
  desc?: string;
  target?: string;
  minutesAgo: number;
};

const SEED_SPECS: SeedSpec[] = [
  // ---- 紧急 3 条 ----
  {
    level: 'urgent',
    title: '『鲁荣渔 51234』驶入禁航区 A3',
    desc: '航速 11.2 kn，已持续 4 分钟，VHF 呼叫无应答',
    target: '413256789',
    minutesAgo: 3,
  },
  {
    level: 'urgent',
    title: '目标 245272000 与锚地船舶最近距离 0.2 nm',
    desc: '碰撞风险，最近会遇时间约 6 分钟',
    target: '245272000',
    minutesAgo: 14,
  },
  {
    level: 'urgent',
    title: '台风黄色预警：黄海中部阵风 11 级',
    desc: '预计 6 小时内影响编队『远洋 3 号』作业区',
    minutesAgo: 27,
  },
  // ---- 告警 6 条 ----
  {
    level: 'warning',
    title: 'AIS 信号丢失超过 30 分钟',
    desc: '目标 477123456，最后位置 121.35, 37.86',
    target: '477123456',
    minutesAgo: 5,
  },
  {
    level: 'warning',
    title: '航速异常：12.5 kn → 0.3 kn',
    desc: '『辽渔 18026』在主航道内骤停',
    target: '412345678',
    minutesAgo: 8,
  },
  {
    level: 'warning',
    title: '『浙岱渔 03511』偏离计划航线 3.2 nm',
    desc: '已连续 3 个报位点在航线右侧',
    target: '563098765',
    minutesAgo: 17,
  },
  {
    level: 'warning',
    title: '进入限制区缓冲区',
    desc: '『闽狮渔 07788』距限制区边界 1.4 nm',
    target: '209876543',
    minutesAgo: 22,
  },
  {
    level: 'warning',
    title: 'AIS 静态信息与登记信息不一致',
    desc: '目标 413256789 上报船名与船舶登记库不符',
    target: '413256789',
    minutesAgo: 31,
  },
  {
    level: 'warning',
    title: '通信中断：VHF 呼叫 3 次无应答',
    desc: '『粤电渔 10293』，最近报位 5 分钟前',
    target: '477123456',
    minutesAgo: 37,
  },
  // ---- 通知 11 条 ----
  {
    level: 'notice',
    title: '编队『远洋 3 号』离开渤海湾',
    desc: '共 6 条船，航向 118°',
    minutesAgo: 2,
  },
  { level: 'notice', title: '新船入网：MMSI 245272000', desc: '『中远海运之星』，首次报位', minutesAgo: 6 },
  { level: 'notice', title: '目标 477123456 恢复正常报位', desc: '中断 34 分钟后恢复', minutesAgo: 10 },
  { level: 'notice', title: '『海巡 1103』靠泊完成', desc: '泊位 B-07', minutesAgo: 12 },
  { level: 'notice', title: '数据通道切换至备用链路', desc: '主链路抖动，已自动切换', minutesAgo: 16 },
  { level: 'notice', title: '日巡检完成：18 个目标状态正常', desc: '无异常项', minutesAgo: 20 },
  { level: 'notice', title: '航线计划已更新（第 4 版）', desc: '调整 2 个转向点', minutesAgo: 25 },
  { level: 'notice', title: '气象数据刷新：能见度 8 km', desc: '数据时间 12:00', minutesAgo: 29 },
  { level: 'notice', title: '目标 209876543 切换至省电模式', desc: '报位间隔变为 10 分钟', minutesAgo: 34 },
  { level: 'notice', title: '编队『远洋 3 号』进入指定作业区', desc: '作业区 B-2', minutesAgo: 36 },
  { level: 'notice', title: '图标库同步完成，新增 6 个图标', desc: '分类：船舶', minutesAgo: 39 },
];

/** 生成 20 条种子数据；`now` 可传入以便测试/复现 */
export const buildMockAlerts = (now: number = Date.now()): AlertItem[] =>
  SEED_SPECS.map((spec, index) => ({
    id: `mock-seed-${index + 1}`,
    level: spec.level,
    title: spec.title,
    desc: spec.desc,
    target: spec.target,
    time: now - spec.minutesAgo * MINUTE,
    source: 'mock',
  }));

// ---------------------------------------------------------------- 模拟新信息

/** 级别池：1/10 紧急、3/10 告警、6/10 通知，保证紧急稀少但会出现 */
const LEVEL_POOL: AlertLevel[] = [
  'urgent',
  'warning',
  'warning',
  'warning',
  'notice',
  'notice',
  'notice',
  'notice',
  'notice',
  'notice',
];

const TEMPLATES: Record<AlertLevel, string[]> = {
  urgent: [
    '『{v}』驶入禁航区 A3',
    '目标 {mmsi} 与前方船舶距离 {d} nm（碰撞风险）',
    '『{v}』主机停车且漂航',
    '强对流预警：{area}阵风 10 级',
  ],
  warning: [
    'AIS 信号丢失超过 {m} 分钟',
    '航速异常：{s} kn → 0.3 kn',
    '『{v}』偏离计划航线 {d} nm',
    '进入限制区缓冲区，距边界 {d} nm',
    'VHF 呼叫 {m} 次无应答',
  ],
  notice: [
    '『{v}』靠泊完成',
    '新船入网：MMSI {mmsi}',
    '目标 {mmsi} 恢复正常报位',
    '航线计划已更新（第 {n} 版）',
    '{area}气象数据刷新：能见度 {n} km',
  ],
};

const DESCS: Record<AlertLevel, string[]> = {
  urgent: ['已持续 {m} 分钟，未响应呼叫', '最近会遇时间约 {m} 分钟', '位置 {lng}, {lat}，附近有 3 条船'],
  warning: ['最近报位 {m} 分钟前', '已连续 2 个报位点异常', '目标 {mmsi}，请核实'],
  notice: ['状态正常', '数据时间 {m} 分钟前', '无需处理'],
};

const AREAS = ['黄海中部', '渤海湾口', '长江口外', '台湾海峡北口', '舟山外海'];

const pick = <T>(list: T[]): T => list[Math.floor(Math.random() * list.length)];

const randomInt = (min: number, max: number) => min + Math.floor(Math.random() * (max - min + 1));

/** 模板占位符替换：`{v}` / `{mmsi}` / `{s}` / `{d}` / `{m}` / `{n}` / `{area}` / `{lng}` / `{lat}` */
const render = (text: string, ctx: Record<string, string | number>): string =>
  text.replace(/\{(\w+)\}/g, (match, key: string) => (key in ctx ? String(ctx[key]) : match));

let sequence = 0;

/** 随机造一条新告警（演示用） */
export const createRandomAlert = (now: number = Date.now()): AlertItem => {
  const level = pick(LEVEL_POOL);
  const ctx: Record<string, string | number> = {
    v: pick(VESSELS),
    mmsi: pick(MMSI),
    s: (randomInt(80, 160) / 10).toFixed(1),
    d: (randomInt(2, 60) / 10).toFixed(1),
    m: randomInt(2, 40),
    n: randomInt(2, 12),
    area: pick(AREAS),
    lng: (120 + Math.random() * 3).toFixed(2),
    lat: (35 + Math.random() * 4).toFixed(2),
  };
  sequence += 1;

  return {
    id: `mock-live-${now}-${sequence}`,
    level,
    title: render(pick(TEMPLATES[level]), ctx),
    desc: render(pick(DESCS[level]), ctx),
    target: String(ctx.mmsi),
    time: now,
    source: 'mock',
  };
};

export type MockSourceOptions = {
  /** 是否持续模拟新信息（演示用；接真实数据源后置 false） */
  simulate?: boolean;
  /** 新信息间隔（毫秒） */
  intervalMs?: number;
};

/**
 * 演示数据源：订阅时先灌入 20 条种子数据，之后按需定时产生新告警。
 * 注意种子数据是一次性同步推送的（不是 20 次定时），页面一打开就有内容。
 */
export const createMockSource = (options: MockSourceOptions = {}): AlertSource => {
  const { simulate = true, intervalMs = 10_000 } = options;

  return (onItem) => {
    const now = Date.now();
    buildMockAlerts(now).forEach((item) => onItem(item));

    if (!simulate) return () => {};

    const timer = window.setInterval(() => {
      onItem(createRandomAlert(Date.now()));
    }, Math.max(1000, intervalMs));

    return () => window.clearInterval(timer);
  };
};
