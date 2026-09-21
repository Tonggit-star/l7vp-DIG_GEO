import { CameraOutlined } from '@ant-design/icons';
import type { ImplementEditorWidgetProps } from '@antv/li-editor';
import { Button, Image, message, Modal } from 'antd';
import React, { useState } from 'react';
import { useParams } from 'umi';
import { updateProjectThumbnail } from '@/services';

type ScreenshotProps = ImplementEditorWidgetProps;

// 将 base64 data URL 转换为 Blob
function dataURLToBlob(dataURL: string): Blob {
  const arr = dataURL.split(',');
  const mime = arr[0].match(/:(.*?);/)?.[1] || 'image/jpeg';
  const bstr = atob(arr[1]);
  let n = bstr.length;
  const u8arr = new Uint8Array(n);
  while (n--) {
    u8arr[n] = bstr.charCodeAt(n);
  }
  return new Blob([u8arr], { type: mime });
}

// 压缩图片：缩放到最大宽度 + JPEG 格式
function compressImage(dataUrl: string, maxWidth: number, quality: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const img = new window.Image();
    img.onload = () => {
      let { width, height } = img;
      if (width > maxWidth) {
        height = Math.round((height * maxWidth) / width);
        width = maxWidth;
      }
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d');
      if (!ctx) { resolve(dataUrl); return; }
      ctx.drawImage(img, 0, 0, width, height);
      resolve(canvas.toDataURL('image/jpeg', quality));
    };
    img.onerror = () => resolve(dataUrl);
    img.src = dataUrl;
  });
}

const Screenshot: React.FC<ScreenshotProps> = (props) => {
  const [imageData, setImageData] = useState('');
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [uploading, setUploading] = useState(false);
  const { id: projectId } = useParams();

  const handleCapture = async () => {
    try {
      let imageDataUrl = '';

      // 通过全局 Scene 引用获取地图画面（与 ExportImage 控件相同的底层机制）
      const scene = (window as any).__l7_scene__;

      if (scene) {
        // 方式1：L7 Scene 的 exportPng
        if (typeof scene.exportPng === 'function') {
          try {
            imageDataUrl = await scene.exportPng('image/png');
          } catch (e) {
            console.log('exportPng failed, trying canvas:', e);
          }
        }
        // 方式2：Scene → Map.getCanvas()
        if (!imageDataUrl || imageDataUrl.length < 1000) {
          const map = typeof scene.getMap === 'function' ? scene.getMap() : null;
          const canvas = map?.getCanvas?.() || map?._canvas;
          if (canvas) {
            imageDataUrl = canvas.toDataURL('image/png');
          }
        }
      }

      // 兜底：DOM 查找可见 canvas
      if (!imageDataUrl || imageDataUrl.length < 1000) {
        const allCanvases = document.querySelectorAll('canvas');
        for (const c of Array.from(allCanvases)) {
          const canvas = c as HTMLCanvasElement;
          if (canvas.width > 200 && canvas.height > 200) {
            try {
              imageDataUrl = canvas.toDataURL('image/png');
              if (imageDataUrl && imageDataUrl.length > 1000) break;
            } catch (_) {}
          }
        }
      }

      if (imageDataUrl && imageDataUrl.length > 1000) {
        setImageData(imageDataUrl);
        setIsModalOpen(true);
      } else {
        message.error('截图失败：无法获取地图画面。请确保地图已完全加载后再试。');
      }
    } catch (error) {
      console.error('截图失败:', error);
      message.error('截图失败：' + (error as Error).message);
    }
  };

  const handleSaveThumbnail = async () => {
    if (!projectId) {
      message.error('项目ID不存在');
      return;
    }

    if (!imageData) {
      message.error('没有可保存的图片');
      return;
    }

    setUploading(true);

    try {
      // 先压缩图片（缩放到 1200px 宽 + JPEG 80% 质量），大幅减小文件大小
      const compressed = await compressImage(imageData, 1200, 0.8);
      const blob = dataURLToBlob(compressed);

      const formData = new FormData();
      formData.append('file', blob, `thumbnail_${projectId}.jpg`);

      const uploadResponse = await fetch('/api/thumbnails/upload', {
        method: 'POST',
        body: formData,
      });

      if (!uploadResponse.ok) {
        const errorText = await uploadResponse.text();
        console.error('Upload error:', errorText);
        throw new Error('上传失败: ' + uploadResponse.statusText);
      }

      const uploadResult = await uploadResponse.json();
      console.log('Upload result:', uploadResult);

      if (!uploadResult.success) {
        throw new Error(uploadResult.message || '上传失败');
      }

      // 将缩略图 URL 保存到数据库中
      await updateProjectThumbnail(projectId, uploadResult.url);

      message.success('封面保存成功');
      setIsModalOpen(false);
    } catch (error) {
      console.error('保存封面失败:', error);
      message.error('保存封面失败：' + (error as Error).message);
    } finally {
      setUploading(false);
    }
  };

  return (
    <>
      <Button
        type="text"
        size="middle"
        shape="circle"
        icon={<CameraOutlined size={18} />}
        onClick={handleCapture}
      />

      <Modal
        title="截取封面"
        open={isModalOpen}
        onCancel={() => setIsModalOpen(false)}
        className="li-export-image-control__model"
        footer={
          <>
            <Button onClick={() => setIsModalOpen(false)}>取消</Button>
            <Button type="primary" loading={uploading} onClick={handleSaveThumbnail}>
              保存为封面
            </Button>
          </>
        }
      >
        <Image 
          src={imageData} 
          alt="封面预览"
          style={{ maxWidth: '100%', maxHeight: '500px' }}
        />
      </Modal>
    </>
  );
};

export default Screenshot;
