import React from 'react';
import type { AlertLevel } from './types';

/** 组件名称, 前缀 */
export const CLS_PREFIX = 'li-analysis-alert-notify';

/** 级别权重：小的排前面（紧急置顶，告警次之，通知最后） */
export const LEVEL_WEIGHT: Record<AlertLevel, number> = {
  urgent: 0,
  warning: 1,
  notice: 2,
};

export const LEVEL_LABEL: Record<AlertLevel, string> = {
  urgent: '紧急',
  warning: '告警',
  notice: '通知',
};

/** 默认级别色：紧急红 / 告警（深黄字配淡黄底）/ 通知用主题常规色 */
export const DEFAULT_LEVEL_COLOR: Record<AlertLevel, string> = {
  urgent: '#FF4D4F',
  warning: '#D48806',
  notice: '',
};

export const DEFAULT_LEVEL_BG: Record<AlertLevel, string> = {
  urgent: '#FFF1F0',
  warning: '#FFF7CC',
  notice: '',
};

/**
 * 「刚到的」判定窗口：只有 time 落在这个窗口内的新条目才闪烁高亮。
 * 意义是「刚到的新消息提醒一下」，而不是把首屏灌进来的历史数据全闪一遍。
 */
export const FRESH_HIGHLIGHT_MS = 30_000;

/** 高亮持续时长 */
export const HIGHLIGHT_MS = 2000;

export const ICON = () => {
  return (
    <svg viewBox="0 0 64 64" width="1em" height="1em" fill="currentColor">
      <path
        fill="currentColor"
        d="M32 6a4 4 0 0 1 4 4v2.2c8.2 1.7 14 8.9 14 17.4v8.8l4.6 7.2A3 3 0 0 1 52 51H12a3 3 0 0 1-2.6-4.4L14 39.4v-8.8c0-8.5 5.8-15.7 14-17.4V10a4 4 0 0 1 4-4zm-7 49h14a7 7 0 0 1-14 0z"
      />
    </svg>
  );
};
