import type { ILngLat } from '@antv/l7';
import { Marker, PointLayer } from '@antv/larkmap';
import type { ImplementWidgetProps } from '@antv/li-sdk';
import { useLayerList, useScene, useStateManager } from '@antv/li-sdk';
import { Image, Switch } from 'antd';
import cls from 'classnames';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import useStyle from './ComponenStyle';
import type { Properties } from './registerForm';
import { setAgentEnabled } from './agentFlag';
import { CLS_PREFIX } from './constants';

export interface AgentBridgeProps extends ImplementWidgetProps, Properties {}

/** 后端下发的指令（见 doc/AGENT_MAP_CONTROL_SKILL.md §4 N4） */
type AgentCommand = {
  cmdId: string;
  seq: number;
  type: string;
  payload?: Record<string, any>;
};

/** 当前选中的目标（target.select） */
type TargetState = {
  key?: string | null;
  name?: string | null;
  lng: number;
  lat: number;
  payload: Record<string, any>;
};

type Phase = 'off' | 'connecting' | 'idle' | 'error';

const IMAGE_URL = /^https?:\/\/.*\.(jpeg|jpg|gif|png|webp|svg)(\?.*)?$/i;

/**
 * 瓦片图层：批量显隐默认保留它们（否则「隐藏全部图层」会把底图也关掉、只剩空白画布）。
 * 判定两头都认——图层资产类型，或它绑定的数据集类型（与 li-sdk LayerList 的瓦片口径一致，另补数据集侧）。
 */
const TILE_LAYER_TYPES = new Set(['TileLayer', 'RasterTileLayer', 'RasterLayer', 'MVTLayer']);
const TILE_DATASET_TYPES = new Set(['xyz-tile', 'raster-tile', 'mvt-tile', 'vector-tile']);

type LayerBrief = { layerId: string; layerName: string | null; type: string };

/** 当前运行时图层清单（只读；不写 store 里的图层集合，只改 visConfig.visible） */
const readLayers = (stateManager: any): LayerBrief[] => {
  const list: any[] = stateManager?.layersStore?.getLayerList?.() ?? [];
  return list.map((layer) => ({
    layerId: layer?.id,
    layerName: layer?.metadata?.name ?? null,
    type: layer?.type ?? '',
  }));
};

/** 瓦片判定：图层资产类型命中，或它绑定的数据集是瓦片类型 */
const isTileLayer = (layerId: string, type: string, stateManager: any): boolean => {
  if (TILE_LAYER_TYPES.has(type)) return true;
  const datasetId = stateManager?.layersStore?.getLayerById?.(layerId)?.sourceConfig?.datasetId;
  if (!datasetId) return false;
  const datasetType = stateManager?.datasetStore?.getDatasetById?.(datasetId)?.type;
  return TILE_DATASET_TYPES.has(datasetType);
};

/** 图层 zIndex（li 的渲染顺序字段）：没配过就是 0，与 L7 侧同口径（`config.zIndex || 0`） */
const readZIndex = (layerSchema: any): number => {
  const z = Number(layerSchema?.visConfig?.zIndex);
  return Number.isFinite(z) ? z : 0;
};

/**
 * 写图层 zIndex（v1.3 `layer.bringToFront` / `layer.sendToBack`）——**必须两条腿一起走**：
 *
 * 1. store 的官方路径 `updateLayer`：非复合图层（Point/Line/Polygon 等 CoreLayer）在 props 变化时
 *    会走 `CoreLayer.updateConfig` → `layer.setIndex()`，写 store 就能生效；
 * 2. 直接调 L7 实例的 `setIndex()`：**复合图层**（IconImageLayer / BubbleLayer / ClusterLayer 的两种
 *    模式）的 `CompositeLayer.update()` 完全不处理 zIndex，larkmap 也不会替它调 setIndex
 *    （`@antv/larkmap` 全文没有 setIndex 调用），所以只写 store 对图标图层是**没有反应**的——
 *    这正是「两个图标图层谁在上谁也调不了」的根因。
 *
 * 复合图层的 setIndex 会把它的所有子层设成同一个 zIndex，故层与层之间必须给互不相同的值
 * （调用方保证：front 取「最大值+1」，back 取「瓦片之上第一个空位」）。
 */
