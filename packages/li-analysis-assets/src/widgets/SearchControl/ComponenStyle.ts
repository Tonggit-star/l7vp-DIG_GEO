import { css } from '@emotion/css';
import { theme } from 'antd';

const useStyle = () => {
  const { useToken } = theme;
  const { token } = useToken();

  const { colorTextDescription, colorBgContainer, borderRadius, colorBorder } = token;

  return {
    searchControl: css`
      background: ${colorBgContainer};
      border-radius: ${borderRadius}px;
      border: 1px solid ${colorBorder};
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
      min-width: 260px;
    `,

    searchInput: css`
      .ant-select-selector {
        color: ${colorTextDescription};
      }
    `,

    searchOption: css`
      font-size: 14px;
      padding: 4px 0;
    `,
  };
};

export default useStyle;
