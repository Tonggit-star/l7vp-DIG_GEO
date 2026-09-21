import { useEmptyModal, useMarketAssets, useStreamDatasets } from '@/hooks';
import { getProject, updateProject } from '@/services';
import { logYuyanMonitor } from '@/utils';
import type { LocalDatasetSchema } from '@antv/li-sdk';
import { LIEditor } from '@antv/li-editor';
import { Spin } from 'antd';
import { useEffect, useMemo, useRef, useState, useCallback } from 'react';
import { useParams } from 'umi';
import TileSelectorModal from '@/components/TileSelectorModal';
import type { TileConfig } from '@/types/tile-config';
import { DefaultEditorWidgets, editorWidgetsWithBuilder as editorWidgets } from './editor-widgets';
import { useEditorNavbarKey } from './hooks';
import './index.less';
import type { Application, BuilderState } from './types';

const API_BASE_URL = '/api';

/**
 * 异步加载懒加载数据集的数据行
 */
const loadLazyRows = async (projectId: string, datasetId: string) => {
  const allRows: any[] = [];
  const size = 500;
  let page = 0;
  let total = 0;

  do {
    const res = await fetch(`${API_BASE_URL}/projects/${projectId}/datasets/${datasetId}/rows?page=${page}&size=${size}`);
    if (!res.ok) break;
    const data = await res.json();
    allRows.push(...data.rows);
    total = data.total;
    page++;
  } while (allRows.length < total);

  return allRows;
};

LIEditor.DefaultEditorWidgets = DefaultEditorWidgets;

// L7_INTEGRATION: 流式数据集识别。服务端 assemble 会给流式数据集打 _stream 标记；
// 编辑器运行时里新建的流式数据集尚未经 assemble，只有 metadata.stream。两者都认。
const isStreamDataset = (ds: any) => ds?._stream === true || ds?.metadata?.stream === true;

const getStreamDatasetIds = (app?: Application | null) =>
  (app?.datasets || []).filter(isStreamDataset).map((ds: any) => ds.id);

const getStreamMaxWindowMap = (app?: Application | null) => {
  const map: Record<string, number> = {};
  (app?.datasets || []).forEach((ds: any) => {
    if (isStreamDataset(ds) && ds?.metadata?.maxWindow) {
      map[ds.id] = Number(ds.metadata.maxWindow);
    }
  });
  return map;
};

