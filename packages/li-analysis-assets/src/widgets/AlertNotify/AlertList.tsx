import cls from 'classnames';
import React from 'react';
import { formatAgo } from './alertQueue';
import { LEVEL_LABEL } from './constants';
import type { RowTextColors } from './contrast';
import type useStyle from './style';
import type { AlertItem, AlertLevel } from './types';

export type AlertListProps = {
  items: AlertItem[];
  /** 正在闪烁高亮的条目 id（刚到的紧急/告警） */
  highlightId: string | null;
  /** 级别 → 主色（边框 / 标签 / 高亮环） */
  colorOf: (level: AlertLevel) => string;
  /** 级别 → 底色 */
  bgOf: (level: AlertLevel) => string;
  /** 级别 → 文字色；null = 该级别底色不算浅，沿用主题色（见 contrast.ts） */
  textColorsOf: (level: AlertLevel) => RowTextColors | null;
  styles: ReturnType<typeof useStyle>;
};

const AlertList: React.FC<AlertListProps> = (props) => {
  const { items, highlightId, colorOf, bgOf, textColorsOf, styles } = props;

  if (items.length === 0) {
    return <div className={styles.empty}>暂无告警信息</div>;
  }

  return (
    <>
      {items.map((item) => {
        // 说清楚底是浅色时，标题/描述/时间都得换成深色字，否则近白字糊在近白底上（见 contrast.ts）
        const textColors = textColorsOf(item.level);
        const textStyle = textColors ? { color: textColors.text } : undefined;
        const secondaryStyle = textColors ? { color: textColors.secondary } : undefined;

        return (
          <div
            // 行内联 color = 级别色：左边框、级别标签、高亮环都取 currentColor
            key={item.id}
            className={cls(styles.row, item.id === highlightId ? styles.rowHighlight : '')}
            style={{ color: colorOf(item.level), background: bgOf(item.level) }}
            title={item.desc ? `${item.title}\n${item.desc}` : item.title}
          >
            <span className={styles.chip}>{LEVEL_LABEL[item.level]}</span>
            <div className={styles.body}>
              <div className={styles.rowTitle} style={textStyle}>
                {item.title}
              </div>
              {item.desc && (
                <div className={styles.rowDesc} style={secondaryStyle}>
                  {item.desc}
                </div>
              )}
            </div>
            <span className={styles.time} style={secondaryStyle}>
              {formatAgo(item.time)}
            </span>
          </div>
        );
      })}
    </>
  );
};

export default AlertList;
