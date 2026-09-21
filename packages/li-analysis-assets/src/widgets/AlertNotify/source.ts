import type { AlertItem } from './types';

/** 收到一条新告警 */
export type AlertSubscriber = (item: AlertItem) => void;

/**
 * 告警数据源：调用后开始推送，返回**退订函数**（组件卸载时调用，必须清干净定时器/连接）。
 *
 * 本期只实现 mock（mockAlerts.ts）。以后接真实数据时新增一个同签名的工厂即可，
 * 面板与队列逻辑都不用动，因为 `AlertItem` 就是契约：
 *
 * - **HTTP 轮询**（推荐先做）：后端加 `GET /api/projects/{pid}/alerts?since=<ms>&limit=`，
 *   前端 `createHttpSource({ url, intervalMs })` 定时拉增量，按 `since` 去重；
 *   形态与现有 `/api/projects/*`、`/api/zhongtai/*` 一致，改动最小。
 * - **WS 推送**：复用已有的 3002 端口，新增端点 `/ws/alerts/{projectId}`（或在
 *   StreamWebSocketHandler 里加一种消息类型），收到即 `onItem(row)`。
 *
 * 两种都只改这个文件与后端，`AlertNotify/Component.tsx` 一行不用改。
 */
export type AlertSource = (onItem: AlertSubscriber) => () => void;
