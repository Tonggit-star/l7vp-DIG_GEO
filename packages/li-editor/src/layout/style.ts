import { css } from '@emotion/css';
import { useAntdToken } from '../hooks';

const useStyle = () => {
  const {
    colorBgLayout,
    colorBgContainer,
    colorFillSecondary,
    colorFill,
    colorText,
    colorTextSecondary,
    borderRadius,
  } = useAntdToken();

  return {
    editorLayout: css`
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      display: flex;
      width: 100vw;
      height: 100vh;
      overflow: hidden;

      /* 滚动条整体部分,必须要设置 */
      ::-webkit-scrollbar {
        width: 7px;
        height: 7px;
        appearance: none;
      }

      /* 滚动条的滑块按钮 */
      ::-webkit-scrollbar-thumb {
        background: ${colorFillSecondary};
        border-radius: 3px;
        cursor: pointer;

        &:hover {
          background-color: ${colorFill};
        }
      }

      /* 滚动条的轨道 */
      ::-webkit-scrollbar-track {
        background: none;
        border-radius: 0;
      }
    `,

    loading: css`
      position: absolute;
      top: 30px;
      left: 50%;
      z-index: 10;
      display: flex;
      align-items: center;
      justify-content: space-around;
      width: 180px;
      margin-left: 50px;
      padding: 15px;
      color: ${colorTextSecondary};
      background-color: ${colorBgContainer};
      border-radius: ${borderRadius}px;
    `,

    left: css`
      position: relative;
      display: flex;
      flex-shrink: 0;
      color: ${colorText};
      background-color: ${colorBgLayout};
    `,

    sideNav: css`
      z-index: 2;
      flex-shrink: 0;
    `,

    sidePanel: css`
      z-index: 1;
      width: 350px;
      flex-shrink: 0;
      transition: width 50ms ease 0s;
      overflow-y: auto;

      &_hidden {
        width: 0;
        visibility: hidden;
      }
    `,

    sidePanelHidden: css`
      width: 0;
      visibility: hidden;
      overflow: hidden;
    `,

    cavans: css`
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-align: center;
      transition: width 50ms ease 0s;
    `,
  };
};

export default useStyle;
