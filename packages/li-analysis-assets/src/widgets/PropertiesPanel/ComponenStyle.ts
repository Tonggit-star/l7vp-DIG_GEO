import { css } from '@emotion/css';
import { theme } from 'antd';

const useStyle = () => {
  const { useToken } = theme;
  const { token } = useToken();

  const { colorBgContainer, zIndexPopupBase } = token;

  return {
    propertiesPanel: css`
      position: absolute;
      top: 0;
      right: 0;
      bottom: 0;
      z-index: ${zIndexPopupBase + 80};
      display: flex;
      flex-direction: column;
      box-sizing: border-box;
      overflow-x: hidden;
      overflow-y: auto;
      text-align: start;
      background-color: ${colorBgContainer};
      transition: width 0.5s 10ms cubic-bezier(0.075, 0.82, 0.165, 1);
    `,

    panelHeader: css`
      position: sticky;
      top: 0;
      z-index: 1;
      display: flex;
      align-items: center;
      justify-content: space-between;
      width: 100%;
      padding: 10px 14px;
      background-color: ${colorBgContainer};
      border-bottom: 1px solid #f0f0f0;
      flex-shrink: 0;
    `,

    panelContent: css`
      padding: 14px;
      overflow-x: hidden;
      word-break: break-all;
      overflow-wrap: break-word;
    `,

    panelHeaderTitle: css`
      flex: 1;
      overflow: hidden;
      font-weight: 600;
      font-size: 14px;
      white-space: nowrap;
      text-overflow: ellipsis;
    `,

    panelHeaderLabel: css`
      opacity: 0.45;
    `,
  };
};

export default useStyle;
