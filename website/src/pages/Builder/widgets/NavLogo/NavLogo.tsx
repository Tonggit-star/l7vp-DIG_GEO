import { ArrowLeftOutlined } from '@ant-design/icons';
import type { ImplementEditorWidgetProps } from '@antv/li-editor';
import React from 'react';
import { Button } from 'antd';
import { history } from 'umi';
import classNames from 'classnames';
import { usePrefixCls } from '../../hooks';
import useStyle from './NavLogoStyle';

type NavLogoProps = ImplementEditorWidgetProps;

const NavLogo: React.FC<NavLogoProps> = (props) => {
  const prefixCls = usePrefixCls('logo');
  const styles = useStyle();

  const onClickBack = () => {
    history.push('/project');
  };

  return (
    <div className={classNames(prefixCls, styles.logo)}>
      <Button type="text" icon={<ArrowLeftOutlined />} onClick={onClickBack}>
        返回项目列表
      </Button>
    </div>
  );
};

export default NavLogo;
