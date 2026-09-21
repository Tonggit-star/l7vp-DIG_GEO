import { getUniqueId } from '@antv/li-sdk';

/**
 * 「智能体配置桥」的指令执行：把 `dataset.create` / `layer.update` 落到**编辑器状态**上。
 *
 * 为什么必须走编辑器状态（而不是直接调后端接口写库）：Builder 每次 `liEditor.on('change')`
 * 都会 PUT 一份完整的 application 快照，后端 `ApplicationAssembler.disassemble()` 按 id 反向删除——
 * 图层无条件删、数据集变孤儿就删。所以任何带外写库的结果都会在下一次自动保存时被抹掉；
 * 只有写进编辑器状态、随快照一起提交的东西才真的留得下来。
 *
 * 另：运行时 store（li-sdk 的 datasetStore/layerStore）也不行——li-editor 从不把它反写回项目。
 */

/** 执行上下文：由组件注入（编辑器状态、appService、项目 id 都只有页面知道） */
export type ExecContext = {
  projectId: string;
  /** 读编辑器状态快照（异步等待期间要反复读，所以给的是取值函数） */
  getSnapshot: () => any;
  /** 写编辑器状态（immer draft），改动由产品自身的自动保存落库 */
  updateState: (updater: (draft: any) => void) => void;
  /** 图层资产查询：换可视化类型要拿新资产的默认样式，与编辑器里新建图层同源 */
  appService?: {
    getImplementLayer?: (name: string) => any;
    getImplementLayerDefaultVis?: (name: string) => Record<string, any> | undefined;
  };
};

/** 自动建图层走 requestIdleCallback，不是同步的，得等它出现 */
const AUTO_CREATE_WAIT_MS = 3000;
const AUTO_CREATE_POLL_MS = 120;

/** dataset.create 回执里每个自动图层的信息（智能体拿去接着调 layer.update） */
type CreatedLayerInfo = {
  layerId: string;
  layerName: string | null;
  type: string;
  visible: boolean;
};

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const asRecord = (value: unknown): Record<string, any> =>
  value && typeof value === 'object' ? (value as Record<string, any>) : {};

/** 图层绑定的数据集 id（与 li-editor 的 `getLayerDatasetId` 同口径） */
const layerDatasetId = (layer: any): string | undefined =>
  layer?.sourceConfig?.datasetId ?? layer?.dataset ?? undefined;

const layersOfDataset = (snapshot: any, datasetId: string): any[] =>
  (snapshot?.layers ?? []).filter((layer: any) => layerDatasetId(layer) === datasetId);

/** 等自动创建的图层出现；超时返回空数组（不抛：数据集已经建好了，如实回报比整条失败有用） */
const waitForAutoCreatedLayers = async (ctx: ExecContext, datasetId: string): Promise<any[]> => {
  const deadline = Date.now() + AUTO_CREATE_WAIT_MS;
  // 轮询快照、而不是订阅 editorState：订阅会动 editorState 的 listener 引用计数，
  // 而那个计数 0→1 时会触发数据集 query 订阅的整体重建（EditorDatasetManager.onSubscribe），
  // 没必要为一次等待去碰它。
  for (;;) {
    const hit = layersOfDataset(ctx.getSnapshot(), datasetId);
    if (hit.length) return hit;
    if (Date.now() >= deadline) return [];
    await sleep(AUTO_CREATE_POLL_MS);
  }
};

/** 智能体给的列 → li-sdk 的 DatasetField */
const toDatasetFields = (columns: any[]) =>
  columns.map((column) => {
    const field: Record<string, any> = { name: column.name, type: column.type || 'string' };
    if (column.displayName) field.displayName = column.displayName;
    return field;
  });

/**
 * 智能体给的列 → 上传接口的 ColumnDef。
 * 两处字段名不同：SDK 侧叫 `displayName`，后端 `ColumnDef` 叫 `comment`（列注释）——
 * 转换放在这里，别指望后端认识 displayName。
 */
const toColumnDefs = (columns: any[]) =>
  columns.map((column, index) => {
    const def: Record<string, any> = { name: column.name, type: column.type || 'string', index };
    if (column.displayName) def.comment = column.displayName;
    return def;
  });

/**
 * 各图层资产的样式字段名映射（字段名逐一到资产源码里核过，跨资产完全不同名，所以必须要这张表）。
 *
 * 只映射「颜色 / 尺寸 / 透明度」三个跨资产同义的概念，且**只登记确实存在的字段**：
 * 没登记的（如热力图没有「单一颜色」、瓦片底图没有尺寸）不是漏了，而是这种改法在该资产上没有意义——
 * 遇到就明确报错，别猜、也别静默忽略（静默忽略会让智能体以为改成功了）。
 *
 * 路径支持 `a.b`（如 LineLayer 的透明度在 `style.opacity`）；`color` 允许给多个路径，
 * 因为 LineLayer / ArcLayer 的颜色有两套写法（顶层 `color` 与 `style.sourceColor/targetColor`
 * 分别由样式面板与默认配置写入），只写其中一个可能不生效，所以三个一起写成同一个值。
 */
