import { useEditorService, useEditorState } from '@antv/li-editor';
import { Tag } from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import { readAgentEnabled, subscribeAgentEnabled } from './agentFlag';
import type { ExecContext } from './execute';
import { executeDatasetCreate, executeLayerUpdate } from './execute';

/** 后端下发的指令（见 doc/AGENT_MAP_CONTROL_SKILL.md §4 N4） */
type AgentCommand = {
  cmdId: string;
  seq: number;
  type: string;
  payload?: Record<string, any>;
};

type Phase = 'off' | 'connecting' | 'idle' | 'error';

/** 长轮询挂起时长（秒）；服务端上限 30。编辑器控件没有属性面板，写死即可 */
const POLL_TIMEOUT_SEC = 25;

/**
 * 解析项目 id：Builder 页由页面写入 `__L7VP_PROJECT_ID__`，取不到再从 hash 路由兜底。
 * （与 AgentBridge 里同名函数同一套口径，两处都是「拿当前项目」这一个诉求。）
 */
const resolveProjectId = (): string | undefined => {
  const fromWindow = (window as any).__L7VP_PROJECT_ID__;
  if (fromWindow) return String(fromWindow);
  const matched = window.location.hash.match(/\/(?:builder|app|template|share)\/([^/?#]+)/);
  return matched ? matched[1] : undefined;
};

const describe = (type: string, detail: Record<string, any> | null): string => {
  if (!detail) return type;
  switch (type) {
    case 'dataset.create':
      return `建数据集「${detail.datasetName}」${detail.layers?.length ?? 0} 个图层`;
    case 'layer.update':
      return `改图层「${detail.layerName}」${(detail.changed ?? []).join('、')}`;
    default:
      return type;
  }
};

/**
 * 智能体配置桥（编辑器侧执行器，v1.4）——**两步建图层**的落地处。
 *
 * 与地图侧的「智能体桥」（`@antv/li-analysis-assets` 的 AgentBridge）分工：
 * - 那个跑在**运行时 store** 上，只能改视图（显隐/层级/视野），刷新即恢复；
 * - 本组件跑在**编辑器状态**上，写进去的东西会被 Builder 的自动保存落库，所以只有它能执行
 *   会改项目配置的指令（`dataset.create` / `layer.update`）。
 *
 * 三条与 AgentBridge 一致的硬约束：
 * 1. 只通过 `updateState` 改编辑器状态，不碰 li-sdk 的运行时 store（会被 `initState` 整体替换）；
 * 2. **必须用户显式授权**：授权开关不在这里，而是复用地图侧 AgentBridge 的「接入智能体」开关
 *    （它把状态写到 `window.__L7VP_AGENT_ENABLED__` 并广播事件，见本目录 agentFlag.ts）。开关没开就完全不轮询。
 * 3. 取令必须带 `capabilities=config`，否则会把只该由地图侧执行的运行时指令抢过来执行掉。
 *
 * 渲染上只占一个位置：**出错时**才在导航栏底部提示一行；授权关闭、连接中、等待指令一律渲染
 * `null`（轮询照旧，只是不显示状态）。
 */
const AgentConfigBridge: React.FC = () => {
  const { state, updateState } = useEditorState();
  const { appService } = useEditorService();
  const [enabled, setEnabled] = useState<boolean>(() => readAgentEnabled());
  const [phase, setPhase] = useState<Phase>('off');
  const [message, setMessage] = useState('');

  // 授权状态来自 window（另一个包写入），订阅事件而不是轮询
  useEffect(() => {
    const unsubscribe = subscribeAgentEnabled(setEnabled);
    setEnabled(readAgentEnabled());
    return unsubscribe;
  }, []);

  // 执行器要读「最新的」编辑器状态，用 ref 镜像：轮询循环长期存活，不能把 state 写进它的依赖
  const stateRef = useRef(state);
  stateRef.current = state;
  const ctxRef = useRef<ExecContext>({
    projectId: '',
    getSnapshot: () => stateRef.current,
    updateState,
  });
  ctxRef.current = {
    projectId: resolveProjectId() ?? '',
    getSnapshot: () => stateRef.current,
    // 换可视化类型要拿新资产的默认样式；与编辑器里新建图层同源
    appService: appService as ExecContext['appService'],
    updateState,
  };

  useEffect(() => {
    if (!enabled) {
      setPhase('off');
      setMessage('');
      return undefined;
    }
    if (!resolveProjectId()) {
      setPhase('error');
      setMessage('未获取到项目 ID');
      return undefined;
    }

    let stopped = false;
    let controller: AbortController | null = null;
    let cursor: number | null = null;
    let count = 0;

    setPhase('connecting');
    setMessage('等待指令…');

    const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

    const run = async () => {
      while (!stopped) {
        const projectId = resolveProjectId() as string;
        controller = new AbortController();
        try {
          const params = new URLSearchParams();
          // 首次不带 since：服务端只回游标不回放，避免执行页面打开前积压的陈旧指令
          if (cursor !== null) params.set('since', String(cursor));
          params.set('timeout', String(POLL_TIMEOUT_SEC));
          // 只取「会写项目配置」的指令；运行时指令留给地图侧的智能体桥
          params.set('capabilities', 'config');

          const res = await fetch(`/api/projects/${projectId}/agent/commands?${params.toString()}`, {
            signal: controller.signal,
          });
          if (stopped) return;
          const data = await res.json().catch(() => ({}));
          if (!res.ok) throw new Error(data?.error || `HTTP ${res.status}`);

          if (typeof data?.seq === 'number') cursor = data.seq;
          setPhase('idle');
          setMessage(count > 0 ? `已写入 ${count} 条` : '等待指令…');

          const commands: AgentCommand[] = Array.isArray(data?.commands) ? data.commands : [];
          for (const cmd of commands) {
            if (stopped) return;
            let ok = true;
            let error: string | null = null;
            let detail: Record<string, any> | null = null;
            try {
              const ctx = ctxRef.current as ExecContext;
              if (cmd.type === 'dataset.create') {
                detail = await executeDatasetCreate(cmd.payload ?? {}, ctx);
              } else if (cmd.type === 'layer.update') {
                detail = executeLayerUpdate(cmd.payload ?? {}, ctx);
              } else {
                throw new Error(`本执行器不支持指令类型：${cmd.type}`);
              }
            } catch (err: any) {
              ok = false;
              error = err?.message || String(err);
            }
            count += 1;
            setMessage(ok ? `已写入 ${count} 条：${describe(cmd.type, detail)}` : `指令失败：${error}`);

            // 回执（N5）：智能体据此确认「页面真的执行了」
            try {
              await fetch(`/api/projects/${projectId}/agent/commands/${cmd.cmdId}/result`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ ok, error, detail }),
                signal: controller.signal,
              });
            } catch (err) {
              // 回执失败不影响本页执行，下一条继续
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
  }, [enabled]);

  if (!enabled) return null;

  // 授权开着时**不显示**常驻状态行：原来那行「智能体配置桥：等待指令…」在导航栏底部一直挂着，
  // 是纯噪音（`message` 里那些「已写入 N 条」也不值得占位置，回执 N5 已经让智能体知道结果了）。
  // 只在**真的出错**时才提示 —— 拿不到项目 ID、或取令失败（后端不可用）会让智能体彻底不工作，
  // 静默失败无从察觉。恢复正常后 phase 回到 idle，提示自动消失。
  if (phase !== 'error') return null;

  return (
    <Tag color="error" style={{ marginBottom: 8, whiteSpace: 'normal', lineHeight: '16px' }}>
      智能体配置桥：{message}
    </Tag>
  );
};

export default AgentConfigBridge;
