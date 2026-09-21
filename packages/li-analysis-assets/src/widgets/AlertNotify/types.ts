/** 告警级别：紧急 > 告警 > 通知 */
export type AlertLevel = 'urgent' | 'warning' | 'notice';

/**
 * 一条告警信息。
 *
 * 这个结构就是告警数据源与面板之间的**契约**：以后接真实数据（HTTP 轮询 / WS 推送）时，
 * 后端按这个结构产出即可，面板这一侧不用改（见 source.ts 的说明）。
 */
export interface AlertItem {
  /** 唯一 ID，用于去重与高亮标记 */
  id: string;
  /** 级别 */
  level: AlertLevel;
  /** 一句话主体，如「『鲁荣渔 51234』驶入禁航区 A3」 */
  title: string;
  /** 次要说明，如「航速 11.2 kn，持续 4 分钟」 */
  desc?: string;
  /** 关联目标标识（船编号 / MMSI）。本期只存不用，留给后续「点击定位到目标」 */
  target?: string;
  /** 发生时间（epoch 毫秒） */
  time: number;
  /** 来源标识，如 mock / ais / 中台 */
  source?: string;
}
