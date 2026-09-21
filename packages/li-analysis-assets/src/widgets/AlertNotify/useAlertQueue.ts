import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  clampCursor,
  countByLevel,
  cursorOnArrival,
  enqueueAlerts,
  nextCursor,
  shouldHighlight,
  visibleAlerts,
} from './alertQueue';
import { HIGHLIGHT_MS } from './constants';
import type { AlertSource } from './source';
import type { AlertItem } from './types';

export type UseAlertQueueOptions = {
  /** 告警数据源（订阅后返回退订函数） */
  source: AlertSource;
  /** 同时显示条数 */
  displayCount: number;
  /** 队列上限（超出挤出排序末尾、也就是级别最低且最旧的） */
  queueSize: number;
  /** 轮播间隔（秒），<=0 表示不自动轮播 */
  intervalSec: number;
  /** 新紧急是否强制回顶 */
  urgentJumpTop: boolean;
};

/**
 * 告警队列的 React 包装：订阅数据源、维护队列与滚动窗口、轮播定时器、悬停暂停、新条高亮。
 * 队列本身（排序/入队/窗口）在 alertQueue.ts 里，是无 React 依赖的纯函数。
 */
export const useAlertQueue = (options: UseAlertQueueOptions) => {
  const { source, displayCount, queueSize, intervalSec, urgentJumpTop } = options;

  const [items, setItems] = useState<AlertItem[]>([]);
  const [cursor, setCursor] = useState(0);
  const [highlightId, setHighlightId] = useState<string | null>(null);
  const [paused, setPaused] = useState(false);
  const [arrivedCount, setArrivedCount] = useState(0);
  const [readCount, setReadCount] = useState(0);

  // 定时器与订阅回调里要读最新值：用 ref 镜像，避免把它们写进 effect 依赖导致反复重建定时器
  const itemsRef = useRef<AlertItem[]>(items);
  const cursorRef = useRef(0);
  const pausedRef = useRef(false);
  const arrivedCountRef = useRef(0);
  const optionsRef = useRef({ displayCount, queueSize, urgentJumpTop });
  const highlightTimerRef = useRef<number | undefined>(undefined);

  useEffect(() => {
    optionsRef.current = { displayCount, queueSize, urgentJumpTop };
  }, [displayCount, queueSize, urgentJumpTop]);

  useEffect(() => {
    pausedRef.current = paused;
  }, [paused]);

  const moveCursor = useCallback((next: number) => {
    cursorRef.current = next;
    setCursor(next);
  }, []);

  const handleArrival = useCallback(
    (item: AlertItem) => {
      const { queueSize: size, urgentJumpTop: jump } = optionsRef.current;

      setItems((prev) => {
        const next = enqueueAlerts(prev, [item], size);
        itemsRef.current = next;
        return next;
      });

      // 紧急置顶 / 告警靠前 / 通知不打断（策略见 alertQueue.ts）
      moveCursor(cursorOnArrival(cursorRef.current, item.level, jump));

      arrivedCountRef.current += 1;
      setArrivedCount(arrivedCountRef.current);

      // 只有「刚到的」紧急/告警才闪一下，首屏灌进来的历史数据不闪
      if (shouldHighlight(item, Date.now())) {
        setHighlightId(item.id);
        window.clearTimeout(highlightTimerRef.current);
        highlightTimerRef.current = window.setTimeout(() => setHighlightId(null), HIGHLIGHT_MS);
      }
    },
    [moveCursor],
  );

  // 订阅数据源
  useEffect(() => {
    const unsubscribe = source(handleArrival);
    return () => {
      unsubscribe();
      window.clearTimeout(highlightTimerRef.current);
    };
  }, [source, handleArrival]);

  // 轮播：窗口下移一行，到底回顶；悬停暂停时跳过（暂停只停轮播，不停收数）
  useEffect(() => {
    if (!(intervalSec > 0)) return undefined;

    const timer = window.setInterval(() => {
      if (pausedRef.current) return;
      const total = itemsRef.current.length;
      if (total <= optionsRef.current.displayCount) return;
      moveCursor(nextCursor(cursorRef.current, total));
    }, intervalSec * 1000);

    return () => window.clearInterval(timer);
  }, [intervalSec, moveCursor]);

  const safeCursor = clampCursor(cursor, items.length);
  const visible = useMemo(() => visibleAlerts(items, safeCursor, displayCount), [items, safeCursor, displayCount]);
  const counters = useMemo(() => countByLevel(items), [items]);

  const pause = useCallback(() => setPaused(true), []);
  const resume = useCallback(() => setPaused(false), []);
  const markRead = useCallback(() => setReadCount(arrivedCountRef.current), []);

  return {
    /** 可见窗口（最多 displayCount 条，按需循环滚动） */
    visible,
    /** 队列总条数 */
    total: items.length,
    /** 各级别计数 */
    counters,
    /** 未读条数（收起成铃铛时的角标） */
    unread: Math.max(0, arrivedCount - readCount),
    /** 正在闪烁高亮的条目 id */
    highlightId,
    /** 是否已暂停轮播（悬停触发） */
    paused,
    pause,
    resume,
    markRead,
  };
};
