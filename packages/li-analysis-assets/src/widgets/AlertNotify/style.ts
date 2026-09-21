import { css, keyframes } from '@emotion/css';
import { theme } from 'antd';
import { HIGHLIGHT_MS } from './constants';

/** 新到达的高亮：白色洗一下（行底色是内联的级别色，动画会短暂盖过它） */
const flash = keyframes`
  0% { background-color: rgba(255, 255, 255, 0.92); }
  100% { background-color: rgba(255, 255, 255, 0); }
`;

type UseStyleOptions = {
  /** 铃铛未读角标颜色 */
  urgentColor?: string;
};

const useStyle = (options: UseStyleOptions = {}) => {
  const { useToken } = theme;
  const { token } = useToken();
  const {
    colorText,
    colorTextDescription,
    colorBgElevated,
    colorBorder,
    colorBorderSecondary,
    colorPrimary,
    boxShadowSecondary,
  } = token;

  const badgeColor = options.urgentColor || '#FF4D4F';

  return {
    /** 面板：纵向 flex，高度受 maxHeight 限制，列表内部滚动 */
    panel: css`
      display: flex;
      flex-direction: column;
      overflow: hidden;
      border: 1px solid ${colorBorder};
      border-radius: 4px;
      background: ${colorBgElevated};
      box-shadow: ${boxShadowSecondary};
      font-size: 12px;
      color: ${colorText};
    `,
    header: css`
      display: flex;
      align-items: center;
      gap: 8px;
      flex: 0 0 auto;
      padding: 8px 10px;
      border-bottom: 1px solid ${colorBorderSecondary};
    `,
    title: css`
      /* 标题过长时省略，别把计数与按钮挤出去 */
      flex: 1 1 auto;
      min-width: 0;
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;
      font-weight: 600;
    `,
    counts: css`
      display: flex;
      flex: 0 0 auto;
      align-items: center;
      gap: 6px;
      font-size: 11px;
      white-space: nowrap;
    `,
    count: css`
      white-space: nowrap;
    `,
    countZero: css`
      color: ${colorTextDescription};
    `,
    paused: css`
      color: ${colorTextDescription};
    `,
    headerBtn: css`
      flex: 0 0 auto;
      color: ${colorTextDescription};
      cursor: pointer;

      &:hover {
        color: ${colorPrimary};
      }
    `,
    list: css`
      flex: 1 1 auto;
      min-height: 0;
      overflow-y: auto;
    `,
    row: css`
      display: flex;
      align-items: flex-start;
      gap: 6px;
      padding: 6px 10px 6px 8px;
      border-left: 3px solid transparent;
      border-bottom: 1px solid ${colorBorderSecondary};

      &:last-child {
        border-bottom: none;
      }
    `,
    rowHighlight: css`
      animation: ${flash} ${HIGHLIGHT_MS}ms ease-out;
      box-shadow: inset 0 0 0 1px currentColor;
    `,
    chip: css`
      flex: 0 0 auto;
      margin-top: 1px;
      padding: 0 4px;
      border: 1px solid currentColor;
      border-radius: 2px;
      font-size: 11px;
      line-height: 15px;
    `,
    body: css`
      flex: 1 1 auto;
      min-width: 0;
    `,
    rowTitle: css`
      line-height: 16px;
      word-break: break-all;
      /* 行内联的是级别色（供边框/标签/高亮环用 currentColor），标题仍用常规文字色 */
      color: ${colorText};
    `,
    rowDesc: css`
      color: ${colorTextDescription};
      line-height: 15px;
      word-break: break-all;
    `,
    time: css`
      flex: 0 0 auto;
      margin-top: 1px;
      color: ${colorTextDescription};
      font-size: 11px;
      white-space: nowrap;
    `,
    empty: css`
      padding: 16px 10px;
      color: ${colorTextDescription};
      text-align: center;
    `,
    /** 收起态：一个带未读角标的铃铛按钮 */
    bell: css`
      position: relative;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 32px;
      height: 32px;
      border: 1px solid ${colorBorder};
      border-radius: 4px;
      background: ${colorBgElevated};
      box-shadow: ${boxShadowSecondary};
      color: ${colorText};
      cursor: pointer;

      &:hover {
        color: ${colorPrimary};
      }
    `,
    bellBadge: css`
      position: absolute;
      top: -6px;
      right: -6px;
      min-width: 16px;
      height: 16px;
      padding: 0 4px;
      border-radius: 8px;
      background: ${badgeColor};
      color: #fff;
      font-size: 11px;
      line-height: 16px;
      text-align: center;
    `,
  };
};

export default useStyle;