const STYLE_FIELDS: Record<string, { color?: string[]; size?: string; opacity?: string }> = {
  BubbleLayer: { color: ['fillColor'], size: 'radius', opacity: 'opacity' },
  MVTLayer: { color: ['fillColor'], size: 'radius', opacity: 'opacity' },
  ChoroplethLayer: { color: ['fillColor'], opacity: 'opacity' }, // 无尺寸概念（只有线宽）
  H3HexagonLayer: { color: ['fillColor'], opacity: 'opacity' }, // 无尺寸概念
  LineLayer: {
    color: ['color', 'style.sourceColor', 'style.targetColor'],
    size: 'size',
    opacity: 'style.opacity',
  },
  ArcLayer: { color: ['style.sourceColor', 'style.targetColor'], size: 'size', opacity: 'style.opacity' },
  IconLayer: { size: 'radius', opacity: 'iconStyle.opacity' }, // 无颜色概念：图标自带颜色
  GridLayer: { color: ['color'], opacity: 'style.opacity' }, // aggregateSize 是网格尺寸，不是点半径
  TileLayer: { opacity: 'style.opacity' }, // 瓦片底图：没有颜色/尺寸
};

/** 按点写入 visConfig，支持 `a.b` 路径（逐层浅拷贝，不动原对象） */
const setByPath = (config: Record<string, any>, path: string, value: any) => {
  const parts = path.split('.');
  const root = { ...config };
  let cursor = root;
  for (let i = 0; i < parts.length - 1; i++) {
    const key = parts[i];
    cursor[key] = { ...(cursor[key] ?? {}) };
    cursor = cursor[key];
  }
  cursor[parts[parts.length - 1]] = value;
  return root;
};

/**
 * 取某图层的样式字段映射。聚合图层是双渲染器（`renderer:'dot'|'icon'`），两套键并存于同一 visConfig、
 * 由 renderer 决定读哪套：dot 读填充色，icon 读图标（图标自带颜色、故无 color 可改）。
 */
const resolveStyleFields = (layer: any): { color?: string[]; size?: string; opacity?: string } => {
  if (layer?.type === 'ClusterLayer') {
    return layer?.visConfig?.renderer === 'icon'
      ? { size: 'radius', opacity: 'iconStyle.opacity' }
      : { color: ['fillColor'], size: 'radius', opacity: 'opacity' };
  }
  return STYLE_FIELDS[layer?.type] ?? {};
};

// ==================== dataset.create ====================

/**
 * 建数据集，并让**产品自己**据此生成图层（两步建图层的「第一步」）。
 *
 * 关键点是「不自己造图层」：数据集带上 `metadata._autoCreateLayers` 后，li-editor 会按列名
 * 推断可视化类型（点对→BubbleLayer、Line→LineLayer、Polygon→ChoroplethLayer…）、生成图层、
 * 绑 LayerPopup 字段、把地图飞到数据上。智能体因此只要描述「数据是什么」，不用描述「图层长什么样」。
 */
