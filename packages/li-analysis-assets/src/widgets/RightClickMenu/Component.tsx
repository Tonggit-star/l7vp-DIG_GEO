import type { ILngLat } from '@antv/l7';
import { LineLayer, Marker, PointLayer } from '@antv/larkmap';
import type { ImplementWidgetProps } from '@antv/li-sdk';
import { useDatasetList, useLayerList, useScene, useStateManager } from '@antv/li-sdk';
import { Spin, message } from 'antd';
import cls from 'classnames';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import HistoryTrackModal from './HistoryTrackModal';
import type { TimeRange } from './HistoryTrackModal';
import useStyle from './ComponenStyle';
import type { Properties } from './registerForm';
import { CLS_PREFIX } from './constants';

export interface RightClickMenuProps extends ImplementWidgetProps, Properties {}

/** 被右键的图层所属的流式数据集信息 */
type StreamInfo = { datasetId: string; streamKey: string; name?: string };

/** 打开右键菜单时的上下文 */
type MenuState = {
  position: { lng: number; lat: number };
  /** 命中的要素（原始行）；未命中要素时为 undefined */
  feature?: Record<string, any>;
  /** 命中的 li 图层 id */
  layerId?: string;
};

/** 当前绘制的轨迹 */
type TrackState = {
  rows: Array<{ lng: number; lat: number }>;
  lineData: Array<{ track: number[][] }>;
  start: Array<{ lng: number; lat: number }>;
  end: Array<{ lng: number; lat: number }>;
  label: string;
};

const START_COLOR = '#22c55e';
const END_COLOR = '#ef4444';

/**
 * 项目 ID：Builder 会写入 window.__L7VP_PROJECT_ID__；其余页面（app/template/share）从路由里兜底解析。
 */
