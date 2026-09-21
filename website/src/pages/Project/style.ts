import { css } from '@emotion/css';
import { theme } from 'antd';

const useStyle = () => {
  const { useToken } = theme;
  const { token } = useToken();

  const { colorTextSecondary, colorPrimaryText, colorText, colorBgElevated } = token;

  return {
    // project: css`
    //   padding: 0 95px;
    // `,

    project: css`
      padding: 0 95px;
      /* 使用视口高度，并减去外层 main 标签的 padding (上下各 24px，共 48px) */
      /* 如果你的页面有顶部导航栏 (通常是 64px)，请改为 calc(100vh - 64px - 48px) */
      height: calc(100vh - 48px); 
      overflow-y: auto;
      overflow-x: hidden;
      box-sizing: border-box; /* 确保 padding 不会额外增加高度 */
    `,



    // layoutContent: css`
    //   height: 100%; /* 因为 #root 已经是 100% 了，这里继承高度 */
    //   overflow-y: auto; /* 允许纵向滚动 */
    //   overflow-x: hidden; /* 隐藏横向滚动 */
    // `,

    projectHeader: css`
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 24px;
    `,

    projectTitle: css`
      font-size: 20px;
      font-weight: 600;
      color: ${colorPrimaryText};
    `,

    projectCard: css`
      cursor: pointer;
    `,

    projectCardImg: css`
      height: 200px;
      object-fit: cover;
      width: 100%;
    `,

    projectCardTools: css`
      position: absolute;
      top: 10px;
      right: 10px;
      border-radius: 20px;
      &:hover {
        background: ${colorBgElevated};
      }
    `,

    itemDescription: css`
      margin-bottom: 0 !important;
    `,

    addCard: css`
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      min-height: 113px;
      color: ${colorTextSecondary};
    `,

    addCardIcon: css`
      font-size: 20px;
      margin-bottom: 12px;
    `,

    importBtnIcon: css`
      transition: transform 0.1s linear, -webkit-transform 0.1s linear;
    `,
    importBtnIconRotate: css`
      transform: rotate(180deg);
    `,
  };
};

export default useStyle;
