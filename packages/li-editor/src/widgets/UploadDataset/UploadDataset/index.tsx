import { FileTextOutlined } from '@ant-design/icons';
import type { DatasetSchema } from '@antv/li-sdk';
import { Button, message, Space, Tooltip, Upload } from 'antd';
import { isEmpty } from 'lodash-es';
import type { UploadRequestOption } from 'rc-upload/lib/interface';
import React, { useState } from 'react';
import classNames from 'classnames';
import type { ImplementEditorAddDatasetWidgetProps } from '../../../types';
import UploadDatasetList from '../UploadDatasetList';
import UrlUpload from '../UrlUpload';
import { usePrefixCls } from '../../../hooks';
import { parserFileToSource } from './helpers/parser-file';
import useStyle from './style';

type UploadDatasetProps = ImplementEditorAddDatasetWidgetProps;

export default function UploadDataset(props: UploadDatasetProps) {
  const { onSubmit, onCancel } = props;
  const prefixCls = usePrefixCls('upload');
  const styles = useStyle();
  const [uploadData, setUploadData] = useState<DatasetSchema[]>([]);
  /* 选择的文件id */
  const [checkedDatasetIdList, setCheckedDatasetIdList] = useState<string[]>([]);
  const [messageApi, messageContextHolder] = message.useMessage();

  const customRequest = (uploadRequestOption: UploadRequestOption<any>) => {
    const { file, onSuccess } = uploadRequestOption;
    parserFileToSource(file as File)
      .then((dataSource) => {
        if (dataSource) {
          setUploadData((pre) => [...pre, dataSource]);
          // 将 Excel/CSV 的行数据通过 upload 端点直接写入 DATASET_ROWS
          const projectId = (window as any).__L7VP_PROJECT_ID__;
          const rawData = (dataSource as any).data;
          console.log('[UploadDataset] projectId=' + projectId + ' type=' + dataSource.type + ' dataLen=' + (rawData?.length || 0) + ' id=' + dataSource.id);
          if (projectId && dataSource.type === 'local' && rawData?.length > 0) {
            const columns = dataSource.columns || [];
            // 直接传对象数组，后端存储为 JSON（保持与 DatasetPreview 表格的 dataIndex 兼容）
            const rows = rawData;
            const payload = {
              id: dataSource.id,
              datasetName: dataSource.metadata?.name || (dataSource as any).name || '',
              type: dataSource.type,
              columns,
              rows,
            };
            // 使用当前页面同源地址，开发环境走 Umi proxy，生产环境走 Nginx 代理
            const url = '/api/projects/' + projectId + '/datasets/upload';
            console.log('[UploadDataset] POST ' + url + ' payloadSize=' + JSON.stringify(payload).length);
            fetch(url, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify(payload),
            }).then(async (res) => {
              if (!res.ok) {
                const errText = await res.text().catch(() => '');
                console.error('[UploadDataset] 行数据上传失败 HTTP ' + res.status, errText);
                messageApi.error('数据集行数据保存失败，请刷新后重试');
              } else {
                console.log('[UploadDataset] 行数据已上传, datasetId=' + dataSource.id + ', rows=' + rows.length);
              }
            }).catch((e) => {
              console.error('[UploadDataset] 行数据上传异常', e);
              messageApi.error('数据集上传网络错误');
            });
          }
        }
        // @ts-ignore
        onSuccess();
      })
      .catch((errorMessage) => {
        messageApi.error(errorMessage);
      });
  };

  const UploadDraggerContent = (
    <div className={classNames(`${prefixCls}-dataset__dragger-content`, styles.draggerContent)}>
      <span className={classNames(`${prefixCls}-dataset__dragger-content_icon`, styles.draggerContentIcon)}>
        <FileTextOutlined />
      </span>
      <div className={classNames(`${prefixCls}-dataset__dragger-content_text`, styles.draggerContentText)}>
        点击或将文件拖拽到这里，也可以文件 URL 地址上传
      </div>
      <UrlUpload
        onSubmit={(fileSource: DatasetSchema) => {
          setUploadData((pre) => [...pre, fileSource]);
        }}
      />
    </div>
  );

  return (
    <>
      <div className={prefixCls}>
        {/* <p className={classNames(`${prefixCls}__description`, styles.uploadDescription)}>
          支持的文件格式有{' '}
          <a href="https://www.yuque.com/antv/l7vp/data-format-csv" target="_blank" rel="noreferrer">
            CSV
          </a>
          、
          <a href="https://www.yuque.com/antv/l7vp/data-format-excel" target="_blank" rel="noreferrer">
            Excel
          </a>
          、
          <a href="https://www.yuque.com/antv/l7vp/data-format-json" target="_blank" rel="noreferrer">
            JSON
          </a>
          、
          <a href="https://www.yuque.com/antv/l7vp/data-format-geojson" target="_blank" rel="noreferrer">
            GeoJSON
          </a>
          、
          <Tooltip title="需压缩打包为 Zip 格式，上传的 Zip 至少包含 .shp、.dbf、.prj 文件">
            <a href="https://www.yuque.com/antv/l7vp/data-format-shapefile" target="_blank" rel="noreferrer">
              Shapefile
            </a>
          </Tooltip>
          ，了解使用方式详见{' '}
          <a href="https://www.yuque.com/antv/l7vp/data-formats" target="_blank" rel="noreferrer">
            使用文档
          </a>
          。
        </p> */}
        <p className={classNames(`${prefixCls}__description`, styles.uploadDescription)}>
          支持的文件格式有 CSV、Excel、JSON、GeoJSON、Shapefile。
        </p>
        <div className={classNames(`${prefixCls}__content`, styles.uploadContent)}>
          <div className={classNames(`${prefixCls}-dataset`, styles.uploadDataset)}>
            {messageContextHolder}
            <Upload.Dragger
              name="data"
              accept=".csv,.json,.xls,.xlsx,.geojson,.zip"
              customRequest={customRequest}
              showUploadList={false}
            >
              {UploadDraggerContent}
            </Upload.Dragger>
          </div>

          {!isEmpty(uploadData) && (
            <div className={classNames(`${prefixCls}-list`, styles.uploadList)}>
              <p className={classNames(`${prefixCls}-list__title`, styles.uploadListTitle)}>已上传文件</p>
              <UploadDatasetList
                dataset={uploadData}
                onChange={(e) => {
                  setCheckedDatasetIdList(e);
                }}
              />
            </div>
          )}
        </div>
      </div>

      <div className="li-upload__footer ant-modal-footer">
        <Space>
          <Button onClick={onCancel}>返回</Button>
          <Button
            disabled={isEmpty(checkedDatasetIdList) ? true : false}
            type="primary"
            onClick={() => {
              onSubmit(uploadData.filter((item) => checkedDatasetIdList.includes(item.id)));
            }}
          >
            添加
          </Button>
        </Space>
      </div>
    </>
  );
}