const resolveProjectId = (): string | undefined => {
  const fromWindow = (window as any).__L7VP_PROJECT_ID__;
  if (fromWindow) return String(fromWindow);
  const matched = window.location.hash.match(/\/(?:builder|app|template|share)\/([^/?#]+)/);
  return matched ? matched[1] : undefined;
};

/**
 * 地图右键菜单。
 *
 * 原实现直接用了 larkmap 的 `<ContextMenu>`，但该组件读的是 `e.lnglat`，而 L7 的 MapMouseEvent
 * 上挂的字段是 `lngLat`（见 @antv/l7-map 的 MapMouseEvent），取值为 undefined 并在 BaseMapService
 * 的事件代理里被 try/catch 吞掉，导致菜单永远不渲染；同时 `preventDefault` 又屏蔽了浏览器原生菜单，
 * 表现为「右键毫无反应」。这里改为自绘菜单 + larkmap 的 `<Marker>` 定位。
 *
 * 另新增「历史轨迹」：在绑定流式数据集（且配置了目标标识字段 streamKey）的图层上右键某个目标，
 * 可查询该目标在时序库里的历史点并在地图上画出轨迹线与轨迹点。
 *
 * 轨迹图层由本组件自己渲染，**不写入 li 的 datasetStore / layersStore** ——
 * Builder 的自动保存是从 editor state 重建 application 的，而 stateManager.initState 又会在
 * datasets/layers 数组引用变化时整体替换 store，把临时轨迹塞进 store 既会被抹掉、也有被存进项目的风险。
 */
const RightClickMenu: React.FC<RightClickMenuProps> = (props) => {
  const {
    showRightMenu,
    showHistoryTrack = true,
    historyMaxRows = 20000,
    trackColor = '#F86624',
  } = props;

  const styles = useStyle();
  const [scene] = useScene();
  const layerList = useLayerList();
  const [datasets] = useDatasetList();
  const stateManager = useStateManager();
  const [messageApi, messageContextHolder] = message.useMessage();

  const [menu, setMenu] = useState<MenuState | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [dataRange, setDataRange] = useState<{ minTime?: string | null; maxTime?: string | null } | null>(null);
  const [track, setTrack] = useState<TrackState | null>(null);

  // 事件处理器在绑定后长期存活，datasets 需经 ref 取最新值
  const datasetsRef = useRef(datasets);
  datasetsRef.current = datasets;

  // 选中的目标（点「历史轨迹」后仍要跨菜单关闭使用）
  const targetRef = useRef<{ datasetId: string; streamKey: string; keyValue: string; label: string } | null>(null);

  // 同一拍内 scene 与各图层都会收到 contextmenu，用一次性定时器合并成一次开菜单
  const scheduleRef = useRef<{
    timer: any;
    position?: { lng: number; lat: number };
    feature?: Record<string, any>;
    layerId?: string;
  }>({ timer: null });
  const scheduleOpen = useCallback((position: { lng: number; lat: number }, feature?: Record<string, any>, layerId?: string) => {
    const slot = scheduleRef.current;
    slot.position = position;
    if (feature) {
      slot.feature = feature;
      slot.layerId = layerId;
    }
    if (slot.timer) return;
    slot.timer = setTimeout(() => {
      slot.timer = null;
      const { position: pos, feature: f, layerId: lid } = slot;
      slot.position = undefined;
      slot.feature = undefined;
      slot.layerId = undefined;
      if (pos) setMenu({ position: pos, feature: f, layerId: lid });
    }, 0);
  }, []);

  /** 由 li 图层 schema → 绑定数据集 → 校验是否「流式 + 配了 streamKey」 */
  const resolveStreamInfo = useCallback(
    (layerId?: string): StreamInfo | null => {
      if (!layerId) return null;
      const schema = stateManager?.layersStore?.getLayerById?.(layerId) as any;
      const datasetId = schema?.sourceConfig?.datasetId ?? schema?.dataset;
      if (!datasetId) return null;
      const dataset = datasetsRef.current.find((item: any) => item.id === datasetId) as any;
      const meta = (dataset?.metadata || {}) as Record<string, any>;
      const isStream = meta.stream === true || dataset?._stream === true;
      const streamKey = typeof meta.streamKey === 'string' ? meta.streamKey.trim() : '';
      if (!isStream || !streamKey) return null;
      return { datasetId, streamKey, name: meta.name };
    },
    [stateManager],
  );

  // ==================== 图层级右键：能拿到 feature（原始数据行） ====================
  // 记录已绑定的 L7 图层实例 → 处理器，避免重复绑定（L7 的事件是累加的，重复绑会多触发）
  const boundLayersRef = useRef(new Map<any, (e: any) => void>());

  useEffect(() => {
    if (!showRightMenu) return;
    const bound = boundLayersRef.current;

    const bind = () => {
      (layerList || []).forEach((coreLayer: any) => {
        const l7Layers: any[] = [];
        try {
          if (coreLayer?.isComposite) {
            // 复合图层（IconImageLayer / BubbleLayer 等）真正的可拾取图层在 subLayers 里
            (coreLayer.subLayers?.getLayers?.() || []).forEach((sub: any) => {
              if (sub?.layer) l7Layers.push(sub.layer);
            });
          } else if (coreLayer?.layer) {
            l7Layers.push(coreLayer.layer);
          }
        } catch (err) {
          // 图层尚未就绪时忽略，稍后重试
        }
        l7Layers.forEach((l7Layer) => {
          if (bound.has(l7Layer)) return;
          const handler = (e: any) => {
            const lngLat = e?.lngLat;
            if (!lngLat) return;
            scheduleOpen({ lng: lngLat.lng, lat: lngLat.lat }, e?.feature, coreLayer?.id);
          };
          try {
            l7Layer.on('contextmenu', handler);
            bound.set(l7Layer, handler);
          } catch (err) {
            // 个别图层不支持事件代理，跳过
          }
        });
      });
    };

    bind();
    // 图层可能在本次 effect 之后才完成注册/初始化，补绑一次
    const retry = setTimeout(bind, 800);
    return () => clearTimeout(retry);
  }, [showRightMenu, layerList, scheduleOpen]);

  // 组件卸载时统一解绑
  useEffect(
    () => () => {
      boundLayersRef.current.forEach((handler, l7Layer) => {
        try {
          l7Layer.off('contextmenu', handler);
        } catch (err) {
          /* noop */
        }
      });
      boundLayersRef.current.clear();
      if (scheduleRef.current.timer) clearTimeout(scheduleRef.current.timer);
    },
    [],
  );

  // ==================== 场景级右键 / 关闭 ====================
  useEffect(() => {
    if (!scene || !showRightMenu) return;
    const onContextMenu = (e: any) => {
      const lngLat = e?.lngLat;
      if (!lngLat) return;
      // 命中要素时图层级回调会写入 feature，同一拍内合并
      scheduleOpen({ lng: lngLat.lng, lat: lngLat.lat });
    };
    const close = () => setMenu(null);
    scene.on('contextmenu', onContextMenu);
    scene.on('click', close);
    scene.on('dragstart', close);
    return () => {
      scene.off('contextmenu', onContextMenu);
      scene.off('click', close);
      scene.off('dragstart', close);
    };
  }, [scene, showRightMenu, scheduleOpen]);

  // 点击菜单之外的地方关闭
  useEffect(() => {
    if (!menu) return;
    const onDocMouseDown = () => setMenu(null);
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setMenu(null);
    };
    // 延后一拍注册，避免触发本次打开的右键/点击事件立刻把它关掉
    const timer = setTimeout(() => document.addEventListener('mousedown', onDocMouseDown), 0);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      clearTimeout(timer);
      document.removeEventListener('mousedown', onDocMouseDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [menu]);

  // ==================== 菜单项动作 ====================
  const zoom = (type: 'zoomIn' | 'zoomOut') => {
    if (!scene) return;
    if (type === 'zoomIn') scene.zoomIn();
    else scene.zoomOut();
  };

  const fetchDataRange = async (datasetId: string) => {
    const projectId = resolveProjectId();
    if (!projectId) return;
    try {
      const res = await fetch(`/api/projects/${projectId}/datasets/${datasetId}/stream/history/range`);
      if (!res.ok) return;
      setDataRange(await res.json());
    } catch (err) {
      // 时间窗只是提示信息，拿不到不影响查询
    }
  };

  const openHistory = () => {
    if (!target) return;
    targetRef.current = target;
    setMenu(null);
    setDataRange(null);
    setModalOpen(true);
    fetchDataRange(target.datasetId);
  };

  const fitToTrack = (rows: Array<{ lng: number; lat: number }>) => {
    try {
      const map = (scene as any)?.getMap?.();
      if (!map || typeof map.fitBounds !== 'function') return;
      let minLng = Infinity;
      let minLat = Infinity;
      let maxLng = -Infinity;
      let maxLat = -Infinity;
      rows.forEach((p) => {
        minLng = Math.min(minLng, p.lng);
        maxLng = Math.max(maxLng, p.lng);
        minLat = Math.min(minLat, p.lat);
        maxLat = Math.max(maxLat, p.lat);
      });
      if (!isFinite(minLng) || (minLng === maxLng && minLat === maxLat)) return;
      map.fitBounds(
        [
          [minLng, minLat],
          [maxLng, maxLat],
        ],
        { padding: 60, duration: 600 },
      );
    } catch (err) {
      // 不同底图能力不一致，定位失败不影响轨迹绘制
    }
  };

  const requestHistory = async (range: TimeRange) => {
    const target0 = targetRef.current;
    if (!target0) return;
    const projectId = resolveProjectId();
    if (!projectId) {
      messageApi.error('未获取到项目 ID，无法查询历史轨迹');
      return;
    }

    const params = new URLSearchParams();
    params.set('key', target0.keyValue);
    if (historyMaxRows) params.set('limit', String(historyMaxRows));
    if (range.start != null) params.set('start', String(range.start));
    if (range.end != null) params.set('end', String(range.end));

    setLoading(true);
    try {
      const res = await fetch(
        `/api/projects/${projectId}/datasets/${target0.datasetId}/stream/history?${params.toString()}`,
      );
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data?.error || `HTTP ${res.status}`);

      const rawRows: any[] = Array.isArray(data?.rows) ? data.rows : [];
      const points = rawRows
        .map((row) => ({ lng: Number(row?.lng), lat: Number(row?.lat) }))
        .filter((p) => Number.isFinite(p.lng) && Number.isFinite(p.lat));

      if (!points.length) {
        setTrack(null);
        messageApi.warning('该时间范围内没有查询到轨迹数据');
        return;
      }

      setTrack({
        rows: points,
        lineData: points.length >= 2 ? [{ track: points.map((p) => [p.lng, p.lat]) }] : [],
        start: [points[0]],
        end: [points[points.length - 1]],
        label: target0.label,
      });
      setModalOpen(false);
      messageApi.success(
        `已加载 ${points.length} 个轨迹点${data?.truncated ? '（已达上限，仅显示最早部分）' : ''}`,
      );
      fitToTrack(points);
    } catch (err: any) {
      messageApi.error(`查询历史轨迹失败：${err?.message || err}`);
    } finally {
      setLoading(false);
    }
  };

  // ==================== 派生状态 ====================
  const streamInfo = useMemo(
    () => (menu && showHistoryTrack ? resolveStreamInfo(menu.layerId) : null),
    [menu, showHistoryTrack, resolveStreamInfo, datasets],
  );

  const target = useMemo(() => {
    if (!menu || !streamInfo) return null;
    const raw = menu.feature ? menu.feature[streamInfo.streamKey] : undefined;
    if (raw === undefined || raw === null || raw === '') {
      if (menu.feature) {
        // 命中了要素但取不到目标标识：多半是数据列名与 streamKey 不一致
        // eslint-disable-next-line no-console
        console.warn(
          `[RightClickMenu] 要素中未找到目标标识字段 "${streamInfo.streamKey}"，无法查询历史轨迹`,
        );
      }
      return null;
    }
    return {
      datasetId: streamInfo.datasetId,
      streamKey: streamInfo.streamKey,
      keyValue: String(raw),
      label: streamInfo.name ? `${streamInfo.name}` : String(raw),
    };
  }, [menu, streamInfo]);

  if (!showRightMenu) return null;

  return (
    <>
      {messageContextHolder}

      {/* 轨迹图层：只在本组件内渲染，不写入 li 的 store（见组件头注释） */}
      {track && (
        <>
          {track.lineData.length > 0 && (
            <LineLayer
              source={{ data: track.lineData, parser: { type: 'json', coordinates: 'track' } }}
              shape="line"
              size={2}
              color={trackColor}
              style={{ opacity: 0.9 }}
            />
          )}
          <PointLayer
            source={{ data: track.rows, parser: { type: 'json', x: 'lng', y: 'lat' } }}
            shape="circle"
            size={4}
            color={trackColor}
            style={{ opacity: 0.55 }}
          />
          {/* 起点 / 终点单独一层，便于区分 */}
          <PointLayer
            source={{ data: track.start, parser: { type: 'json', x: 'lng', y: 'lat' } }}
            shape="circle"
            size={12}
            color={START_COLOR}
          />
          <PointLayer
            source={{ data: track.end, parser: { type: 'json', x: 'lng', y: 'lat' } }}
            shape="circle"
            size={12}
            color={END_COLOR}
          />
        </>
      )}

      {menu && (
        <Marker lngLat={menu.position as ILngLat} anchor="top-left">
          <div
            className={cls(`${CLS_PREFIX}__menu`, styles.menu)}
            onMouseDown={(e) => e.stopPropagation()}
            onContextMenu={(e) => e.preventDefault()}
          >
            <div
              className={styles.menuItem}
              onClick={() => {
                zoom('zoomIn');
                setMenu(null);
              }}
            >
              放大一级
            </div>
            <div
              className={styles.menuItem}
              onClick={() => {
                zoom('zoomOut');
                setMenu(null);
              }}
            >
              缩小一级
            </div>
            {target && (
              <div className={styles.menuItem} onClick={openHistory}>
                历史轨迹
              </div>
            )}
            {track && (
              <div
                className={styles.menuItem}
                onClick={() => {
                  setTrack(null);
                  setMenu(null);
                }}
              >
                清除轨迹
              </div>
            )}
          </div>
        </Marker>
      )}

      {loading && (
        <div className={styles.loading} data-comp="right-click-menu-loading">
          <Spin tip="正在查询历史轨迹…" size="large">
            <div style={{ width: 160, height: 60 }} />
          </Spin>
        </div>
      )}

      <HistoryTrackModal
        open={modalOpen}
        loading={loading}
        targetKey={targetRef.current?.keyValue}
        targetName={targetRef.current?.label}
        dataRange={dataRange}
        onCancel={() => setModalOpen(false)}
        onConfirm={requestHistory}
      />

      {/* 轨迹图例（有轨迹时显示，便于区分起点/终点） */}
      {track && (
        <div className={styles.legend}>
          <span className={styles.legendTitle}>历史轨迹</span>
          <span className={styles.legendItem}>
            <i className={styles.legendDot} style={{ background: START_COLOR }} />
            起点
          </span>
          <span className={styles.legendItem}>
            <i className={styles.legendDot} style={{ background: END_COLOR }} />
            终点
          </span>
        </div>
      )}
    </>
  );
};

export default RightClickMenu;
