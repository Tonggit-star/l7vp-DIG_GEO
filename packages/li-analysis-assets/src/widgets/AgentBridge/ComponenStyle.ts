import { css } from '@emotion/css';
import { theme } from 'antd';

const useStyle = () => {
  const { useToken } = theme;
  const { token } = useToken();

  const { colorText, colorTextDescription, colorBgElevated, colorBorder, colorPrimary, boxShadowSecondary } = token;

  return {
    panel: css`
      position: absolute;
      right: 12px;
      bottom: 12px;
      z-index: 10;
      min-width: 200px;
      max-width: 260px;
      padding: 8px 12px;
      border-radius: 4px;
      border: 1px solid ${colorBorder};
      background: ${colorBgElevated};
      box-shadow: ${boxShadowSecondary};
      font-size: 12px;
      color: ${colorText};
      user-select: none;
    `,
    row: css`
      display: flex;
      align-items: center;
      gap: 8px;
    `,
    title: css`
      font-weight: 600;
    `,
    status: css`
      margin-left: auto;
      color: ${colorTextDescription};
    `,
    statusOn: css`
      color: ${colorPrimary};
    `,
    hint: css`
      margin-top: 4px;
      color: ${colorTextDescription};
      line-height: 16px;
    `,
    target: css`
      position: absolute;
      left: 0;
      top: 0;
      /* Marker 的锚点在目标经纬度上，整体上移一个自身高度，避免盖住目标图标 */
      transform: translate(8px, calc(-100% - 10px));
      z-index: 10;
      min-width: 180px;
      max-width: 280px;
      padding: 8px 12px;
      border-radius: 4px;
      border: 1px solid ${colorBorder};
      background: ${colorBgElevated};
      box-shadow: ${boxShadowSecondary};
      font-size: 12px;
      color: ${colorText};
      user-select: text;
    `,
    close: css`
      margin-left: auto;
      color: ${colorTextDescription};
      cursor: pointer;

      &:hover {
        color: ${colorPrimary};
      }
    `,
    kv: css`
      display: flex;
      gap: 8px;
      margin-top: 4px;
      line-height: 16px;
    `,
    k: css`
      flex: 0 0 auto;
      max-width: 45%;
      color: ${colorTextDescription};
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;
    `,
    v: css`
      flex: 1;
      min-width: 0;
      text-align: right;
      word-break: break-all;
    `,
  };
};

export default useStyle;
