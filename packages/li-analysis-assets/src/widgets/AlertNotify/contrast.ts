/**
 * 告警行的文字配色。
 *
 * 背景：告警行会**内联一个浅色级别底色**（紧急 #FFF1F0 / 告警 #FFF7CC），而承载本组件的
 * Builder 页用的是 antd **深色**主题（`website/src/constants/theme.ts` 的 darkAlgorithm，
 * `colorTextBase: rgba(255,255,255,0.85)`）。标题取 `colorText`、描述与时间取
 * `colorTextDescription`（rgba(255,255,255,0.45)），于是「近白字 + 近白底」叠在一起，
 * 描述那行基本看不见（用户报的问题）。
 *
 * 所以文字色要**按实际底色反着选**：底色偏亮 → 用深色字；底色透明或本身偏暗（通知级、
 * 或用户把底色配成了深色）→ 返回 `null`，意思是「沿用主题色」，深色主题下正好是浅字。
 * 用户可在属性面板自定义各级配色，故按颜色本身算亮度，不写死级别。
 */

export type RowTextColors = {
  /** 标题：正文色 */
  text: string;
  /** 描述 / 时间：次级色，比正文淡一档但仍在浅底上清晰可读 */
  secondary: string;
};

/** 浅底上的深色字。固定黑系、不跟主题走 —— 深色主题下没有「深色文字」这个 token */
const ON_LIGHT_TEXT = 'rgba(0, 0, 0, 0.88)';
const ON_LIGHT_SECONDARY = 'rgba(0, 0, 0, 0.6)';

/** 感知亮度高于此值即认为底色是浅色（#FFF1F0≈0.95、#FFF7CC≈0.96，均远高于阈值） */
const LIGHT_LUMINANCE = 0.6;

/** '#abc' / '#aabbcc' / 'rgb(1,2,3)' / 'rgba(1,2,3,.5)' → [r,g,b]；认不出返回 null */
const parseColor = (color: string): [number, number, number] | null => {
  const value = color.trim();

  const hex = value.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i);
  if (hex) {
    const h = hex[1];
    const full = h.length === 3 ? h.replace(/./g, (c) => c + c) : h;
    return [
      parseInt(full.slice(0, 2), 16),
      parseInt(full.slice(2, 4), 16),
      parseInt(full.slice(4, 6), 16),
    ];
  }

  const rgb = value.match(/^rgba?\(([^)]+)\)$/i);
  if (rgb) {
    const parts = rgb[1]
      .split(',')
      .slice(0, 3)
      .map((p) => parseFloat(p));
    if (parts.length === 3 && parts.every((n) => Number.isFinite(n))) {
      return [parts[0], parts[1], parts[2]];
    }
  }

  return null;
};

/**
 * 该底色下该用什么文字色；`null` = 沿用主题色（底色透明 / 认不出 / 本身偏暗）。
 */
export const pickRowTextColors = (bg: string | undefined): RowTextColors | null => {
  if (!bg) return null;

  const rgb = parseColor(bg);
  if (!rgb) return null;

  // 感知亮度（人眼对绿最敏感），够用且不引依赖
  const [r, g, b] = rgb;
  const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
  if (luminance < LIGHT_LUMINANCE) return null;

  return { text: ON_LIGHT_TEXT, secondary: ON_LIGHT_SECONDARY };
};
