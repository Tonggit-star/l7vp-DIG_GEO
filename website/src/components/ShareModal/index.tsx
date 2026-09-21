import { CopyOutlined, ExportOutlined } from '@ant-design/icons';
import { Button, Input, message, Modal, Space, Typography } from 'antd';
import React from 'react';

const { Text } = Typography;

interface ShareModalProps {
  visible: boolean;
  projectId: string;
  onClose: () => void;
}

const ShareModal: React.FC<ShareModalProps> = ({ visible, projectId, onClose }) => {
  const shareUrl = `${window.location.origin}/#/share/${projectId}`;

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(shareUrl);
      message.success('链接已复制到剪贴板');
    } catch {
      // Fallback for older browsers
      const input = document.createElement('input');
      input.value = shareUrl;
      document.body.appendChild(input);
      input.select();
      document.execCommand('copy');
      document.body.removeChild(input);
      message.success('链接已复制到剪贴板');
    }
  };

  const handlePreview = () => {
    window.open(shareUrl, '_blank');
  };

  return (
    <Modal
      title="共享链接"
      open={visible}
      onCancel={onClose}
      footer={null}
      destroyOnClose
      width={520}
    >
      <div style={{ marginBottom: 16 }}>
        <Text type="secondary">
          此链接可嵌入 DataEase 等可视化大屏中，打开后仅显示地图和交互组件，不含编辑器界面。
        </Text>
      </div>
      <Input.Group compact style={{ display: 'flex' }}>
        <Input
          value={shareUrl}
          readOnly
          style={{ flex: 1 }}
          onFocus={(e) => e.target.select()}
        />
        <Button icon={<CopyOutlined />} onClick={handleCopy}>
          复制
        </Button>
      </Input.Group>
      <div style={{ marginTop: 16, textAlign: 'right' }}>
        <Space>
          <Button icon={<ExportOutlined />} onClick={handlePreview}>
            预览
          </Button>
        </Space>
      </div>
    </Modal>
  );
};

export default ShareModal;
