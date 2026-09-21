import { genStyleHook } from '@formily/antd-v5/esm/__builtins__';

export default genStyleHook('icon-scale-selector-icon-item', (token) => {
  const {
    componentCls,
    controlItemBgHover,
    controlItemBgActive,
    borderRadius,
    colorTextDescription,
    zIndexPopupBase,
    padding,
  } = token;

  return {
    [componentCls]: {
      width: '100%',
      display: 'flex',
      alignItems: 'center',
      height: '28px',

      '&__select': {
        width: '100%',
      },

      '&__icon': {
        verticalAlign: 'middle',
        marginRight: '5px',
        zIndex: zIndexPopupBase + 50,

        '&__img': {
          width: '20px',
          height: '20px',
          display: 'flex',
          justifyContent: 'center',
          background: controlItemBgHover,
          verticalAlign: 'middle',
          borderRadius: `${borderRadius}px`,
          border: 0,
          cursor: 'pointer',

          '&:hover': {
            background: controlItemBgActive,
          },
        },
      },

      '&__icon-popover': {
        width: '280px',
        maxHeight: '400px',
        overflowY: 'auto',
      },

      '&__category-selector': {
        padding: `${padding}px`,
        borderBottom: '1px solid #f0f0f0',
        marginBottom: `${padding}px`,
      },

      '&__value': {
        flex: 1,
        overflow: 'hidden',
        textOverflow: 'ellipsis',
      },

      '&__delete': {
        width: '14px',
        padding: 0,
        color: colorTextDescription,

        '&-visible': {
          visibility: 'hidden',
        },
      },
    },
  };
});
