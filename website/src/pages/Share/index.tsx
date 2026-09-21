import type { Application, LocalDatasetSchema } from '@antv/li-sdk';
import { LIRuntimeApp } from '@antv/li-sdk';
import { Spin } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useParams } from 'umi';
import { getProject } from '@/services';
import type { Project } from '@/services';
import { useMarketAssets, useStreamDatasets } from '@/hooks';

const API_BASE_URL = '/api';

const loadLazyRows = async (projectId: string, datasetId: string) => {
  const allRows: any[] = [];
  const size = 500;
  let page = 0;
  let total = 0;
  do {
    const res = await fetch(
      `${API_BASE_URL}/projects/${projectId}/datasets/${datasetId}/rows?page=${page}&size=${size}`,
    );
    if (!res.ok) break;
    const data = await res.json();
    allRows.push(...data.rows);
    total = data.total;
    page++;
  } while (allRows.length < total);
  return allRows;
};

interface CrossMessage {
  action: 'highlight' | 'highlightLines' | 'filter' | 'clearHighlight' | 'reset';
  datasetId?: string;
  filters?: { fieldId?: string; value: any[]; operator?: string }[];
  highlightPoints?: { lng: number; lat: number; [key: string]: any }[];
  lines?: { fromLng: number; fromLat: number; toLng: number; toLat: number; [key: string]: any }[];
  highlightLayerType?: string;
  style?: any;
}

const HL_DS_PREFIX = 'de_highlight_';
const HL_LAYER_PREFIX = 'de_highlight_layer_';

let _highlightTs = 0;

// ================================================================
// L7_INTEGRATION: 持有 React state 引用，使 addHighlight 能通过
// setAppConfig 更新 config 触发 LIAPP 重渲染。
// layers 必须写入 spec.layers（非顶层 layers）。
// ================================================================
let _appConfig: Application | null = null;
let _setAppConfig: ((c: Application) => void) | null = null;

const clearAllHighlights = (stateManager: any) => {
  const dsStore = stateManager.datasetStore;
  const layerStore = stateManager.layersStore;
  const dsList = dsStore.getDatasetList();
  dsList.filter((ds: any) => ds.id.startsWith(HL_DS_PREFIX)).forEach((ds: any) => dsStore.removeDataset(ds.id));
  const layerList = layerStore.getLayerList();
  layerList.filter((l: any) => l.id.startsWith(HL_LAYER_PREFIX)).forEach((l: any) => layerStore.removeLayer(l.id));
};

const addHighlight = (stateManager: any, points: any[], layerType: string, style: any) => {
  const ts = ++_highlightTs;
  const dsId = `${HL_DS_PREFIX}${ts}`;
  const layerId = `${HL_LAYER_PREFIX}${ts}`;

  const newDataset = {
    id: dsId,
    type: 'local',
    metadata: { name: '联动高亮' },
    data: points.map((p, i) => ({ lng: p.lng, lat: p.lat, index: i, ...p })),
    columns: [
      { name: 'lng', type: 'number' },
      { name: 'lat', type: 'number' },
      { name: 'index', type: 'number' },
      { name: 'name', type: 'string' },
    ],
  };
  const newLayer = {
    id: layerId,
    type: layerType,
    name: '联动标注',
    dataset: dsId,
    metadata: { name: '联动标注' },
    sourceConfig: { datasetId: dsId, parser: { x: 'lng', y: 'lat' } },
    visConfig: {
      visible: true,
      radius: style?.pointRadius || 10,
      fillColor: style?.pointColor || '#F86624',
      opacity: style?.pointOpacity ?? 0.85,
      strokeColor: '#fff',
      strokeWidth: 2,
      lineOpacity: 1,
      blend: 'normal',
      coordinateType: 'table',
      minZoom: 0,
      maxZoom: 24,
      label: {
        visible: style?.showLabel !== false,
        field: 'name',
        enable: true,
        style: {
          fill: style?.textColor || '#ffffff',
          fontSize: style?.textSize || 12,
          textAnchor: 'center',
          textOffset: [0, 0],
          stroke: '#606060',
          strokeWidth: 0.5,
        },
      },
    },
  };

  // 1) 清除旧高亮（stores）
  clearAllHighlights(stateManager);

  // 2) 写入新数据到 MobX stores
  stateManager.datasetStore.addDataset(newDataset);
  stateManager.layersStore.addLayer(newLayer);

  // 3) 同步 config 到 React state
  if (_appConfig && _setAppConfig) {
    const cleanDatasets = (_appConfig.datasets || []).filter((ds: any) => !(ds.id || '').startsWith(HL_DS_PREFIX));
    const cleanLayers = (_appConfig.spec?.layers || []).filter((l: any) => !(l.id || '').startsWith(HL_LAYER_PREFIX));

    _appConfig = {
      ..._appConfig,
      datasets: [...cleanDatasets, newDataset as any],
      spec: {
        ..._appConfig.spec,
        layers: [...cleanLayers, newLayer as any],
      },
    };
    (window as any).__hlConfig = _appConfig;
    _setAppConfig(_appConfig);
  }

  console.log('[L7 Share] highlight added:', points.length, 'points');
};