const Builder = () => {
  const { id: projectId = '' } = useParams();
  // 供 UploadDataset 组件获取当前项目 ID，用于直接上传数据集行数据
  (window as any).__L7VP_PROJECT_ID__ = projectId;
  const liEditor = useMemo(() => {
    const editor = new LIEditor({ assets: [], editorWidgets });
    (window as any).liRuntimeApp = editor.runtimeApp;
    return editor;
  }, []);
  const [assetPackageIds, setAssetPackageIds] = useState<string[] | undefined>([]);
  const { assets } = useMarketAssets(assetPackageIds);
  const activeNavbarKey = useEditorNavbarKey(liEditor);
  const { emptyModal, emptyContextHolder } = useEmptyModal();

  // 搭建应用状态
  const [loadingLazyRows, setLoadingLazyRows] = useState(false);
  const [builderState, setBuilderState] = useState<BuilderState>({
    project: undefined,
  });

  const defaultApplication = builderState.project?.applicationConfig;

  // L7_INTEGRATION: WS 订阅的数据集列表必须跟随「编辑器当前实时数据集」，而不是仅跟随
  // 页面加载时的项目快照 builderState.project.applicationConfig（后者不会随编辑器增删流式
  // 数据集而更新，导致新加的流式数据集必须刷新页面才有数据）。这里把 liEditor 'change' 事件
  // 携带的最新 application 缓存一份用于推导流式数据集 id；首个 change 到来前回退 defaultApplication。
  // 用 ref 记录上次的流式 id 串，避免每次都 setState（'change' 触发非常频繁）。
  const [liveStreamApp, setLiveStreamApp] = useState<Application | null>(null);
  const liveStreamIdsKeyRef = useRef('');

  // 安装资产
  useMemo(() => liEditor.installAssets(assets), [assets]);

  // 查询数据
  useEffect(() => {
    let cancelled = false;
    console.log('[Builder] projectId changed to:', projectId);
    // 切换项目时立即清空旧项目数据，确保 EditorApp 卸载/重建
    console.log('[Builder] clearing old project state...');
    setBuilderState({ project: undefined });
    setLiveStreamApp(null);
    liveStreamIdsKeyRef.current = '';
    setLoadingLazyRows(false);

    getProject(projectId)
      .then((project) => {
        if (cancelled) return;
        console.log('[Builder] project loaded:', project.projectName, 'widgets count:', project.applicationConfig?.spec?.widgets?.length);
        // Debug: log widget container references
        const widgets = project.applicationConfig?.spec?.widgets || [];
        const layoutWidget = widgets.find((w: any) => w.type === 'AnalysisLayout' || w.type === 'BaseLayout');
        console.log('[Builder] layout widget id:', layoutWidget?.id, 'type:', layoutWidget?.type);
        widgets.forEach((w: any, i: number) => {
          if (w.container) {
            const match = w.container.id === layoutWidget?.id;
            console.log(`[Builder] widget[${i}] id=${w.id} type=${w.type} container.id=${w.container.id} match=${match ? 'YES' : 'NO -> ORPHANED!'}`);
          } else if (w.type !== 'AnalysisLayout' && w.type !== 'BaseLayout') {
            console.log(`[Builder] widget[${i}] id=${w.id} type=${w.type} NO CONTAINER -> will be top-level`);
          }
        });
        setAssetPackageIds(project.assetPackageIds);
        setBuilderState({ project });
        document.title = `${project.applicationConfig.metadata.name}`;
        logYuyanMonitor(14, { c1: project.projectName, c2: project.creatTime });
        // 有懒加载数据集时，立即置 loading 状态防止图层提前渲染导致 source undefined 崩溃
        const hasLazy = project.applicationConfig.datasets?.some(
          (ds: any) => ds.type === 'local' && ds._lazy === true,
        );
        if (hasLazy) {
          setLoadingLazyRows(true);
        }
      })
      .catch((message) => {
        if (cancelled) return;
        emptyModal(message);
      });

    return () => { cancelled = true; };
  }, [projectId]);

  // 异步加载懒加载数据集的数据行
  useEffect(() => {
    const appConfig = builderState.project?.applicationConfig;
    if (!appConfig?.datasets) return;

    const lazyDatasets = appConfig.datasets.filter(
      (ds): ds is LocalDatasetSchema => ds.type === 'local' && (ds as LocalDatasetSchema)._lazy === true,
    );

    if (lazyDatasets.length === 0) return;

    setLoadingLazyRows(true);
    const loadAllDatasets = async () => {
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

      setBuilderState((state) => {
        if (!state.project) return state;
        const app = state.project.applicationConfig;
        if (!app.datasets) return state;
        const newDatasets = app.datasets.map((ds) => {
          const updated = updatedDatasets.find((u) => u.id === ds.id);
          return updated || ds;
        });
        return {
          ...state,
          project: { ...state.project, applicationConfig: { ...app, datasets: newDatasets } },
        };
      });
    };

    loadAllDatasets().finally(() => setLoadingLazyRows(false));
  }, [builderState.project?.applicationConfig, projectId]);

  // 更新项目时存储数据
  useEffect(() => {
    const onUpdate = (applicationConfig: Application) => {
      // setBuilderState((state) => ({...state, project: {...state.project!, applicationConfig}}));
      console.log('[Builder] onUpdate spec.widgets count=', applicationConfig.spec?.widgets?.length, 'spec keys=', Object.keys(applicationConfig.spec || {}));
      // 实时缓存最新 application（仅当流式数据集集合变化时才 setState，避免频繁重渲染）
      const key = getStreamDatasetIds(applicationConfig).join(',');
      if (key !== liveStreamIdsKeyRef.current) {
        liveStreamIdsKeyRef.current = key;
        setLiveStreamApp(applicationConfig);
      }
      const { projectName, description } = builderState.project!;
      updateProject(projectId, { projectName, description, applicationConfig });
    };
    liEditor.on('change', onUpdate);
    return () => {
      liEditor.off('change', onUpdate);
    };
  }, [builderState.project, liEditor, projectId]);

  const { Editor: EditorApp } = liEditor;
  const loaded = Boolean(assets.length) && defaultApplication;

  // L7_INTEGRATION: 流式数据集 — 启动 WebSocket 实时同步
  // 订阅源用「编辑器当前实时 application（含本次会话新加/删除的流式数据集）」，
  // 首个 change 前回退到加载快照 defaultApplication，保证新增流式数据集无需刷新即可有数据。
  const streamApp = liveStreamApp ?? defaultApplication;
  const streamDatasetIds = useMemo(() => getStreamDatasetIds(streamApp), [streamApp]);
  const streamMaxWindowByDataset = useMemo(() => getStreamMaxWindowMap(streamApp), [streamApp]);
  useStreamDatasets(liEditor.runtimeApp, streamDatasetIds, streamMaxWindowByDataset, !!loaded);

  // TileSelector for fresh projects
  const [tileSelectorVisible, setTileSelectorVisible] = useState(false);
  const [tileSelectorShown, setTileSelectorShown] = useState(false);

  // Editor remount key (incremented on tile selection to force refresh)
  const [editorKey, setEditorKey] = useState(0);
  // Reset editorKey when project changes
  useEffect(() => { setEditorKey(0); }, [projectId]);

  // Show tile selector once when project loads and has only default tile
  useEffect(() => {
    if (!loaded || tileSelectorShown || !defaultApplication) return;
    const layers = defaultApplication.spec?.layers || [];
    const datasets = defaultApplication.datasets || [];
    // Only show for fresh projects with just the default basemap
    const hasOnlyDefaultTile = datasets.length === 0 && layers.length === 0;
    if (hasOnlyDefaultTile) {
      setTileSelectorVisible(true);
      setTileSelectorShown(true);
    }
  }, [loaded, defaultApplication, tileSelectorShown]);

  const handleTileConfirm = useCallback(async (selectedTiles: TileConfig[]) => {
    setTileSelectorVisible(false);
    if (!defaultApplication || selectedTiles.length === 0) return;

    const ts = Date.now();
    // 默认瓦片按 defaultNum 排序，小号在下层
    const sorted = [...selectedTiles].sort((a, b) => (a.defaultNum || 99) - (b.defaultNum || 99));
    const newDatasets = sorted.map((tile, i) => {
      // 瓦片 URL 变换
      let tileUrl = tile.tileUrl;
      const origin = tile.origin;
      const isCustomOrigin = origin && origin !== '-180,90';
      const originLat = isCustomOrigin ? parseFloat(origin!.split(',')[1]) : 90;

      if (tile.tileScheme === 'TMS') {
        // TMS 翻转 Y 轴
        tileUrl = tileUrl.replace(/\{y\}/g, '{-y}');
        if (isCustomOrigin) {
          // TMS + 自定义原点：需要同时翻转和偏移，暂用 {-y} 翻转，原点偏移暂不支持
          console.warn('[Builder] TMS + custom origin not fully supported, origin offset ignored for:', tile.tileName);
        }
      } else if (isCustomOrigin) {
        // XYZ + 自定义原点：用 {oy:lat0} 替代 {y}，L7 内部计算 Y 偏移
        tileUrl = tileUrl.replace(/\{y\}/g, `{oy:${originLat}}`);
      }
      return {
        id: `tile_${ts}_${i}`,
        type: 'raster-tile' as const,
        metadata: { name: tile.tileName, description: '瓦片底图' },
        properties: { type: 'xyz-tile' as const, url: tileUrl, minZoom: tile.minZoom ?? 0, maxZoom: tile.maxZoom ?? 18, crs: tile.crs || 'EPSG:3857', tileScheme: tile.tileScheme || 'XYZ' },
      };
    });
    const newLayers = sorted.map((tile, i) => ({
      id: `tile_layer_${ts}_${i}`,
      type: 'TileLayer',
      metadata: { name: tile.tileName },
      sourceConfig: { datasetId: `tile_${ts}_${i}`, parser: { type: 'rasterTile' } },
      visConfig: { visible: true, style: { opacity: 1 }, minZoom: tile.minZoom ?? 0, maxZoom: tile.maxZoom ?? 18, blend: 'normal', crs: tile.crs || 'EPSG:3857', tileScheme: tile.tileScheme || 'XYZ' },
    }));

    const updatedConfig: Application = {
      ...defaultApplication,
      datasets: [...defaultApplication.datasets, ...newDatasets],
      spec: { ...defaultApplication.spec, layers: [...defaultApplication.spec.layers, ...newLayers] },
    };

    // Save updated config and update state in-place (no reload)
    const { projectName, description } = builderState.project!;
    await updateProject(projectId, { projectName, description, applicationConfig: updatedConfig });
    setBuilderState({ project: { ...builderState.project!, applicationConfig: updatedConfig } });
    setEditorKey(prev => prev + 1);
  }, [defaultApplication, builderState.project, projectId]);

  return (
    <div className="li-builder">
      {emptyContextHolder}
      <TileSelectorModal
        visible={tileSelectorVisible}
        onCancel={() => setTileSelectorVisible(false)}
        onConfirm={handleTileConfirm}
      />
      {loadingLazyRows && (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
          <Spin tip="正在加载数据..." />
        </div>
      )}
      {loaded && !loadingLazyRows && (
        <EditorApp
          key={`${projectId}_${editorKey}`}
          className="li-builder__editor"
          defaultActiveNavMenuKey={activeNavbarKey}
          defaultApplication={defaultApplication}
        />
      )}
    </div>
  );
};

export default Builder;
