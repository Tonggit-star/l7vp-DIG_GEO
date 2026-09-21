import { BellOutlined, UpOutlined } from '@ant-design/icons';
import { CustomControl } from '@antv/larkmap';
import type { ImplementWidgetProps } from '@antv/li-sdk';
import { theme } from 'antd';
import cls from 'classnames';
import React, { useCallback, useMemo, useState } from 'react';
import AlertList from './AlertList';
import { CLS_PREFIX, DEFAULT_LEVEL_BG, DEFAULT_LEVEL_COLOR, LEVEL_LABEL } from './constants';
import { pickRowTextColors } from './contrast';
import { createMockSource } from './mockAlerts';
import type { Properties } from './registerForm';
import useStyle from './style';
import type { AlertLevel } from './types';
import { useAlertQueue } from './useAlertQueue';

export interface AlertNotifyProps extends ImplementWidgetProps, Properties {}

const LEVELS: AlertLevel[] = ['urgent', 'warning', 'notice'];

/**
 * 告警通知：地图右上角（默认）的滚动告警面板。
 *
 * - 排序：紧急 > 告警 > 通知，同级新的在前；**紧急一出现就置顶**，告警也拉回顶部，通知不打断当前视图；
 * - 轮播：同时显示 displayCount 条（默认 10），每 intervalSec 秒窗口下移一行、到底回顶部；
 * - 悬停暂停轮播；收起后变成带未读角标的铃铛。
 *
 * 数据来自 `AlertSource`（见 source.ts）：本期是演示数据源（20 条种子 + 可选模拟新信息），
 * 以后换成 HTTP 轮询或 WS 推送的实现即可，本组件不用改。
 */
const AlertNotify: React.FC<AlertNotifyProps> = (props) => {
  const {
    enabled = true,
    position = 'topright',
    title = '实时告警',
    displayCount = 10,
    queueSize = 100,
    width = 320,
    maxHeight = 420,
    intervalSec = 5,
    pauseOnHover = true,
    urgentJumpTop = true,
    showLevelCount = true,
    urgentColor,
    urgentBg,
    warningColor,
    warningBg,
    noticeColor,
    noticeBg,
    simulate = true,
    simulateIntervalSec = 10,
  } = props;

  const { token } = theme.useToken();
  const [collapsed, setCollapsed] = useState(false);
  const styles = useStyle({ urgentColor: urgentColor || DEFAULT_LEVEL_COLOR.urgent });

  const colorOf = useCallback(
    (level: AlertLevel) => {
      if (level === 'urgent') return urgentColor || DEFAULT_LEVEL_COLOR.urgent;
      if (level === 'warning') return warningColor || DEFAULT_LEVEL_COLOR.warning;
      // 通知用主题常规文字色，深色主题下也不会看不清
      return noticeColor || token.colorText;
    },
    [urgentColor, warningColor, noticeColor, token.colorText],
  );

  const bgOf = useCallback(
    (level: AlertLevel) => {
      if (level === 'urgent') return urgentBg || DEFAULT_LEVEL_BG.urgent;
      if (level === 'warning') return warningBg || DEFAULT_LEVEL_BG.warning;
      return noticeBg || 'transparent';
    },
    [urgentBg, warningBg, noticeBg],
  );

  // 文字色跟着**实际底色**走：紧急/告警是浅底 → 深色字；通知透明底 → 主题色（见 contrast.ts）
  const textColorsOf = useCallback(
    (level: AlertLevel) => pickRowTextColors(bgOf(level)),
    [bgOf],
  );

  // 数据源：配置项变化时重建（会重新灌种子数据并重启模拟定时器）
  const source = useMemo(
    () => createMockSource({ simulate, intervalMs: simulateIntervalSec * 1000 }),
    [simulate, simulateIntervalSec],
  );

  // 注意：所有 hook 都在 enabled 判断之前，避免条件调用 hook
  const { visible, total, counters, unread, highlightId, paused, pause, resume, markRead } = useAlertQueue({
    source,
    displayCount,
    queueSize,
    intervalSec,
    urgentJumpTop,
  });

  const handleExpand = useCallback(() => {
    setCollapsed(false);
    markRead();
  }, [markRead]);

  if (!enabled) return null;

  if (collapsed) {
    return (
      <CustomControl position={position} className={CLS_PREFIX}>
        <div
          className={cls(styles.bell, `${CLS_PREFIX}__bell`)}
          title={`${title}（共 ${total} 条）`}
          onClick={handleExpand}
        >
          <BellOutlined />
          {unread > 0 && <span className={styles.bellBadge}>{unread > 99 ? '99+' : unread}</span>}
        </div>
      </CustomControl>
    );
  }

  return (
    <CustomControl position={position} className={CLS_PREFIX}>
      <div
        className={cls(styles.panel, `${CLS_PREFIX}__panel`)}
        style={{ width, maxHeight }}
        // 悬停暂停轮播（只停滚动，不停收数）
        onMouseEnter={pauseOnHover ? pause : undefined}
        onMouseLeave={pauseOnHover ? resume : undefined}
      >
        <div className={styles.header}>
          <span className={styles.title}>{title}</span>
          <span className={styles.counts}>
            {showLevelCount &&
              LEVELS.map((level) => (
                <span
                  key={level}
                  className={cls(styles.count, counters[level] === 0 ? styles.countZero : '')}
                  style={counters[level] === 0 ? undefined : { color: colorOf(level) }}
                >
                  {LEVEL_LABEL[level]} {counters[level]}
                </span>
              ))}
            {paused && <span className={styles.paused}>已暂停</span>}
          </span>
          <span className={styles.headerBtn} title="收起" onClick={() => setCollapsed(true)}>
            <UpOutlined />
          </span>
        </div>

        <div className={styles.list}>
          <AlertList
            items={visible}
            highlightId={highlightId}
            colorOf={colorOf}
            bgOf={bgOf}
            textColorsOf={textColorsOf}
            styles={styles}
          />
        </div>
      </div>
    </CustomControl>
  );
};

export default AlertNotify;
