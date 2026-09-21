import type { WidgetSchema } from '../specs';
import type { ChildrenMap, ImplementWidget, ImplementWidgetOptions } from '../types';

/**
 * 实现一个组件资产:
 * LI 资产研发教程 🔗 https://www.yuque.com/antv/l7vp/zqvk302x61qq2kcq
 */
export function implementWidget<P extends Record<string, unknown>, CP extends P = P>(
  options: ImplementWidgetOptions<P, CP>,
): ImplementWidget<P, CP> {
  const registerForm = options.registerForm ?? { schema: {} };
  const result: ImplementWidget<P, CP> = Object.assign(options, { registerForm });

  return result;
}

/**
 * 通过插槽解析组件树
 */
export function resolveChildrenMap(widgets: WidgetSchema[]) {
  const childrenMap: ChildrenMap = {};
  const topLevelWidgets: WidgetSchema[] = [];

  // Debug: find container widgets
  const containerWidgets = widgets.filter(w => w.type === 'AnalysisLayout' || w.type === 'BaseLayout');
  console.log('[resolveChildrenMap] total widgets:', widgets.length, 'containers:', containerWidgets.map(c => ({ id: c.id, type: c.type })));

  for (const w of widgets) {
    const slotContainer = w.container;
    if (!slotContainer) {
      topLevelWidgets.push(w);
      if (w.type !== 'AnalysisLayout' && w.type !== 'BaseLayout') {
        console.log('[resolveChildrenMap] widget id=%s type=%s -> TOP LEVEL (no container)', w.id, w.type);
      }
      continue;
    }
    const { id, slot } = slotContainer;
    if (!childrenMap[id]) {
      childrenMap[id] = {
        _allChildren: [],
      };
    }
    // Check if container actually exists
    const containerExists = containerWidgets.some(c => c.id === id);
    console.log('[resolveChildrenMap] widget id=%s type=%s -> container.id=%s slot=%s containerExists=%s',
      w.id, w.type, id, slot, containerExists ? 'YES' : 'NO -> ORPHAN!');
    const children = childrenMap[id];
    if (!children[slot]) {
      children[slot] = [];
    }
    children[slot].push(w);
    children._allChildren.push(w);
  }

  function getAllChildren(id: string): WidgetSchema[] {
    if (!childrenMap[id]) {
      return [];
    }
    if (childrenMap[id]?._grandChildren) {
      return childrenMap[id]._grandChildren!;
    }
    const children = childrenMap[id];

    childrenMap[id]._grandChildren = children._allChildren.reduce((res, curr) => {
      const _widgets = getAllChildren(curr.id);
      return res.concat(_widgets);
    }, children._allChildren);

    return childrenMap[id]._grandChildren!;
  }

  for (const id in childrenMap) {
    childrenMap[id]._grandChildren = getAllChildren(id);
    Object.defineProperty(childrenMap[id], '_allChildren', {
      enumerable: false,
    });
    Object.defineProperty(childrenMap[id], '_grandChildren', {
      enumerable: false,
    });
  }

  return {
    childrenMap,
    topLevelWidgets,
  };
}
