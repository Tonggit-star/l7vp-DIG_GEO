import { streamDatasetStats } from '@antv/li-sdk';
import { useEffect, useRef } from 'react';

/**
 * useStreamDatasets — 流式数据集 WebSocket 实时同步
 *
 * L7_INTEGRATION: 流式数据接入
 *
 * 为每个流式数据集 datasetId 建立到后端 /ws/datasets/{datasetId} 的 WebSocket 连接，
 * 接收 push 帧并维护前端滑动窗口，节流后写入 li-sdk 的 datasetStore，驱动图层重渲染。
 *
 * 协议（后端 → 前端）：
 *   { type: 'snapshot', datasetId, op: 'replace', rows: [...] }  首帧：当前缓冲全量
 *   { type: 'data', datasetId, op: 'append'|'replace', rows: [...] } 增量/全量更新
 *
 * @param runtimeApp  li-sdk 运行时（Builder: liEditor.runtimeApp；Share: liRuntimeApp）
 * @param datasetIds 需要订阅的流式数据集 id 列表
 * @param maxWindowByDataset 可选，datasetId → 滑动窗口大小；缺省读 L7VP_CONFIG.streamMaxWindow 或 1000
 * @param enabled    是否启用（应用未加载完成时传 false，避免连接过早）
 */
export const useStreamDatasets = (
  runtimeApp: any,
  datasetIds: string[],
  maxWindowByDataset?: Record<string, number>,
  enabled = true,
) => {
  const connsRef = useRef<Map<string, WebSocket>>(new Map());
  const windowsRef = useRef<Map<string, any[]>>(new Map());
  const timersRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());
  // 保持 datasetIds 引用稳定（按内容比较）
  const idsKey = datasetIds.slice().sort().join(',');
  const idsRef = useRef<string[]>([]);
  if (idsRef.current.join('|') !== datasetIds.slice().sort().join('|')) {
    idsRef.current = datasetIds.slice().sort();
  }

  useEffect(() => {
    if (!enabled || !runtimeApp) {
      // 关闭所有连接，并清空实时行数统计
      connsRef.current.forEach((ws) => {
        try {
          ws.close();
        } catch {
          /* ignore */
        }
      });
      Array.from(connsRef.current.keys()).forEach((id) => streamDatasetStats.removeDataset(id));
      connsRef.current.clear();
      windowsRef.current.clear();
      return;
    }

    const current = connsRef.current;
    const ids = idsRef.current;

    // 关闭已不需要的连接，并同步清空其行数统计
    Array.from(current.keys()).forEach((id) => {
      if (!ids.includes(id)) {
        try {
          current.get(id)?.close();
        } catch {
          /* ignore */
        }
        current.delete(id);
        windowsRef.current.delete(id);
        streamDatasetStats.removeDataset(id);
      }
    });

    // 为新增的 datasetId 建立连接
    ids.forEach((id) => {
      if (current.has(id)) return;
      const url = buildWsUrl(id);
      let ws: WebSocket;
      try {
        ws = new WebSocket(url);
      } catch (e) {
        console.warn('[STREAM] WebSocket 创建失败', id, e);
        return;
      }
      current.set(id, ws);
      windowsRef.current.set(id, []);

      const getMaxWindow = () =>
        maxWindowByDataset?.[id] ?? (window as any).L7VP_CONFIG?.streamMaxWindow ?? 1000;

      ws.onmessage = (ev) => {
        let frame: any;
        try {
          frame = JSON.parse(ev.data);
        } catch {
          return;
        }
        if (!frame || frame.datasetId !== id) return;
        const win = windowsRef.current.get(id) ?? [];
        if (frame.type === 'snapshot' || (frame.type === 'data' && frame.op === 'replace')) {
          windowsRef.current.set(id, Array.isArray(frame.rows) ? [...frame.rows] : []);
        } else if (frame.type === 'data' && frame.op === 'append') {
          const merged = [...win, ...(Array.isArray(frame.rows) ? frame.rows : [])];
          const max = getMaxWindow();
          while (merged.length > max) merged.shift();
          windowsRef.current.set(id, merged);
        }
        scheduleFlush(id);
      };

      ws.onerror = (e) => {
        console.warn('[STREAM] WebSocket 错误', id, e);
      };
      ws.onclose = () => {
        current.delete(id);
      };
    });

    return () => {
      // 卸载时关闭全部连接并清空定时器
      current.forEach((ws) => {
        try {
          ws.close();
        } catch {
          /* ignore */
        }
      });
      current.clear();
      timersRef.current.forEach((t) => clearTimeout(t));
      timersRef.current.clear();
      windowsRef.current.clear();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runtimeApp, idsKey, enabled]);

  /** 节流写入 datasetStore，避免高频帧导致重渲染风暴 */
  const scheduleFlush = (id: string) => {
    if (timersRef.current.has(id)) return;
    const t = setTimeout(() => {
      timersRef.current.delete(id);
      const dsStore = runtimeApp?.stateManager?.datasetStore;
      const data = windowsRef.current.get(id);
      if (dsStore && data) {
        dsStore.updateDataset(id, { data });
      }
      // 同步实时行数给数据集面板（流式行不落编辑器侧 data，面板据此展示实时数量）
      if (data) {
        streamDatasetStats.setCount(id, data.length);
      }
    }, 300);
    timersRef.current.set(id, t);
  };
};

/**
 * 构造 WebSocket 地址
 * 1) wsBaseUrl 非空（如 https://host 或 ws://host:3002）→ 完整覆盖
 * 2) 否则按 wsPort 拼：ws://<当前hostname>:<wsPort>/ws/datasets/{datasetId}
 *    —— 单 jar 双端口：前端从 3001 取页面，WS 直连 3002
 * 3) wsPort 也空 → 同源：ws://<当前host>/ws/datasets/{datasetId}（开发经 UMI /ws 代理）
 */
function buildWsUrl(datasetId: string): string {
  const cfg = (window as any).L7VP_CONFIG;
  const loc = window.location;
  const proto = loc.protocol === 'https:' ? 'wss' : 'ws';

  if (cfg?.wsBaseUrl) {
    const base = String(cfg.wsBaseUrl).replace(/\/$/, '');
    const wsProto = base.startsWith('https') ? 'wss' : base.startsWith('wss') ? 'wss' : 'ws';
    const host = base.replace(/^https?:\/\//, '').replace(/^wss?:\/\//, '');
    return `${wsProto}://${host}/ws/datasets/${datasetId}`;
  }
  if (cfg?.wsPort) {
    return `${proto}://${loc.hostname}:${cfg.wsPort}/ws/datasets/${datasetId}`;
  }
  return `${proto}://${loc.host}/ws/datasets/${datasetId}`;
}
