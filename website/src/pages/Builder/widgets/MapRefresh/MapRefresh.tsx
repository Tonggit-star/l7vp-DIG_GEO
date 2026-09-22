import { ClockCircleOutlined } from '@ant-design/icons';
import type { ImplementEditorWidgetProps } from '@antv/li-editor';
import { useEditorState } from '@antv/li-editor';
import { Alert, Badge, Button, InputNumber, message, Modal, Radio, Space, Tag, Tooltip } from 'antd';
import React, { useMemo, useState } from 'react';

type Props = ImplementEditorWidgetProps;

/** 预设档位：覆盖「秒级盯屏」到「分钟级看板」两种用法 */
const PRESETS: { label: string; value: number }[] = [
  { label: '关闭', value: 0 },
  { label: '1 秒', value: 1 },
  { label: '5 秒', value: 5 },
  { label: '10 秒', value: 10 },
  { label: '30 秒', value: 30 },
  { label: '1 分钟', value: 60 },
  { label: '5 分钟', value: 300 },
];

const PRESET_VALUES = PRESETS.map((p) => p.value);

/**
 * 地图刷新（SideNav 入口）。
 *
 * 这里设的刷新间隔**写在数据集的 `metadata.refreshInterval` 上**（秒），而不是另起一套计时器：
 * li-editor 与 li-sdk 两边的数据集查询本来就认这个字段，会把它转成 react-query 的
 * `refetchInterval`，每到一个周期就重跑一次数据服务、从数据源重新拉数。
 *
 * 所以这个入口只是「一次把项目里所有数据源数据集设成同一个值」的批量操作，
 * 好处是：① 只存一份配置、不产生第二套真值；② 走编辑器状态，会被 Builder 的自动保存落库，
 * 所以**配好的地图（预览页 / 嵌入页）打开时也是按这个间隔刷新的**——
 * 那两页不加载编辑器，认的是持久化下来的 metadata。
 *
 * 流式数据集（`metadata.stream`）不在此列：它有 WebSocket 实时推送，不需要轮询。
 */
const MapRefresh: React.FC<Props> = () => {
  const { state, updateState } = useEditorState();
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState<number>(0);

  const remoteDatasets = useMemo(
    () => ((state?.datasets || []) as any[]).filter((d) => d?.type === 'remote'),
    [state?.datasets],
  );

  const currentValues = remoteDatasets.map((d) => Number(d?.metadata?.refreshInterval) || 0);
  const distinctValues = Array.from(new Set(currentValues));
  const mixed = distinctValues.length > 1;
  // 各数据集不一致时，用第一个非零值作为入口的展示值（多数情况就是用户上次设的那个）
  const effectiveValue = currentValues.find((v) => v > 0) ?? 0;

  const handleOpen = () => {
    setPending(PRESET_VALUES.includes(effectiveValue) ? effectiveValue : effectiveValue || 5);
    setOpen(true);
  };

  const handleApply = () => {
    if (remoteDatasets.length === 0) {
      message.warning('当前项目里没有数据源数据集，无需设置刷新');
      return;
    }
    updateState((draft: any) => {
      (draft.datasets || []).forEach((d: any) => {
        // 流式数据集靠 WebSocket 推送，轮询对它是多余的
        if (d?.type !== 'remote') return;
        if (!d.metadata) d.metadata = { name: d.id };
        d.metadata.refreshInterval = pending;
      });
    });
    message.success(
      pending > 0
        ? `已设置每 ${pending} 秒刷新，预览页/嵌入页同样生效`
        : '已关闭自动刷新',
    );
    setOpen(false);
  };

  return (
    <>
      <Tooltip title="地图刷新" placement="right">
        <Badge dot={effectiveValue > 0} offset={[-2, 2]}>
          <ClockCircleOutlined onClick={handleOpen} style={{ cursor: 'pointer' }} />
        </Badge>
      </Tooltip>

      <Modal
        title="地图刷新"
        open={open}
        onCancel={() => setOpen(false)}
        onOk={handleApply}
        okText="应用"
        cancelText="取消"
        width={460}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="每次刷新都会重新从数据源拉取最新数据"
          description="作用于本项目全部数据源数据集，并随项目保存；预览页与嵌入页也按同一间隔刷新。流式数据集由 WebSocket 推送，不受此设置影响。"
        />

        {mixed && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message="当前各数据集的刷新间隔不一致"
            description="应用后会统一成同一个值。"
          />
        )}

        <div style={{ marginBottom: 8 }}>刷新频率</div>
        <Radio.Group
          value={PRESET_VALUES.includes(pending) ? pending : -1}
          onChange={(e) => setPending(e.target.value)}
          style={{ marginBottom: 12 }}
        >
          <Space wrap>
            {PRESETS.map((p) => (
              <Radio.Button key={p.value} value={p.value}>
                {p.label}
              </Radio.Button>
            ))}
          </Space>
        </Radio.Group>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
          <span>自定义</span>
          <InputNumber
            min={0}
            max={86400}
            value={PRESET_VALUES.includes(pending) ? undefined : pending}
            placeholder="秒"
            onChange={(v) => setPending(typeof v === 'number' ? v : 0)}
            addonAfter="秒"
          />
        </div>

        <div style={{ marginBottom: 8 }}>
          受影响的数据集（{remoteDatasets.length}）
        </div>
        {remoteDatasets.length === 0 ? (
          <div style={{ color: 'rgba(255,255,255,.45)' }}>
            暂无数据源数据集。可在「数据集」面板新增「数据库连接」或中台数据表数据集。
          </div>
        ) : (
          <Space wrap size={[4, 4]}>
            {remoteDatasets.map((d) => (
              <Tag key={d.id}>
                {d?.metadata?.name || d.id}
                <span style={{ opacity: 0.55, marginLeft: 4 }}>
                  {Number(d?.metadata?.refreshInterval) || 0}s
                </span>
              </Tag>
            ))}
          </Space>
        )}
      </Modal>
    </>
  );
};

export default MapRefresh;