const applyLayerZIndex = (layerId: string, zIndex: number, stateManager: any, layerList: any[]) => {
  stateManager?.layersStore?.updateLayer?.(layerId, { visConfig: { zIndex } });
  const coreLayer = (layerList || []).find((item: any) => item?.id === layerId);
  const instance: any = coreLayer?.layer;
  if (instance && typeof instance.setIndex === 'function') {
    instance.setIndex(zIndex);
  }
};

const PHASE_TEXT: Record<Phase, string> = {
  off: '未接入',
  connecting: '连接中',
  idle: '已接入',
  error: '异常',
};

/** 项目 ID：Builder 会写入 window.__L7VP_PROJECT_ID__；其余页面从路由兜底解析 */
const resolveProjectId = (): string | undefined => {
  const fromWindow = (window as any).__L7VP_PROJECT_ID__;
  if (fromWindow) return String(fromWindow);
  const matched = window.location.hash.match(/\/(?:builder|app|template|share)\/([^/?#]+)/);
  return matched ? matched[1] : undefined;
};

const toFinite = (v: any): number | null => {
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
};

/** 聚焦到点；zoom 缺省 12（与技能约定一致） */
const focusPoint = (scene: any, lng: any, lat: any, zoom: any) => {
  if (!scene) throw new Error('地图尚未就绪');
  const x = toFinite(lng);
  const y = toFinite(lat);
  if (x === null || y === null) throw new Error('经纬度非法');
  const z = toFinite(zoom);
  scene.setZoomAndCenter(z === null ? 12 : z, [x, y]);
};

/** 聚焦到矩形：优先用底图的 fitBounds，底图没提供时退化为「中心 + 按跨度估算缩放级」 */
const focusBounds = (scene: any, bbox: any, padding: any) => {
  if (!scene) throw new Error('地图尚未就绪');
  const box = Array.isArray(bbox) ? bbox.map(toFinite) : [];
  if (box.length !== 4 || box.some((v) => v === null)) {
    throw new Error('bbox 必须是 [minLng, minLat, maxLng, maxLat]');
  }
  const [minLng, minLat, maxLng, maxLat] = box as number[];
  const pad = toFinite(padding);
  const map: any = (scene as any)?.getMap?.();
  if (typeof map?.fitBounds === 'function') {
    map.fitBounds(
      [
        [minLng, minLat],
        [maxLng, maxLat],
      ],
      { padding: pad === null ? 60 : pad, duration: 600 },
    );
    return;
  }
  // 回退：zoom z 的可见经度跨度约 360/2^z 度，留一点余量给 padding
  const span = Math.max(maxLng - minLng, (maxLat - minLat) * 1.6, 1e-6);
  const zoom = Math.max(0, Math.min(18, Math.log2(360 / span) - 0.3));
  scene.setZoomAndCenter(zoom, [(minLng + maxLng) / 2, (minLat + maxLat) / 2]);
};

/** 项目初始视野（map.reset 用）：不重新初始化整个 application，避免连带清掉用户的其它运行时状态 */
const readMapConfig = (stateManager: any): Record<string, any> => {
  try {
    const mapConfig: any = stateManager?.mapStore?.getMapConfig?.() ?? {};
    return mapConfig?.config ?? mapConfig ?? {};
  } catch (err) {
    return {};
  }
};

/** 一句人话描述刚执行的指令，显示在面板上 */
const describe = (cmd: AgentCommand, detail: any): string => {
  switch (cmd.type) {
    case 'layer.visibility':
      if (detail?.scope === 'all') {
        const kept = Array.isArray(detail?.skipped) ? detail.skipped.length : 0;
        return `全部${detail?.visible ? '显示' : '隐藏'}（保留瓦片 ${kept} 个）`;
      }
      return `${detail?.visible ? '显示' : '隐藏'}图层「${detail?.layerName || detail?.layerId}」`;
    case 'layer.isolate': {
      const shown = Array.isArray(detail?.changed)
        ? detail.changed.filter((item: any) => item?.visible).length
        : 0;
      const kept = Array.isArray(detail?.skipped) ? detail.skipped.length : 0;
      return `只显示 ${shown} 个图层${kept > 0 ? `（保留瓦片 ${kept} 个）` : ''}`;
    }
    case 'layer.bringToFront':
      return `图层「${detail?.layerName || detail?.layerId}」提到最上层（zIndex ${detail?.zIndex}）`;
    case 'layer.sendToBack':
      return `图层「${detail?.layerName || detail?.layerId}」压到最下层（zIndex ${detail?.zIndex}）`;
    case 'map.focus':
      return detail?.mode === 'point'
        ? `聚焦 ${detail?.lng}, ${detail?.lat}`
        : `聚焦区域 ${detail?.regionKey || detail?.bbox || ''}`;
    case 'map.reset':
      return '复位视野';
    case 'target.select':
      return `选中目标 ${detail?.name || detail?.key || ''}`;
    default:
      return cmd.type;
  }
};

/**
 * 智能体桥：取令 → 执行 → 回执。
 *
 * 后端 `/api/projects/{id}/agent/commands` 只入队，页面长轮询把指令拉下来在**本页**执行——
 * 地图场景对象只活在浏览器内存里，HTTP 请求天生由浏览器发起，方向反不过来。
 *
 * 三条硬约束（doc/AGENT_MAP_CONTROL_SKILL.md §6）：
 * 1. 不写入 li 的 dataset/layer store（官方运行时路径 `setLayerVisibility` 除外）：
 *    Builder 会自动保存，而 `stateManager.initState` 在 datasets/layers 数组引用变化时整体替换 store，
 *    塞进去的临时对象会被抹掉、且有被存进项目的风险。
 * 2. **必须有显式开关**：页面默认不接受智能体控制，用户手动开启后才开始轮询。
 * 3. 高亮/详情浮层一律自绘，卸载即消失。
 *
 * 图层显隐（v1.2）：批量形态（`layer.visibility` 带 `scope:'all'`、以及 `layer.isolate`）默认
 * **保留瓦片图层**（`keepTiles`，默认 true）——「隐藏全部图层」不该把底图也关掉、留下空白画布。
 * 单层形态与显式 `keepTiles:false` 不受此限，点谁改谁。
 *
 * 图层层级（v1.3）：`layer.bringToFront` / `layer.sendToBack` 只做「提到最上层 / 压到最下层」
 * 两个动作，zIndex 由本组件按当前图层栈算（页面才知道有哪些层、谁是瓦片）。
 * 复合图层（图标类）必须直接调实例 `setIndex()`，原因见 `applyLayerZIndex` 的注释。
 *
 * 另：指令只改运行时视图，刷新页面即恢复；要持久化改动得去编辑器里改。
 *
 * 能力分组（v1.4）：取令时带 `capabilities=runtime`，本组件只执行「改运行时视图」的指令。
 * 会写项目配置的 `dataset.create` / `layer.update` 属 `config` 分组，由 Builder 页的
 * 「智能体配置桥」（编辑器侧组件）执行——那一个跑在编辑器状态上，写进去才会被自动保存落库。
 * 本组件的开关同时通过 {@link setAgentEnabled} 广播成本页的授权旗标，配置桥据此决定是否取令。
 */
const AgentBridge: React.FC<AgentBridgeProps> = (props) => {
  const { enabled: enabledProp = false, pollTimeout = 25, highlightColor = '#F86624' } = props;

  const styles = useStyle();
  const [scene] = useScene();
  const layerList = useLayerList();
  const stateManager = useStateManager();

  const [enabled, setEnabled] = useState<boolean>(enabledProp);
  const [phase, setPhase] = useState<Phase>('off');
  const [message, setMessage] = useState<string>('');
  const [selected, setSelected] = useState<TargetState | null>(null);

  // 属性面板改了「默认开启」后同步过来（组件实例不会因 props 变化而重建）
  useEffect(() => {
    setEnabled(!!enabledProp);
  }, [enabledProp]);

  // 把开关状态广播成本页的「智能体已授权」旗标：Builder 页的「智能体配置桥」（编辑器侧，另一个包）
  // 据此决定要不要取 config 类指令。授权不跨页、不跨会话——组件卸载即收回。
  useEffect(() => {
    setAgentEnabled(enabled);
  }, [enabled]);
  useEffect(() => () => setAgentEnabled(false), []);

  const execute = useCallback(
    async (cmd: AgentCommand): Promise<Record<string, any>> => {
      const payload = cmd.payload ?? {};
      switch (cmd.type) {
        case 'layer.visibility': {
          const visible = payload.visible as boolean;
          if (typeof visible !== 'boolean') {
            throw new Error('layer.visibility 载荷不完整（需要 visible）');
          }
          // 批量形态（v1.2）：整片图层一起改，瓦片默认不动
          if (payload.scope === 'all') {
            const keepTiles = payload.keepTiles !== false;
            const changed: Array<Record<string, any>> = [];
            const skipped: Array<Record<string, any>> = [];
            for (const info of readLayers(stateManager)) {
              if (keepTiles && isTileLayer(info.layerId, info.type, stateManager)) {
                skipped.push({ layerId: info.layerId, layerName: info.layerName, reason: 'tile' });
                continue;
              }
              // 官方运行时路径：只改 store 里的 visConfig.visible，不落库、不进项目 JSON
              stateManager.layersStore.setLayerVisibility(info.layerId, visible);
              changed.push({ layerId: info.layerId, layerName: info.layerName, visible });
            }
            return { scope: 'all', visible, keepTiles, changed, skipped };
          }
          const layerId = payload.layerId as string;
          if (!layerId) {
            throw new Error('layer.visibility 载荷不完整（需要 layerId + visible）');
          }
          stateManager.layersStore.setLayerVisibility(layerId, visible);
          return { layerId, layerName: payload.layerName ?? null, visible };
        }
        case 'layer.isolate': {
          const keepTiles = payload.keepTiles !== false;
          const wanted = new Map<string, string | null>();
          for (const item of Array.isArray(payload.layers) ? payload.layers : []) {
            if (item?.layerId) wanted.set(String(item.layerId), item.layerName ?? null);
          }
          if (wanted.size === 0) {
            throw new Error('layer.isolate 载荷为空（需要 layers）');
          }
          const layers = readLayers(stateManager);
          const changed: Array<Record<string, any>> = [];
          const skipped: Array<Record<string, any>> = [];
          for (const info of layers) {
            const named = wanted.has(info.layerId);
            // 没点名的瓦片层：不动它（keepTiles=false 时才跟其它层一起被隐藏）
            if (keepTiles && !named && isTileLayer(info.layerId, info.type, stateManager)) {
              skipped.push({ layerId: info.layerId, layerName: info.layerName, reason: 'tile' });
              continue;
            }
            stateManager.layersStore.setLayerVisibility(info.layerId, named);
            changed.push({ layerId: info.layerId, layerName: info.layerName, visible: named });
          }
          // 点名了但运行时 store 里没有的图层：如实回报，不静默吞掉
          const present = new Set(layers.map((item) => item.layerId));
          const missing = Array.from(wanted.keys()).filter((id) => !present.has(id));
          return { keepTiles, changed, skipped, missing };
        }
        case 'layer.bringToFront':
        case 'layer.sendToBack': {
          const layerId = payload.layerId as string;
          if (!layerId) {
            // 归一化后一定带 layerId（后端把 layerName 解析掉了），走到这里说明指令是手写的
            throw new Error(`${cmd.type} 缺少 layerId`);
          }
          const layers: any[] = stateManager?.layersStore?.getLayerList?.() ?? [];
          const me = layers.find((item: any) => item?.id === layerId);
          if (!me) {
            throw new Error(`图层不在当前页面：${layerId}`);
          }

          // 瓦片层永远在业务层之下（li 的 LayerList 也把瓦片强制排最底），
          // 所以 back 只要压在所有瓦片之上即可，不能压到瓦片下面去（那会「消失」在底图里）
          const used = new Set<number>();
          let tileMax = 0;
          layers.forEach((item: any) => {
            const z = readZIndex(item);
            if (item?.id !== layerId) used.add(z);
            if (isTileLayer(item?.id, item?.type ?? '', stateManager)) tileMax = Math.max(tileMax, z);
          });

          const toFront = cmd.type === 'layer.bringToFront';
          let zIndex: number;
          if (toFront) {
            // 最大值 + 1，天然唯一
            zIndex = Math.max(tileMax, 0);
            used.forEach((z) => {
              zIndex = Math.max(zIndex, z);
            });
            zIndex += 1;
          } else {
            // 瓦片之上的第一个空位：保证唯一，避免与其它层撞成「平局看插入顺序」
            zIndex = tileMax + 1;
            while (used.has(zIndex)) zIndex += 1;
          }

          const before = readZIndex(me);
          applyLayerZIndex(layerId, zIndex, stateManager, layerList);
          return {
            layerId,
            layerName: payload.layerName ?? me?.metadata?.name ?? null,
            action: toFront ? 'front' : 'back',
            before,
            zIndex,
          };
        }
        case 'map.focus': {
          if (payload.mode === 'point') {
            focusPoint(scene, payload.lng, payload.lat, payload.zoom);
            return {
              mode: 'point',
              lng: toFinite(payload.lng),
              lat: toFinite(payload.lat),
              zoom: toFinite(payload.zoom) ?? 12,
            };
          }
          if (payload.mode === 'bounds') {
            // region 模式已由后端解析成 bounds，页面只认 point/bounds
            focusBounds(scene, payload.bbox, payload.padding);
            return { mode: 'bounds', bbox: payload.bbox, regionKey: payload.regionKey ?? null };
          }
          throw new Error(`未知的 map.focus mode：${payload.mode}`);
        }
        case 'map.reset': {
          const config = readMapConfig(stateManager);
          focusPoint(scene, (config.center as any)?.[0], (config.center as any)?.[1], config.zoom ?? 4);
          return { zoom: config.zoom ?? null, center: config.center ?? null };
        }
        case 'target.select': {
          const target = payload.target ?? {};
          const lng = toFinite(target.lng);
          const lat = toFinite(target.lat);
          if (lng === null || lat === null) {
            throw new Error('target.select 的 target 缺少有效经纬度');
          }
          setSelected({
            key: target.key ?? null,
            name: target.name ?? null,
            lng,
            lat,
            payload: target.payload && typeof target.payload === 'object' ? target.payload : {},
          });
          if (payload.focus !== false) {
            focusPoint(scene, lng, lat, payload.zoom ?? 14);
          }
          return { key: target.key ?? null, name: target.name ?? null, lng, lat };
        }
        default:
          throw new Error(`未知指令类型：${cmd.type}`);
      }
    },
    [scene, stateManager, layerList],
  );

  // 轮询循环长期存活，execute 经 ref 取最新版本——否则 scene 一就绪就会重启循环
  const executeRef = useRef(execute);
  executeRef.current = execute;

  useEffect(() => {
    if (!enabled) {
      setPhase('off');
      setMessage('');
      return undefined;
    }
    const projectId = resolveProjectId();
    if (!projectId) {
      setPhase('error');
      setMessage('未获取到项目 ID，无法接入智能体');
      return undefined;
    }

    // 关闭开关或组件卸载时靠 stopped + abort 收口，避免请求悬挂 25 秒
    let stopped = false;
    let controller: AbortController | null = null;
    let cursor: number | null = null;
    let count = 0;

    setPhase('connecting');
    setMessage('等待指令…');

    const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

    const run = async () => {
      while (!stopped) {
        controller = new AbortController();
        try {
          const params = new URLSearchParams();
          // 首次不带 since：服务端只回游标不回放，避免执行页面打开前积压的陈旧指令
          if (cursor !== null) params.set('since', String(cursor));
          params.set('timeout', String(pollTimeout));
          // 只取「运行时视图」类指令：会写入项目配置的指令（dataset.create / layer.update）
          // 由 Builder 页的「智能体配置桥」执行——本组件跑在运行时 store 上，写不了项目配置。
          // 不属于本能力的指令留在队列里等那一侧来取，不会被取走却执行不了。
          params.set('capabilities', 'runtime');

          const res = await fetch(`/api/projects/${projectId}/agent/commands?${params.toString()}`, {
            signal: controller.signal,
          });
          if (stopped) return;
          const data = await res.json().catch(() => ({}));
          if (!res.ok) throw new Error(data?.error || `HTTP ${res.status}`);

          if (typeof data?.seq === 'number') cursor = data.seq;
          setPhase('idle');
          setMessage(count > 0 ? `已执行 ${count} 条` : '等待指令…');

          const commands: AgentCommand[] = Array.isArray(data?.commands) ? data.commands : [];
          for (const cmd of commands) {
            if (stopped) return;
            let ok = true;
            let error: string | null = null;
            let detail: Record<string, any> | null = null;
            try {
              detail = await executeRef.current(cmd);
            } catch (err: any) {
              ok = false;
              error = err?.message || String(err);
            }
            count += 1;
            setMessage(ok ? `已执行 ${count} 条：${describe(cmd, detail)}` : `指令失败：${error}`);

            // 回执（N5）：让智能体能确认「页面真的执行了」，而不是「页面根本没开」
            try {
              await fetch(`/api/projects/${projectId}/agent/commands/${cmd.cmdId}/result`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ ok, error, detail }),
                signal: controller.signal,
              });
            } catch (err) {
              // 回执失败不影响本页执行，下一条指令继续
            }
          }
        } catch (err: any) {
          if (stopped) return;
          setPhase('error');
          setMessage(`取令失败：${err?.message || err}（2 秒后重试）`);
          await sleep(2000);
        }
      }
    };

    run();

    return () => {
      stopped = true;
      controller?.abort();
    };
  }, [enabled, pollTimeout]);

  const payloadEntries = useMemo(() => {
    if (!selected) return [] as Array<[string, any]>;
    return Object.keys(selected.payload ?? {})
      .filter((key) => selected.payload[key] !== null && selected.payload[key] !== undefined)
      .slice(0, 20)
      .map((key) => [key, selected.payload[key]] as [string, any]);
  }, [selected]);

  const enabledOn = enabled && phase !== 'error';

  return (
    <>
      {/* 选中目标的高亮 + 详情浮层：自绘，卸载即消失，不写入 li 的 store */}
      {selected && (
        <PointLayer
          source={{ data: [selected], parser: { type: 'json', x: 'lng', y: 'lat' } }}
          shape="circle"
          size={28}
          color={highlightColor}
          style={{ opacity: 0.25, stroke: highlightColor, strokeWidth: 2 }}
        />
      )}
      {selected && (
        <Marker lngLat={{ lng: selected.lng, lat: selected.lat } as ILngLat} anchor="top-left">
          <div
            className={cls(`${CLS_PREFIX}__target`, styles.target)}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <div className={styles.row}>
              <span className={styles.title}>{selected.name || selected.key || '目标详情'}</span>
              <span className={styles.close} onClick={() => setSelected(null)}>
                关闭
              </span>
            </div>
            {selected.key != null && (
              <div className={styles.kv}>
                <span className={styles.k}>标识</span>
                <span className={styles.v}>{String(selected.key)}</span>
              </div>
            )}
            <div className={styles.kv}>
              <span className={styles.k}>坐标</span>
              <span className={styles.v}>
                {selected.lng.toFixed(5)}, {selected.lat.toFixed(5)}
              </span>
            </div>
            {payloadEntries.map(([key, value]) => (
              <div className={styles.kv} key={key}>
                <span className={styles.k}>{key}</span>
                <span className={styles.v}>
                  {typeof value === 'string' && IMAGE_URL.test(value) ? (
                    <Image src={value} height={40} referrerPolicy="no-referrer" />
                  ) : typeof value === 'object' ? (
                    JSON.stringify(value)
                  ) : (
                    String(value)
                  )}
                </span>
              </div>
            ))}
          </div>
        </Marker>
      )}

      {/* 显式开关：页面默认不接受智能体控制，必须在这里手动开启 */}
      <div className={cls(`${CLS_PREFIX}__panel`, styles.panel)} onMouseDown={(e) => e.stopPropagation()}>
        <div className={styles.row}>
          <Switch size="small" checked={enabled} onChange={setEnabled} />
          <span className={styles.title}>接入智能体</span>
          <span className={cls(styles.status, enabledOn ? styles.statusOn : '')}>{PHASE_TEXT[phase]}</span>
        </div>
        <div className={styles.hint}>
          {enabled
            ? message || '等待指令…'
            : '开启后本页会执行外部智能体下发的地图指令。在 Builder 页还会同时允许智能体新建/修改图层并写入项目配置（保存后生效）；仅改运行时视图的指令刷新即恢复'}
        </div>
      </div>
    </>
  );
};

export default AgentBridge;