const addHighlightLines = (stateManager: any, lines: any[], style: any) => {
  const ts = ++_highlightTs;
  const dsFromId = `${HL_DS_PREFIX}from_${ts}`;
  const dsToId = `${HL_DS_PREFIX}to_${ts}`;
  const layerFromId = `${HL_LAYER_PREFIX}from_${ts}`;
  const layerToId = `${HL_LAYER_PREFIX}to_${ts}`;

  const ptRadius = style?.pointRadius || 10;
  const ptColor = style?.pointColor || '#F86624';
  const ptOpacity = style?.pointOpacity ?? 0.85;
  const lnColor = style?.lineColor || '#F86624';
  const lnWidth = style?.lineWidth || 4;
  const lnOpacity = style?.lineOpacity ?? 0.85;

  const makePtDs = (key: 'from' | 'to') => ({
    id: key === 'from' ? dsFromId : dsToId,
    type: 'local',
    metadata: { name: `联动路段-${key === 'from' ? '起点' : '终点'}` },
    data: lines.map((l, i) => ({
      lng: key === 'from' ? l.fromLng : l.toLng,
      lat: key === 'from' ? l.fromLat : l.toLat,
      index: i,
      ...l,
    })),
    columns: [
      { name: 'lng', type: 'number' },
      { name: 'lat', type: 'number' },
      { name: 'index', type: 'number' },
      { name: 'name', type: 'string' },
    ],
  });
  const fromDataset = makePtDs('from');
  const toDataset = makePtDs('to');

  const makePtLayer = (layerId: string, dsId: string, name: string) => ({
    id: layerId,
    type: 'BubbleLayer',
    name,
    dataset: dsId,
    metadata: { name },
    sourceConfig: { datasetId: dsId, parser: { x: 'lng', y: 'lat' } },
    visConfig: {
      visible: true,
      radius: ptRadius,
      fillColor: ptColor,
      opacity: ptOpacity,
      strokeColor: '#fff',
      strokeWidth: 2,
      lineOpacity: 1,
      blend: 'normal',
      coordinateType: 'table',
      minZoom: 0,
      maxZoom: 24,
      label: {
        visible: style?.showLabel !== false,
        field: 'name',
        enable: true,
        style: {
          fill: style?.textColor || '#ffffff',
          fontSize: style?.textSize || 12,
          textAnchor: 'center',
          textOffset: [0, 0],
          stroke: '#606060',
          strokeWidth: 0.5,
        },
      },
    },
  });
  const fromLayer = makePtLayer(layerFromId, dsFromId, '联动路段-起点');
  const toLayer = makePtLayer(layerToId, dsToId, '联动路段-终点');

  // 线数据 set + LineLayer（对齐原始 LineLayer config 格式）
  const dsLineId = `${HL_DS_PREFIX}line_${ts}`;
  const layerLineId = `${HL_LAYER_PREFIX}line_${ts}`;
  const lineDataset = {
    id: dsLineId,
    type: 'local',
    metadata: { name: '联动路段-线' },
    data: lines.map((l, i) => ({
      fromLng: l.fromLng,
      fromLat: l.fromLat,
      toLng: l.toLng,
      toLat: l.toLat,
      index: i,
      ...l,
    })),
    columns: [
      { name: 'fromLng', type: 'number' },
      { name: 'fromLat', type: 'number' },
      { name: 'toLng', type: 'number' },
      { name: 'toLat', type: 'number' },
    ],
  };
  const lineLayer = {
    id: layerLineId,
    type: 'LineLayer',
    name: '联动路段-线',
    dataset: dsLineId,
    metadata: { name: '联动路段-线' },
    sourceConfig: {
      datasetId: dsLineId,
      parser: { x: 'fromLng', y: 'fromLat', x1: 'toLng', y1: 'toLat' },
    },
    visConfig: {
      visible: true,
      size: lnWidth,
      style: {
        opacity: lnOpacity,
        lineType: 'solid',
        sourceColor: lnColor,
        targetColor: lnColor,
      },
      minZoom: 0,
      maxZoom: 24,
      blend: 'normal',
      coordinateType: 'table',
      animate: { enable: false },
    },
  };

  // 清除旧高亮
  clearAllHighlights(stateManager);

  // 写入 stores
  const dsStore = stateManager.datasetStore;
  const lyrStore = stateManager.layersStore;
  dsStore.addDataset(lineDataset);
  dsStore.addDataset(fromDataset);
  dsStore.addDataset(toDataset);
  lyrStore.addLayer(lineLayer);
  lyrStore.addLayer(fromLayer);
  lyrStore.addLayer(toLayer);

  // 同步 config 到 React state
  if (_appConfig && _setAppConfig) {
    const cleanDatasets = (_appConfig.datasets || []).filter((ds: any) => !(ds.id || '').startsWith(HL_DS_PREFIX));
    const cleanLayers = (_appConfig.spec?.layers || []).filter((l: any) => !(l.id || '').startsWith(HL_LAYER_PREFIX));

    _appConfig = {
      ..._appConfig,
      datasets: [...cleanDatasets, lineDataset as any, fromDataset as any, toDataset as any],
      spec: {
        ..._appConfig.spec,
        layers: [...cleanLayers, lineLayer as any, fromLayer as any, toLayer as any],
      },
    };
    (window as any).__hlConfig = _appConfig;
    _setAppConfig(_appConfig);
  }

  console.log('[L7 Share] highlightLines added:', lines.length, 'lines + start/end points');
};

