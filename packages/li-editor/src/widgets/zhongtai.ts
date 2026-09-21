/**
 * 中台集成公共工具
 *
 * space_id 获取优先级（中台编配嵌入时传入）：
 *   1) URL query（中台把 space_id 拼进被嵌入地址，最可靠）
 *   2) hash 里携带的 query（部分 hash 路由场景兜底）
 *   3) 浏览器 localStorage（同源时中台会把当前空间写入，跨域读不到）
 *
 * 中台服务基地址：读 window.L7VP_CONFIG.zhongtaiBaseUrl（内网部署直接改 public/config.js，
 * 无需重新构建）。未配置时返回空字符串，调用方须提示配置缺失，不再兜底到硬编码地址。
 */

const SPACE_ID_KEYS = ['daas_space_id', 'space_id', 'spaceId'];

function pickParam(params: URLSearchParams): string | undefined {
  for (const key of SPACE_ID_KEYS) {
    const v = params.get(key);
    if (v) return v;
  }
  return undefined;
}

/** 读取当前中台空间 ID（URL → hash → localStorage），都没有返回 undefined */
export function getZhongtaiSpaceId(): string | undefined {
  if (typeof window === 'undefined') return undefined;

  // 1) URL query
  const fromSearch = pickParam(new URLSearchParams(window.location.search));
  if (fromSearch) return fromSearch;

  // 2) hash 里携带的 query（如 http://host/#/a?daas_space_id=xxx）
  const hash = window.location.hash;
  const qi = hash.indexOf('?');
  if (qi >= 0) {
    const fromHash = pickParam(new URLSearchParams(hash.slice(qi + 1)));
    if (fromHash) return fromHash;
  }

  // 3) localStorage（同源场景兜底）
  for (const key of SPACE_ID_KEYS) {
    try {
      const v = window.localStorage.getItem(key);
      if (v) return v;
    } catch (e) {
      /* ignore */
    }
  }

  return undefined;
}

/** 读取中台服务基地址（含端口，结尾无斜杠）。未配置时返回空字符串，由调用方提示配置缺失。 */
export function getZhongtaiBaseUrl(): string {
  if (typeof window === 'undefined') return '';
  const runtime = (window as any)?.L7VP_CONFIG?.zhongtaiBaseUrl;
  if (runtime) return runtime;
  return '';
}
