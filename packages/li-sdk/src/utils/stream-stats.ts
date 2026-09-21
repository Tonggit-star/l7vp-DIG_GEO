/**
 * L7_INTEGRATION: 流式数据集「实时行数」跨模块事件总线。
 *
 * 背景：流式数据的行只存在于运行时 datasetStore（useStreamDatasets 节流写入），从不进入
 * li-editor 编辑器侧的 EditorDataset.data —— 所以数据集面板「共 N 行」恒为 0。这里把
 * 「实时行数」（而非整批数据）广播出去，供面板等只读展示同步；不触碰编辑器数据、不落库。
 *
 * 行数取 useStreamDatasets 维护的前端滑动窗口长度（与图层所见同源，一般约等于后端环形缓冲）。
 */
export type StreamStatsListener = (counts: Readonly<Record<string, number>>) => void;

class StreamDatasetStats {
  private counts: Record<string, number> = {};
  private listeners: Set<StreamStatsListener> = new Set();

  /** 设置某数据集的实时行数；无变化时跳过通知 */
  setCount(datasetId: string, count: number): void {
    const next = count < 0 ? 0 : Math.floor(count);
    if (this.counts[datasetId] === next) {
      return;
    }
    this.counts[datasetId] = next;
    this.notify();
  }

  /** 数据集从订阅列表移除/卸载时清除计数 */
  removeDataset(datasetId: string): void {
    if (!(datasetId in this.counts)) {
      return;
    }
    delete this.counts[datasetId];
    this.notify();
  }

  getCount(datasetId: string): number {
    return this.counts[datasetId] ?? 0;
  }

  subscribe(listener: StreamStatsListener): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private notify(): void {
    this.listeners.forEach((listener) => {
      try {
        listener(this.counts);
      } catch (e) {
        console.error('[stream-stats] listener error', e);
      }
    });
  }
}

/** 单例：同页共享（Builder 与 Share 都经 useStreamDatasets 写入） */
export const streamDatasetStats = new StreamDatasetStats();
