import { css } from '@emotion/css';
import { theme } from 'antd';

const useStyle = () => {
  const { useToken } = theme;
  const { token } = useToken();

  const { colorText, colorBgElevated, colorBorder, colorPrimary, boxShadowSecondary } = token;

  return {
    menu: css`
      min-width: 108px;
      padding: 4px 0;
      border-radius: 4px;
      border: 1px solid ${colorBorder};
      background: ${colorBgElevated};
      box-shadow: ${boxShadowSecondary};
      user-select: none;
    `,
    menuItem: css`
      padding: 6px 14px;
      font-size: 13px;
      line-height: 20px;
      color: ${colorText};
      cursor: pointer;
      white-space: nowrap;

      &:hover {
        color: ${colorPrimary};
        background: ${colorBorder};
      }
    `,
    loading: css`
      position: absolute;
      left: 50%;
      top: 50%;
      transform: translate(-50%, -50%);
      z-index: 20;
      padding: 16px 24px;
      border-radius: 6px;
      background: ${colorBgElevated};
      box-shadow: ${boxShadowSecondary};
    `,
    legend: css`
      position: absolute;
      left: 12px;
      bottom: 12px;
      z-index: 10;
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 6px 12px;
      border-radius: 4px;
      border: 1px solid ${colorBorder};
      background: ${colorBgElevated};
      box-shadow: ${boxShadowSecondary};
      font-size: 12px;
      color: ${colorText};
    `,
    legendTitle: css`
      font-weight: 600;
    `,
    legendItem: css`
      display: inline-flex;
      align-items: center;
      gap: 4px;
    `,
    legendDot: css`
      width: 8px;
      height: 8px;
      border-radius: 50%;
      display: inline-block;
    `,
  };
};

export default useStyle;