export const executeDatasetCreate = async (payload: Record<string, any>, ctx: ExecContext) => {
  const datasetName = String(payload.datasetName ?? '').trim();
  const columns = Array.isArray(payload.columns) ? payload.columns : [];
  const rows = Array.isArray(payload.rows) ? payload.rows : [];
  if (!datasetName) throw new Error('dataset.create 缺少 datasetName');
  if (!columns.length) throw new Error('dataset.create 缺少 columns');
  if (!rows.length) throw new Error('dataset.create 缺少 rows');

  const datasetId = getUniqueId('dataset');
  const autoCreateLayer = payload.autoCreateLayer !== false;
  const layerName = payload.layerName ? String(payload.layerName) : null;

  // 1) 先落库行数据：自动保存会剥掉 dataset.data（stripDataRowsFromApplication），
  //    不上传的话刷新/重开项目后数据集就是空的。
  const res = await fetch(`/api/projects/${ctx.projectId}/datasets/upload`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      id: datasetId,
      datasetName,
      type: 'local',
      columns: toColumnDefs(columns),
      rows,
    }),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    // 上传失败就不往编辑器里塞：否则会留下一个「有图层却查不到数据」的假数据集
    throw new Error(`数据集行数据保存失败：HTTP ${res.status} ${text.slice(0, 200)}`);
  }

  // 2) 再进编辑器状态。`_autoCreateLayers` 是纯触发标记（保存时会被剥掉，不进项目）。
  ctx.updateState((draft) => {
    draft.datasets.push({
      id: datasetId,
      type: 'local',
      metadata: { name: datasetName, ...(autoCreateLayer ? { _autoCreateLayers: true } : {}) },
      columns: toDatasetFields(columns),
      data: rows,
    });
  });

  if (!autoCreateLayer) {
    return { datasetId, datasetName, rowCount: rows.length, autoCreateLayer: false, layers: [] };
  }

  // 3) 等自动生成的图层出现。按 datasetId 反查最稳——图层名是产品按「数据集名_字段名」自己拼的。
  const created = await waitForAutoCreatedLayers(ctx, datasetId);
  if (!created.length) {
    return {
      datasetId,
      datasetName,
      rowCount: rows.length,
      autoCreateLayer: true,
      layers: [],
      warning: '数据集已创建，但没有自动生成图层（多半是列里没有可识别的经纬度/几何字段）',
    };
  }

  // 4) 指定的图层名只在「恰好一个自动图层」时应用：多个图层时改谁都不对，
  //    留给智能体自己按返回的 layerId 调 layer.update（回执里会说明）。
  const single = created.length === 1;
  if (layerName && single) {
    const layerId = created[0].id;
    ctx.updateState((draft) => {
      const target = (draft.layers ?? []).find((layer: any) => layer.id === layerId);
      if (target) target.metadata = { ...(target.metadata ?? {}), name: layerName };
    });
  }

  // 回执里的图层清单现场重读，确保名字/可见性是改名之后的真实值
  const createdIds = new Set(created.map((layer: any) => layer.id));
  const layers: CreatedLayerInfo[] = (ctx.getSnapshot()?.layers ?? [])
    .filter((layer: any) => createdIds.has(layer.id))
    .map((layer: any) => ({
      layerId: layer.id,
      layerName: layer.metadata?.name ?? null,
      type: layer.type,
      visible: layer?.visConfig?.visible !== false,
    }));
  const hidden = layers.filter((layer) => !layer.visible).map((layer) => layer.layerId);

  return {
    datasetId,
    datasetName,
    rowCount: rows.length,
    autoCreateLayer: true,
    layers,
    ...(layerName && single ? { renamedTo: layerName } : {}),
    ...(layerName && !single
      ? { warning: `自动生成了 ${layers.length} 个图层，未改名（layerName 只对单图层生效）；可用 layer.update 按 layerId 逐个改` }
      : {}),
    // 自动图层的可见性是产品定的：只有前两个默认打开，其余要显式打开。
    // 这里必须指 layer.update（写进项目配置、会落库），**不能**指 layer.visibility——
    // 后者是运行时指令，只改当前页面视图、刷新即恢复，改完用户刷新一看还是隐藏的。
    ...(hidden.length
      ? {
          note: `自动生成的图层里只有前两个默认可见：${hidden.join('、')}；` +
            `要让它以后一直显示，用 layer.update 传 visible:true（会落库）`,
        }
      : {}),
  };
};

// ==================== layer.update ====================

/**
 * 改图层属性（受控白名单）。
 *
 * 为什么不放开自由 visConfig：各图层资产的样式字段名完全不同（BubbleLayer 用 fillColor/radius、
 * LineLayer 用 color/size…），放开写等于把「资产内部结构」变成对外契约，改一个资产就破坏契约。
 * 这里只暴露跨资产同义的少数参数，映射不到就明确报错。
 *
 * 参数：`name` / `description` / `visible` / `parser`（坐标字段映射）/ `visType`（换可视化类型）
 * / `color` / `size` / `opacity`。后三项与 `visType` **互斥**（字段名随资产变，得先换类型再改样式）。
 * 只给 `visType` 时整块 visConfig 重置为新资产默认样式（与编辑器里换类型同一行为），可见性保留。
 */
