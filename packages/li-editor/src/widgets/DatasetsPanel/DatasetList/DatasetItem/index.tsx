import {
  DeleteOutlined,
  EditOutlined,
  FormOutlined,
  InsertRowAboveOutlined,
  MoreOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import type { DatasetSchema, LayerSchema } from '@antv/li-sdk';
import { getUniqueId, useDatasetStreamCount } from '@antv/li-sdk';
import type { MenuProps } from 'antd';
import { Dropdown, message, Popconfirm, Space, Tooltip } from 'antd';
import classnames from 'classnames';
import { downloadText } from 'download.js';
import React, { useState } from 'react';
import DatasetName from '../../../../components/EditName';
import { useEditorDataset, useEditorState, usePrefixCls } from '../../../../hooks';
import useStyle from './style';

export type DatasetItemProps = {
  className?: string;
  onReplaceDataset: (datasetId: string) => void;
  onPreviewDataset: (datasetId: string) => void;
  /** 打开「编辑数据集」弹窗（就地改名称/列定义/流式参数等，不换数据源） */
  onEditDataset: (datasetId: string) => void;
  dataset: DatasetSchema;
};

// 解析图层绑定的数据集 ID：顶层 "dataset" 优先，其次 sourceConfig.datasetId。
// 需与后端 ApplicationAssembler#fillLayerFields 的解析顺序保持一致——删除数据集时，
// 用同规则级联删除绑定它的图层，避免保存后 spec.layers 仍携带引用已删除数据集的悬空图层。
const getLayerDatasetId = (layer: LayerSchema): string | undefined => {
  const topLevelDataset = (layer as unknown as { dataset?: unknown }).dataset;
  if (typeof topLevelDataset === 'string' && topLevelDataset) {
    return topLevelDataset;
  }
  return layer.sourceConfig?.datasetId;
};

const DatasetItem = (props: DatasetItemProps) => {
  const { dataset: datasetSchema, onReplaceDataset, onPreviewDataset, onEditDataset, className } = props;
  const { state, updateState } = useEditorState();
  const prefixCls = usePrefixCls('dataset-list');
  const styles = useStyle();
  const [isEditName, setIsEditName] = useState(false);
  const editorDataset = useEditorDataset(datasetSchema.id);
  const isLocalOrRemoteDataSource = editorDataset?.isLocalOrRemoteDataset;
  const [messageApi, messageContextHolder] = message.useMessage();
  // L7_INTEGRATION: 流式数据集的实时行数（来自 useStreamDatasets 同步的 FE 滑动窗口）。
  // 非流式数据集恒为 0 且无订阅推送，不引起多余重渲染。
  const streamLiveCount = useDatasetStreamCount(datasetSchema.id);

  const replaceDataset = () => {
    onReplaceDataset(datasetSchema.id);
  };

  const copyDataset = () => {
    const copyData: DatasetSchema = {
      ...datasetSchema,
      metadata: {
        ...datasetSchema.metadata,
        name: `${datasetSchema.metadata.name}copy`,
      },
      id: getUniqueId(datasetSchema.id),
    };
    updateState((draft) => {
      draft.datasets.push(copyData);
    });
    messageApi.success('复制成功');
  };

  const downloadDataset = () => {
    if (isLocalOrRemoteDataSource) {
      const { metadata } = editorDataset;
      downloadText(`${metadata.name}.json`, JSON.stringify(editorDataset.data));
    }
  };

  // L7_INTEGRATION: 流式数据集「重新加载」入口。
  // 判定：编辑器新建的流式数据集带 metadata.stream；服务端 assemble 回读的打 _stream 标记。
  const isStream = (datasetSchema.metadata as { stream?: boolean })?.stream === true
    || (datasetSchema as { _stream?: boolean })._stream === true;
  const [streamReloading, setStreamReloading] = useState(false);
  const reloadStreamData = async () => {
    const projectId = (window as any).__L7VP_PROJECT_ID__;
    if (!projectId) {
      messageApi.error('未获取到项目 ID');
      return;
    }
    setStreamReloading(true);
    try {
      // 后端串联：停作业 → 清空缓冲并广播空帧 → 重新提交作业（Kafka earliest 重读）
      const res = await fetch(
        `/api/projects/${projectId}/datasets/${datasetSchema.id}/stream/reload`,
        { method: 'POST' },
      );
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        throw new Error(data?.error || `HTTP ${res.status}`);
      }
      messageApi.success(
        `已清空并重新加载流式数据${data.clearedSubscribers ? `（已通知 ${data.clearedSubscribers} 个在线页面清空）` : ''}`,
      );
    } catch (err: any) {
      messageApi.error(`重新加载失败：${err?.message || err}`);
    } finally {
      setStreamReloading(false);
    }
  };

  const onChangeName = (newName: string) => {
    updateState((draft) => {
      const findIndex = draft.datasets.findIndex((source) => source.id === datasetSchema.id);
      draft.datasets[findIndex].metadata.name = newName;
      setIsEditName(false);
    });
  };

  const getDelLayersCount = (dataSourceID: string) => {
    return state.layers.filter((layer) => getLayerDatasetId(layer) === dataSourceID).length;
  };

  const onDeleteDataset = () => {
    updateState((draft) => {
      const delDataIndex = draft.datasets.findIndex((source) => source.id === datasetSchema.id);
      draft.layers = draft.layers.filter((layer) => getLayerDatasetId(layer) !== datasetSchema.id);
      draft.datasets.splice(delDataIndex, 1);
    });
  };

  const dropDownItems: MenuProps['items'] = [
    {
      key: 'editDataset',
      label: <>编辑数据集</>,
      onClick() {
        onEditDataset(datasetSchema.id);
      },
    },
    {
      key: 'replaceDataset',
      label: <>替换数据集</>,
      onClick() {
        replaceDataset();
      },
    },
    {
      key: 'copyDataset',
      label: <>复制数据集</>,
      onClick() {
        copyDataset();
      },
    },
    {
      key: 'downloadDataset',
      label: <>导出数据集</>,
      onClick() {
        downloadDataset();
      },
    },
  ];

  return (
    <div className={classnames(`${prefixCls}__card`, styles.listCard, className)}>
      <div className={classnames(`${prefixCls}__info`, styles.listInfo)}>
        <DatasetName
          name={datasetSchema.metadata.name}
          isEdit={isEditName}
          onChange={onChangeName}
          onCancel={() => {
            setIsEditName(false);
          }}
        />

        <div
          className={classnames(`${prefixCls}__info-name`, styles.infoName)}
          onClick={(e) => {
            e.stopPropagation();
          }}
        >
          {isLocalOrRemoteDataSource ? (
            <>
              共
              <span className={classnames(`${prefixCls}__info-count`, styles.infoCount)}>
                {isStream ? streamLiveCount : (editorDataset?.data.length ?? 0)}
              </span>
              行数据{isStream ? '（实时）' : ''}
            </>
          ) : (
            editorDataset?.metadata.description
          )}
        </div>
      </div>

      <Space className={classnames(`${prefixCls}__actions`, styles.listActions)} onClick={(e) => e.stopPropagation()}>
        <Tooltip title="点击修改数据集名称">
          <FormOutlined
            data-comp="dataset-actions-item_hover-show"
            className={classnames(`${prefixCls}__actions-item`, styles.actionsItem)}
            onClick={() => {
              setIsEditName(true);
            }}
          />
        </Tooltip>
        <Tooltip title="编辑数据集（名称 / 字段定义 / 流式参数等）" placement="top">
          <EditOutlined
            data-comp="dataset-actions-item_hover-show"
            className={classnames(`${prefixCls}__actions-item`, styles.actionsItem)}
            onClick={(e) => {
              e.stopPropagation();
              onEditDataset(datasetSchema.id);
            }}
          />
        </Tooltip>
        {isStream && (
          <Popconfirm
            title="清空当前流式数据并从 Kafka 重新加载？"
            okText="确定"
            cancelText="取消"
            onConfirm={reloadStreamData}
          >
            <Tooltip
              title={streamReloading ? '正在重新加载…' : '重新加载流式数据（清空图层数据并从头重放 Kafka）'}
              placement="top"
            >
              {/* data-comp 用 _hover-show 后缀：.actionsItem 默认 opacity:0，仅当悬停 actions 区时
                  命中 [data-comp='dataset-actions-item_hover-show'] 才显示（与重命名/删除图标一致）。
                  此前用自定义 _stream-reload 永不显示，表现为按钮空白。 */}
              <ReloadOutlined
                spin={streamReloading}
                data-comp="dataset-actions-item_hover-show"
                className={classnames(`${prefixCls}__actions-item`, styles.actionsItem)}
                onClick={(e) => e.stopPropagation()}
              />
            </Tooltip>
          </Popconfirm>
        )}
        <Popconfirm
          title={
            <div>
              <span>你确定要删除{datasetSchema.metadata.name}吗</span>
              {getDelLayersCount(datasetSchema.id) ? (
                <p className={classnames(`${prefixCls}__popconfirm-title`, styles.popconfirmTitle)}>
                  删掉此数据集，会删除与此数据集关联的
                  <span className={classnames(`${prefixCls}__popconfirm-layers-count`, styles.popconfirmLayersCount)}>
                    {getDelLayersCount(datasetSchema.id)}
                  </span>
                  个图层
                </p>
              ) : null}
            </div>
          }
          placement="bottom"
          onConfirm={onDeleteDataset}
          okText="确定"
          cancelText="取消"
        >
          <Tooltip title="删除数据集" placement="top">
            <DeleteOutlined
              data-comp="dataset-actions-item_hover-show"
              className={classnames(`${prefixCls}__actions-item`, styles.actionsItem)}
              onClick={(e) => e.stopPropagation()}
            />
          </Tooltip>
        </Popconfirm>
        {isLocalOrRemoteDataSource && (
          <Tooltip title="点击查看数据集详情">
            <InsertRowAboveOutlined
              onClick={() => {
                onPreviewDataset(datasetSchema.id);
              }}
            />
          </Tooltip>
        )}
        <Dropdown menu={{ items: dropDownItems }}>
          <MoreOutlined />
        </Dropdown>
        {messageContextHolder}
      </Space>
    </div>
  );
};

export default DatasetItem;
