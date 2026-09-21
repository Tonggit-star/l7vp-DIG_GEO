import { ShareAltOutlined } from '@ant-design/icons';
import type { ImplementEditorWidgetProps } from '@antv/li-editor';
import { Button, Tooltip } from 'antd';
import React, { useState } from 'react';
import { useParams } from 'umi';
import ShareModal from '@/components/ShareModal';

type ShareProps = ImplementEditorWidgetProps;

const Share: React.FC<ShareProps> = () => {
  const { id: projectId = '' } = useParams();
  const [visible, setVisible] = useState(false);

  return (
    <>
      <Tooltip placement="right" title="共享">
        <Button
          type="text"
          size="middle"
          shape="circle"
          icon={<ShareAltOutlined size={18} />}
          onClick={() => setVisible(true)}
        />
      </Tooltip>
      <ShareModal
        visible={visible}
        projectId={projectId}
        onClose={() => setVisible(false)}
      />
    </>
  );
};

export default Share;