export const executeLayerUpdate = (payload: Record<string, any>, ctx: ExecContext) => {
  const layerId = payload.layerId;
  if (!layerId) throw new Error('layer.update 缺少 layerId');

  const snapshot = ctx.getSnapshot();
  const layer = (snapshot?.layers ?? []).find((item: any) => item.id === layerId);
  if (!layer) throw new Error(`图层不存在（可能已被删除，或还没保存）：${layerId}`);

  const layerLabel = layer.metadata?.name ?? layerId;

  // ---- 先全部校验，再一次性写入：不让「改了一半才失败」留下半个结果 ----

  // 换类型与改样式**不能同一条指令**：样式的字段名是「旧资产」的，换完类型就失效了，
  // 照旧映射去写会写进一个没人读的键（表现为「改了没反应」）。所以明确要求分两步。
  const styleKeys = ['color', 'size', 'opacity'].filter((key) => payload[key] !== undefined);
  if (payload.visType !== undefined && styleKeys.length) {
    throw new Error(
      `layer.update 不能在换可视化类型（visType）的同时改样式（${styleKeys.join('、')}）：` +
        `样式字段名随资产而变，请分两步——先只给 visType 换类型，再单独发一条改样式`,
    );
  }

  const mapping = resolveStyleFields(layer);
  const styleChecks: Array<[string, string[] | undefined, any]> = [
    ['color', mapping.color, payload.color],
    ['size', mapping.size ? [mapping.size] : undefined, payload.size],
    ['opacity', mapping.opacity ? [mapping.opacity] : undefined, payload.opacity],
  ];
  for (const [name, fields, value] of styleChecks) {
    if (value === undefined) continue;
    if (!fields?.length) {
      throw new Error(
        `图层「${layerLabel}」（可视化类型 ${layer.type}）没有 ${name} 对应的样式字段，` +
          `这一项在该图层上改不了；可改的项见 layer.update 的说明`,
      );
    }
  }

  let nextType: string | null = null;
  let nextDefaultVis: Record<string, any> | null = null;
  if (payload.visType !== undefined) {
    nextType = String(payload.visType);
    if (nextType === layer.type) {
      nextType = null; // 同类型不算换，省得把样式重置掉
    } else if (!ctx.appService?.getImplementLayer?.(nextType)) {
      throw new Error(`未注册的图层资产：${nextType}（本项目可用的资产见编辑器「图层」面板的添加列表）`);
    } else {
      nextDefaultVis = ctx.appService?.getImplementLayerDefaultVis?.(nextType) ?? {};
    }
  }

  const changed: string[] = [];
  const applied: Record<string, any> = {};

  ctx.updateState((draft) => {
    const target = (draft.layers ?? []).find((item: any) => item.id === layerId);
    if (!target) throw new Error(`图层不存在：${layerId}`);

    const metadata = { ...(target.metadata ?? {}) };
    let visConfig = { ...(target.visConfig ?? {}) };

    if (payload.name !== undefined) {
      metadata.name = payload.name;
      changed.push('name');
      applied.name = payload.name;
    }
    if (payload.description !== undefined) {
      // 图层备注（存 metadata.description，纯说明、不参与渲染）
      metadata.description = payload.description;
      changed.push('description');
      applied.description = payload.description;
    }
    if (payload.visible !== undefined) {
      visConfig.visible = payload.visible;
      changed.push('visible');
      applied.visible = payload.visible;
    }
    if (payload.parser !== undefined) {
      // 坐标字段映射：{x,y} 或 {geometry}，列名已由后端按数据集列校验过
      const parser = { ...asRecord(target.sourceConfig?.parser), type: 'json' } as Record<string, any>;
      if (payload.parser.geometry) {
        parser.geometry = payload.parser.geometry;
        delete parser.x;
        delete parser.y;
      } else {
        parser.x = payload.parser.x;
        parser.y = payload.parser.y;
        delete parser.geometry;
      }
      target.sourceConfig = { ...(target.sourceConfig ?? {}), parser };
      changed.push('parser');
      applied.parser = { x: parser.x, y: parser.y, geometry: parser.geometry };
    }
    if (nextType) {
      // 换可视化类型：整层换资产，旧资产的样式字段在新资产里没有意义，故重置为新资产默认样式，
      // 但保留可见性（刚设的显隐不该因为换类型而变）
      const keepVisible = visConfig.visible !== false;
      target.type = nextType;
      target.visConfig = { ...(nextDefaultVis ?? {}), visible: keepVisible };
      changed.push('visType');
      applied.visType = nextType;
      applied.visible = keepVisible;
    } else {
      // 样式写入：按资产字段名映射（可能是 `style.opacity` 这种嵌套路径）。
      // 注意这会覆盖「按字段映射」的写法（如 radius: {field, value} → 固定数值），
      // 也就是「改颜色/大小」的语义是「改成这个固定值」，而不是「换一个映射字段」。
      if (payload.color !== undefined) {
        for (const path of mapping.color!) visConfig = setByPath(visConfig, path, payload.color);
        changed.push('color');
        applied.color = payload.color;
      }
      if (payload.size !== undefined) {
        visConfig = setByPath(visConfig, mapping.size!, payload.size);
        changed.push('size');
        applied.size = payload.size;
      }
      if (payload.opacity !== undefined) {
        visConfig = setByPath(visConfig, mapping.opacity!, payload.opacity);
        changed.push('opacity');
        applied.opacity = payload.opacity;
      }
      target.visConfig = visConfig;
    }

    target.metadata = metadata;
  });

  // 回执里的名字/类型现场重读，避免报的是改之前的值
  const after = (ctx.getSnapshot()?.layers ?? []).find((item: any) => item.id === layerId);
  return {
    layerId,
    layerName: after?.metadata?.name ?? layerLabel,
    type: after?.type ?? layer.type,
    changed,
    applied,
    note: '改动已写入编辑器状态，随 Builder 自动保存落库；页面上即时生效',
  };
};
