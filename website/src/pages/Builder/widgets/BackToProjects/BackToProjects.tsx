import { ArrowLeftOutlined } from '@ant-design/icons';
import type { ImplementEditorWidgetProps } from '@antv/li-editor';
import { Button, Tooltip } from 'antd';
import React from 'react';
import { history } from 'umi';

type BackToProjectsProps = ImplementEditorWidgetProps;

const BackToProjects: React.FC<BackToProjectsProps> = () => {
  return (
    <Tooltip placement="right" title="返回项目列表">
      <Button
        type="text"
        size="middle"
        shape="circle"
        icon={<ArrowLeftOutlined />}
        onClick={() => {
          history.push('/project');
        }}
      />
    </Tooltip>
  );
};

export default BackToProjects;