const SharePage = () => {
  const { id: projectId = '' } = useParams();
  const [assetPackageIds, setAssetPackageIds] = useState<string[] | undefined>([]);
  const [appConfig, setAppConfig] = useState<Application>();
  const [loadingLazyRows, setLoadingLazyRows] = useState(false);
  const originalConfigRef = useRef<Application | null>(null);

  const liRuntimeApp = useMemo(() => new LIRuntimeApp({ assets: [] }), []);
  (window as any).__liRuntimeApp = liRuntimeApp;

  useEffect(() => {
    _setAppConfig = setAppConfig;
  }, []);
  useEffect(() => {
    _appConfig = appConfig;
  }, [appConfig]);

  const { assets } = useMarketAssets(assetPackageIds);
  useMemo(() => liRuntimeApp.installAssets(assets), [assets]);

  useEffect(() => {
    if (!projectId) return;
    getProject(projectId)
      .then(async (project: Project) => {
        setAssetPackageIds(project.assetPackageIds);
        document.title = project.applicationConfig?.metadata?.name || 'L7VP';
        let config: Application = JSON.parse(JSON.stringify(project.applicationConfig));

        const lazyDatasets = config.datasets?.filter((ds: any) => ds.type === 'local' && ds._lazy === true) as
          | LocalDatasetSchema[]
          | undefined;
        if (lazyDatasets?.length) {
          setLoadingLazyRows(true);
          const updatedDatasets = await Promise.all(
            lazyDatasets.map(async (ds) => {
              try {
                const rows = await loadLazyRows(projectId, ds.id);
                return { ...ds, data: rows, _lazy: false };
              } catch {
                return ds;
              }
            }),
          );
          const newDatasets = config.datasets!.map((ds: any) => {
            const updated = updatedDatasets.find((u) => u.id === ds.id);
            return updated || ds;
          });
          config = { ...config, datasets: newDatasets };
          setLoadingLazyRows(false);
        }

        originalConfigRef.current = config;
        _appConfig = config;
        setAppConfig(config);
      })
      .catch((err) => console.error('加载项目失败:', err));
  }, [projectId]);

  // ===== postMessage 监听 =====
  useEffect(() => {
    const { stateManager } = liRuntimeApp;

    const handleMessage = (event: MessageEvent) => {
      console.log(
        '[L7 Share] RAW message event, origin:',
        event.origin,
        'action:',
        event.data?.action,
        'data keys:',
        Object.keys(event.data || {}),
      );
      const msg: CrossMessage = event.data || {};
      if (!msg.action) return;
      const origin = new URL(event.origin);
      const allowedHosts = ['localhost', '127.0.0.1'];
      if (origin.hostname !== window.location.hostname && !allowedHosts.includes(origin.hostname)) return;

      console.log('[L7 Share] 收到消息:', msg);

      const style = msg.style || {};

      switch (msg.action) {
        case 'highlight': {
          const points = msg.highlightPoints || [];
          console.log('[L7 Share] highlight case, points:', points.length);
          if (!points.length) {
            clearAllHighlights(stateManager);
            console.log('[L7 Share] no points, cleared highlights');
            return;
          }
          addHighlight(stateManager, points, msg.highlightLayerType || 'BubbleLayer', style);
          // 延迟确认 store 状态
          setTimeout(() => {
            const layers = stateManager.layersStore.getLayerList();
            const hl = layers.filter((l: any) => (l.id || '').startsWith('de_highlight_'));
            console.log('[L7 Share] after 1s - total layers:', layers.length, 'highlight layers:', hl.length);
          }, 1000);
          break;
        }
        case 'highlightLines': {
          const lines = msg.lines || [];
          console.log('[L7 Share] highlightLines case, lines:', lines.length);
          if (!lines.length) {
            clearAllHighlights(stateManager);
            console.log('[L7 Share] no lines, cleared highlights');
            return;
          }
          addHighlightLines(stateManager, lines, style);
          setTimeout(() => {
            const layers = stateManager.layersStore.getLayerList();
            const hl = layers.filter((l: any) => (l.id || '').startsWith('de_highlight_'));
            console.log('[L7 Share] after 1s - total layers:', layers.length, 'highlight layers:', hl.length);
          }, 1000);
          break;
        }
        case 'clearHighlight': {
          clearAllHighlights(stateManager);
          break;
        }
        case 'filter': {
          const { filters } = msg;
          if (!filters?.length) break;
          const l7Filters = filters.map((f: any) => ({
            id: f.fieldId || f.field,
            field: f.fieldId || f.field,
            type: 'string',
            operator: 'IN',
            value: Array.isArray(f.value) ? f.value : [f.value],
          }));
          stateManager.datasetStore.getDatasetList().forEach((ds: any) => {
            if (ds.type !== 'raster-tile') {
              stateManager.datasetStore.updateFilter(ds.id, {
                relation: 'AND',
                children: l7Filters,
              });
            }
          });
          break;
        }
        case 'reset': {
          clearAllHighlights(stateManager);
          if (originalConfigRef.current) {
            stateManager.initState(originalConfigRef.current);
          }
          break;
        }
      }
    };
    window.addEventListener('message', handleMessage);
    console.log('[L7 Share] message listener registered');
    return () => window.removeEventListener('message', handleMessage);
  }, [liRuntimeApp]);

  // ===== 地图事件回传给 DE =====
  useEffect(() => {
    const layerHandler = (payload: any) => {
      if (payload?.type === 'click' && payload?.feature) {
        const { coordinates, properties } = payload.feature;
        window.parent.postMessage(
          {
            action: 'featureClick',
            feature: {
              id: properties?.id || properties?.index,
              lng: coordinates?.[0],
              lat: coordinates?.[1],
              properties: properties || {},
            },
          },
          '*',
        );
      }
    };
    liRuntimeApp.eventBus.on('layer', layerHandler);
    return () => {
      liRuntimeApp.eventBus.off?.('layer', layerHandler);
    };
  }, [liRuntimeApp]);

  const { App: LIAPP } = liRuntimeApp;
  const loaded = Boolean(assets.length) && appConfig && !loadingLazyRows;

  // L7_INTEGRATION: 流式数据集 — 启动 WebSocket 实时同步
  const streamDatasetIds = useMemo(
    () =>
      (appConfig?.datasets || [])
        .filter((ds: any) => ds._stream === true)
        .map((ds: any) => ds.id),
    [appConfig],
  );
  const streamMaxWindowByDataset = useMemo(() => {
    const map: Record<string, number> = {};
    (appConfig?.datasets || []).forEach((ds: any) => {
      if (ds._stream === true && ds.metadata?.maxWindow) {
        map[ds.id] = Number(ds.metadata.maxWindow);
      }
    });
    return map;
  }, [appConfig]);
  useStreamDatasets(liRuntimeApp, streamDatasetIds, streamMaxWindowByDataset, !!loaded);

  if (!loaded) {
    return loadingLazyRows ? (
      <div
        style={{
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          width: '100vw',
          height: '100vh',
        }}
      >
        <Spin size="large" />
      </div>
    ) : null;
  }

  return (
    <div style={{ width: '100vw', height: '100vh', overflow: 'hidden' }}>
      <LIAPP style={{ width: '100%', height: '100%' }} config={appConfig} key={projectId} />
    </div>
  );
};

export default SharePage;
