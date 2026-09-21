import { Alert, DatePicker, Modal, Radio, Typography } from 'antd';
import dayjs from 'dayjs';
import React, { useEffect, useMemo, useState } from 'react';

/** 预设时间范围（相对「现在」）；custom 时用自定义区间 */
export type RangePreset = 'all' | '1h' | '6h' | '24h' | 'custom';

export type TimeRange = { start?: number; end?: number };

export type HistoryTrackModalProps = {
  open: boolean;
  /** 目标标识（如 mmsi），用于标题展示 */
  targetKey?: string;
  targetName?: string;
  /** 时序库里该数据集的实际数据时间窗（ISO 字符串），用于提示「近 N 小时」可能查不到数据 */
  dataRange?: { minTime?: string | null; maxTime?: string | null } | null;
  loading?: boolean;
  onCancel: () => void;
  onConfirm: (range: TimeRange, preset: RangePreset) => void;
};

const PRESET_HOURS: Record<string, number> = { '1h': 1, '6h': 6, '24h': 24 };

/**
 * 历史轨迹的时间范围选择弹窗。
 *
 * 默认「全部」：时序库里的数据往往是作业从 Kafka earliest 重放灌入的，
 * 相对当前时间的「近 N 小时」常常落在数据窗之外（实测本机数据滞后数天），
 * 因此弹窗里会展示实际数据时间窗做提示。
 */
const HistoryTrackModal: React.FC<HistoryTrackModalProps> = (props) => {
  const { open, targetKey, targetName, dataRange, loading, onCancel, onConfirm } = props;
  const [preset, setPreset] = useState<RangePreset>('all');
  const [customRange, setCustomRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null);

  // 每次打开都回到默认「全部」，避免沿用上一次的选择
  useEffect(() => {
    if (open) {
      setPreset('all');
      setCustomRange(null);
    }
  }, [open]);

  const rangeText = useMemo(() => {
    if (!dataRange || (!dataRange.minTime && !dataRange.maxTime)) return null;
    if (!dataRange.minTime || !dataRange.maxTime) return null;
    return `${dayjs(dataRange.minTime).format('YYYY-MM-DD HH:mm')} ~ ${dayjs(dataRange.maxTime).format('YYYY-MM-DD HH:mm')}`;
  }, [dataRange]);

  // 相对「现在」取窗时，若窗口整体晚于库内最后一条数据，则必然查不到
  const presetWillBeEmpty = useMemo(() => {
    const hours = PRESET_HOURS[preset];
    if (!hours || !dataRange?.maxTime) return false;
    return dayjs(dataRange.maxTime).valueOf() < Date.now() - hours * 3600 * 1000;
  }, [preset, dataRange]);

  const customIncomplete = preset === 'custom' && !customRange;

  const handleOk = () => {
    if (customIncomplete) return;
    if (preset === 'all') {
      onConfirm({}, 'all');
      return;
    }
    if (preset === 'custom' && customRange) {
      onConfirm({ start: customRange[0].valueOf(), end: customRange[1].valueOf() }, 'custom');
      return;
    }
    const hours = PRESET_HOURS[preset];
    const end = Date.now();
    onConfirm({ start: end - hours * 3600 * 1000, end }, preset);
  };

  return (
    <Modal
      title="历史轨迹"
      width={460}
      open={open}
      confirmLoading={loading}
      okText="查询并绘制"
      cancelText="取消"
      onCancel={onCancel}
      onOk={handleOk}
      okButtonProps={{ disabled: customIncomplete }}
      destroyOnClose
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        目标：{targetName ? `${targetName}（${targetKey}）` : targetKey}
      </Typography.Paragraph>

      {rangeText && (
        <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
          时序库现有数据时间范围：{rangeText}
        </Typography.Paragraph>
      )}

      <Radio.Group value={preset} onChange={(e) => setPreset(e.target.value)}>
        <Radio.Button value="all">全部</Radio.Button>
        <Radio.Button value="1h">近 1 小时</Radio.Button>
        <Radio.Button value="6h">近 6 小时</Radio.Button>
        <Radio.Button value="24h">近 24 小时</Radio.Button>
        <Radio.Button value="custom">自定义</Radio.Button>
      </Radio.Group>

      {preset === 'custom' && (
        <div style={{ marginTop: 12 }}>
          <DatePicker.RangePicker
            showTime
            style={{ width: '100%' }}
            value={customRange as any}
            onChange={(v) => setCustomRange(v as any)}
          />
        </div>
      )}

      {presetWillBeEmpty && (
        <Alert
          style={{ marginTop: 12 }}
          type="warning"
          showIcon
          message="该时间窗晚于库内最后一条数据，可能查不到轨迹，建议改选「全部」。"
        />
      )}
    </Modal>
  );
};

export default HistoryTrackModal;
