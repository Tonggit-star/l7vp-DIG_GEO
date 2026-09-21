import { DeleteOutlined, EditOutlined, PictureOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons';
import { Button, Empty, Form, Input, InputNumber, List, message, Modal, Popconfirm, Space, Spin, Upload } from 'antd';
import { useEffect, useState } from 'react';
import type { IconCategory, IconItem } from '@/types/icon';
import {
  getCategories, createCategory, updateCategory, deleteCategory,
  getIcons, uploadIcons, updateIconMeta, deleteIcon, moveIcon,
} from '@/services/icon';

interface IconLibraryModalProps {
  visible: boolean;
  onVisibleChange: (visible: boolean) => void;
}

const IconLibraryModal: React.FC<IconLibraryModalProps> = ({ visible, onVisibleChange }) => {
  const [categories, setCategories] = useState<IconCategory[]>([]);
  const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(null);
  const [icons, setIcons] = useState<IconItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [editingIconId, setEditingIconId] = useState<string | null>(null);
  const [editForm] = Form.useForm();

  // 加载分类列表
  useEffect(() => {
    if (visible) {
      getCategories().then(setCategories).catch(() => {});
    }
  }, [visible]);

  // 选中分类时加载图标
  useEffect(() => {
    if (selectedCategoryId) {
      setLoading(true);
      getIcons(selectedCategoryId).then(setIcons).finally(() => setLoading(false));
    } else {
      setIcons([]);
    }
  }, [selectedCategoryId]);

  const handleAddCategory = () => {
    Modal.confirm({
      title: '新增分类',
      content: <Input id="new-category-name" placeholder="请输入分类名称" />,
      onOk: async () => {
        const input = document.getElementById('new-category-name') as HTMLInputElement;
        if (input?.value) {
          const cat = await createCategory(input.value);
          setCategories((prev) => [...prev, cat]);
          message.success('分类已创建');
        }
      },
    });
  };

  const handleRenameCategory = (cat: IconCategory) => {
    let inputValue = cat.categoryName;
    Modal.confirm({
      title: '重命名分类',
      content: <Input defaultValue={cat.categoryName} onChange={(e) => { inputValue = e.target.value; }} />,
      onOk: async () => {
        if (inputValue) {
          await updateCategory(cat.categoryId, inputValue);
          setCategories((prev) => prev.map((c) => (c.categoryId === cat.categoryId ? { ...c, categoryName: inputValue } : c)));
          message.success('分类已重命名');
        }
      },
    });
  };

  const handleDeleteCategory = async (cat: IconCategory) => {
    await deleteCategory(cat.categoryId);
    setCategories((prev) => prev.filter((c) => c.categoryId !== cat.categoryId));
    if (selectedCategoryId === cat.categoryId) setSelectedCategoryId(null);
    message.success('分类已删除');
  };

  const handleUpload = async (options: any) => {
    const { file, onSuccess, onError } = options;
    if (!selectedCategoryId) {
      onError?.(new Error('请先选择分类'));
      return;
    }
    setUploading(true);
    try {
      const newIcons = await uploadIcons(selectedCategoryId, [file]);
      setIcons((prev) => [...prev, ...newIcons]);
      onSuccess?.('ok');
    } catch {
      onError?.(new Error('上传失败'));
      message.error('上传失败');
    }
    setUploading(false);
  };

  const handleEditIcon = (icon: IconItem) => {
    setEditingIconId(icon.iconId);
    editForm.setFieldsValue({ libraryCode: icon.libraryCode || '', codeName: icon.codeName || '' });
  };

  const handleSaveIcon = async (iconId: string) => {
    const values = await editForm.validateFields();
    try {
      await updateIconMeta(iconId, values.libraryCode, values.codeName);
      setIcons((prev) =>
        prev.map((i) => (i.iconId === iconId ? { ...i, libraryCode: values.libraryCode, codeName: values.codeName } : i)),
      );
      setEditingIconId(null);
      message.success('图标信息已更新');
    } catch (err: any) {
      if (err?.code === 409) {
        Modal.warning({
          title: '库号+代号重复',
          content: `${err.message || '该分类下已存在相同库号+代号的图标，请修改后重试。'}`,
        });
      } else {
        message.error('更新图标信息失败');
      }
    }
  };

  const handleDeleteIcon = async (iconId: string) => {
    await deleteIcon(iconId);
    setIcons((prev) => prev.filter((i) => i.iconId !== iconId));
    message.success('图标已删除');
  };

  const totalIcons = icons.length;

  return (
    <Modal
      title="图标库管理"
      open={visible}
      onCancel={() => onVisibleChange(false)}
      width={1200}
      footer={null}
      destroyOnClose
    >
      <div style={{ display: 'flex', height: 550, marginTop: 8 }}>
        {/* 左侧分类列表 */}
        <div style={{ width: 250, borderRight: '1px solid #aaadaf', paddingRight: 12, overflowY: 'auto' }}>
          <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <strong>分类</strong>
            <Button type="text" size="small" icon={<PlusOutlined />} onClick={handleAddCategory} />
          </div>
          <List
            size="small"
            dataSource={categories}
            renderItem={(cat) => (
              <List.Item
                key={cat.categoryId}
                style={{
                  cursor: 'pointer',
                  background: selectedCategoryId === cat.categoryId ? '#aaadaf' : undefined,
                  color: selectedCategoryId === cat.categoryId ? '#000' : undefined,
                  padding: '4px 1px',
                  borderRadius: 4,
                  // display: 'flex',
                  // alignItems: 'center',
                  // justifyContent: 'space-between',
                  // width: '100%',
                }}
                onClick={() => setSelectedCategoryId(cat.categoryId)}
                actions={[
                  <Button key="rename" type="text" size="small" icon={<EditOutlined />} onClick={(e) => { e.stopPropagation(); handleRenameCategory(cat); }} />,
                  <Popconfirm key="del" title={`确定删除分类"${cat.categoryName}"及其所有图标？`} onConfirm={(e) => { e?.stopPropagation(); handleDeleteCategory(cat); }}>
                    <Button type="text" size="small" icon={<DeleteOutlined />} onClick={(e) => e.stopPropagation()} />
                  </Popconfirm>,
                ]}
              >
                <div 
                  title={cat.categoryName} // 1. 鼠标悬浮显示完整名称
                  style={{
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    flex: 1, 
                    minWidth: 0,
                    marginRight: 0,
                }}>{cat.categoryName}
                </div>
                
              </List.Item>
            )}
          />
        </div>

        {/* 右侧图标网格 */}
        <div style={{ flex: 1, paddingLeft: 16, overflowY: 'auto' }}>
          {!selectedCategoryId ? (
            <Empty description="请选择左侧分类" />
          ) : (
            <>
              <div style={{ marginBottom: 12, display: 'flex', gap: 8 }}>
                <Upload multiple accept=".svg,.png,.jpg,.jpeg,.gif,.webp" showUploadList={false} customRequest={handleUpload}>
                  <Button icon={<UploadOutlined />} loading={uploading}>上传图标</Button>
                </Upload>
                <span style={{ color: '#999', lineHeight: '32px' }}>
                  {categories.find((c) => c.categoryId === selectedCategoryId)?.categoryName} — {totalIcons} 个图标
                </span>
              </div>

              {loading ? (
                <Spin />
              ) : icons.length === 0 ? (
                <Empty description="此分类暂无图标" />
              ) : (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(100px, 1fr))', gap: 12 }}>
                  {icons.map((icon) => (
                    <div
                      key={icon.iconId}
                      style={{
                        border: '1px solid #aaadaf',
                        borderRadius: 8,
                        padding: 8,
                        textAlign: 'center',
                        position: 'relative',
                      }}
                    >
                      <img src={icon.url} alt={icon.originalName} style={{ width: 60, height: 60, objectFit: 'contain' }} />
                      <div style={{ fontSize: 12, color: '#666', marginTop: 4, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {icon.originalName}
                      </div>

                      {editingIconId === icon.iconId ? (
                        <Form form={editForm} size="small" style={{ marginTop: 4 }}>
                          <Form.Item name="libraryCode" style={{ marginBottom: 4 }}>
                            <Input placeholder="库号" />
                          </Form.Item>
                          <Form.Item name="codeName" style={{ marginBottom: 4 }}>
                            <Input placeholder="代号" />
                          </Form.Item>
                          <Space size="small">
                            <Button size="small" type="primary" onClick={() => handleSaveIcon(icon.iconId)}>保存</Button>
                            <Button size="small" onClick={() => setEditingIconId(null)}>取消</Button>
                          </Space>
                        </Form>
                      ) : (
                        <>
                          {(icon.libraryCode || icon.codeName) && (
                            <div style={{ fontSize: 11, color: '#999', marginTop: 2 }}>
                              {icon.libraryCode || '-'} / {icon.codeName || '-'}
                            </div>
                          )}
                          <Space size="small" style={{ marginTop: 4 }}>
                            <Button size="small" type="text" icon={<EditOutlined />} onClick={() => handleEditIcon(icon)} />
                            <Popconfirm title="确定删除此图标？" onConfirm={() => handleDeleteIcon(icon.iconId)}>
                              <Button size="small" type="text" danger icon={<DeleteOutlined />} />
                            </Popconfirm>
                          </Space>
                        </>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      </div>

      {/* 底部状态栏 */}
      <div style={{ borderTop: '1px solid #aaadaf', marginTop: 12, paddingTop: 8, color: '#999', fontSize: 12 }}>
        共 {categories.length} 个分类
        {/* ，{totalIcons} 个图标 */}
      </div>
    </Modal>
  );
};

export default IconLibraryModal;
