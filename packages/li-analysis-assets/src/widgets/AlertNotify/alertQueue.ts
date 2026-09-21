import { FRESH_HIGHLIGHT_MS, LEVEL_WEIGHT } from './constants';
import type { AlertItem, AlertLevel } from './types';

/**
 * 告警队列的**纯逻辑**：排序、入队、滚动窗口、新条目到达时的游标策略。
 * 刻意不依赖 React（也不依赖任何 li 状态），方便单独验证与复用。
 */

/** 排序：级别优先（紧急 > 告警 > 通知），同级按时间倒序（新的在前） */
export const sortAlerts = (items: AlertItem[]): AlertItem[] =>
  [...items].sort((a, b) => {
    const weight = LEVEL_WEIGHT[a.level] - LEVEL_WEIGHT[b.level];
    return weight !== 0 ? weight : b.time - a.time;
  });

/** 按 id 去重，同一 id 后来的覆盖先前的 */
export const dedupeAlerts = (items: AlertItem[]): AlertItem[] => {
  const map = new Map<string, AlertItem>();
  items.forEach((item) => map.set(item.id, item));
  return [...map.values()];
};

/**
 * 入队：合并 → 去重 → 排序 → 超上限时**从尾部挤出**。
 * 因为排序后尾部就是「级别最低且时间最旧」的条目，所以挤出的正是最该丢的，
 * 紧急/告警不会被通知洪流挤掉。
 */
export const enqueueAlerts = (items: AlertItem[], incoming: AlertItem[], queueSize: number): AlertItem[] => {
  const merged = sortAlerts(dedupeAlerts([...items, ...incoming]));
  if (queueSize > 0 && merged.length > queueSize) {
    return merged.slice(0, queueSize);
  }
  return merged;
};

/** 游标取值范围 [0, total-1]，窗口按 total 取模循环，所以能滚到每一条 */
export const clampCursor = (cursor: number, total: number): number => {
  if (total <= 0) return 0;
  if (cursor < 0) return 0;
  return cursor >= total ? total - 1 : cursor;
};

/** 轮播：窗口下移一行，到底后回到顶部 */
export const nextCursor = (cursor: number, total: number): number => (total <= 0 ? 0 : (cursor + 1) % total);

/**
 * 取可见窗口。
 * 队列不足一屏时直接按序返回（不循环，避免同一条重复出现）；
 * 超过一屏时从游标开始取 displayCount 条并按 total 取模循环。
 */
export const visibleAlerts = (items: AlertItem[], cursor: number, displayCount: number): AlertItem[] => {
  const size = Math.max(1, Math.floor(displayCount) || 1);
  if (items.length === 0) return [];
  if (items.length <= size) return items;
  const start = clampCursor(cursor, items.length);
  return Array.from({ length: size }, (_, index) => items[(start + index) % items.length]);
};

/**
 * 新条目到达时的游标策略：
 * - 紧急：一旦出现就置顶（urgentJumpTop 关掉则保持当前视图）
 * - 告警：也靠前，拉回顶部
 * - 通知：不打断用户当前正在看的位置
 */
export const cursorOnArrival = (cursor: number, level: AlertLevel, urgentJumpTop: boolean): number => {
  if (level === 'urgent') return urgentJumpTop ? 0 : cursor;
  if (level === 'warning') return 0;
  return cursor;
};

/** 是否闪烁高亮：紧急/告警且是「刚到的」（通知不闪，历史数据不闪） */
export const shouldHighlight = (item: AlertItem, now: number, freshMs = FRESH_HIGHLIGHT_MS): boolean => {
  if (item.level === 'notice') return false;
  const age = now - item.time;
  return age >= 0 && age <= freshMs;
};

/** 各级别计数（给头部徽标用） */
export const countByLevel = (items: AlertItem[]): Record<AlertLevel, number> => {
  const counter: Record<AlertLevel, number> = { urgent: 0, warning: 0, notice: 0 };
  items.forEach((item) => {
    counter[item.level] += 1;
  });
  return counter;
};

/** 相对时间文案：刚刚 / N 分钟前 / N 小时前 / N 天前 */
export const formatAgo = (time: number, now: number = Date.now()): string => {
  const diff = Math.max(0, now - time);
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  return `${Math.floor(hours / 24)} 天前`;
};
