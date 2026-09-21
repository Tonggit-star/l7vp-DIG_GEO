/**
 * 图标图层 / 聚合图层的「图标转向」运行时支持。
 *
 * 面板里只存一个**字段名**（`visConfig.iconRotationField`，纯 JSON、可持久化），真正的角度换算
 * 放在渲染前做，原因有二：
 *
 *  1. 归一化必须用函数，而项目配置是 JSON，函数存不进 `LAYERS.VIS_CONFIG`；
 *  2. 原始角度不能直接喂给 L7（见 normalizeRotation 的说明）。
 *
 * 角度方向：L7 点图层的 rotation 走 shader 的 `rotate_matrix(a)`（`a / 180 * PI`），正角度在屏幕上
 * 表现为**顺时针**，与船艏向「从正北起顺时针」的定义同构 —— 船头朝北的图标，rotation 就是航向本身，
 * 不需要任何减法。若日后换了素材导致方向反了，改 `HEADING_SIGN` 即可，不必动换算逻辑。
 */

/** 归一化后写入数据行的角度字段名（双下划线前缀，避免与业务列冲突） */
export const ROTATION_DATA_FIELD = '__iconRotation';

/**
 * 素材修正：图标本身「朝正北」时为 0。
 * 若换了一套朝东/朝南的素材，改这里（朝东 = 270，朝南 = 180，朝西 = 90）。
 */
const ARTWORK_OFFSET = 0;

/** 方向修正：正角度在屏幕上为顺时针（与船艏向同向），故为 +1；万一日后实测转反了改成 -1。 */
const HEADING_SIGN = 1;

/**
 * 把航向字段的原始值归一化成 L7 可直接消费的旋转角度（度）。
 *
 * 不能直接透传原始值，因为：
 *  - 本项目的船舶数据实测 12.4% 的行 `heading = 360`，且其中 97.7% 船速 ≈ 0，该值是上游「艏向
 *    不可用」的哨兵值；直接当角度用会让这批点全部渲染成朝正北的假信号；
 *  - L7 的 attribute update 是 `const { rotation = 0 } = feature`，默认值**只对 undefined 生效、
 *    对 null 不生效**，null 会原样写进 Float32 缓冲。
 *
 * 故统一收敛：非数值 / 越界 / 360 一律回落到 0（朝正北）。
 */
export const normalizeRotation = (raw: unknown, artworkOffset = ARTWORK_OFFSET, headingSign = HEADING_SIGN): number => {
  const deg = typeof raw === 'number' ? raw : Number(raw);
  if (!Number.isFinite(deg)) return 0;
  if (deg < 0 || deg > 360) return 0;
  if (deg === 360) return 0; // 哨兵值 ≡ 正北

  const adjusted = deg * headingSign + artworkOffset;
  return ((adjusted % 360) + 360) % 360;
};

/**
 * 给 source 的每行补一个归一化后的角度列 `__iconRotation`。
 * 没有配 rotationField、或 source 没有数据行时原样返回（不产生新对象，避免多余的 L7 setData）。
 */
export const rotateSourceData = (source: any, rotationField?: string): any => {
  if (!source || !rotationField) return source;

  const data = source?.data;
  if (!Array.isArray(data) || !data.length) return source;

  return {
    ...source,
    data: data.map((row: Record<string, any>) =>
      row && typeof row === 'object' ? { ...row, [ROTATION_DATA_FIELD]: normalizeRotation(row[rotationField]) } : row,
    ),
  };
};

/**
 * 把 iconStyle 收敛成 L7 可消费的形式。
 *
 * `rotation: { field }` **刻意不传 value** —— L7 的 FeatureScalePlugin 在 `values === undefined` 时
 * 会选 `ScaleTypes.IDENTITY`（identityScale 的 `scale(x) => x == null ? unknown : x`），即原样透传，
 * 正是我们要的「数据里是什么角度就转多少度」。
 */
export const rotateIconStyle = (iconStyle: any, rotationField?: string): any => {
  if (!rotationField) return iconStyle;
  return { ...(iconStyle || {}), rotation: { field: ROTATION_DATA_FIELD } };
};

/** 一步到位：同时处理 source 与 iconStyle，供两个图层组件直接展开到 <IconImageLayer> 上 */
export const applyIconRotation = (params: {
  source?: any;
  iconStyle?: any;
  rotationField?: string;
}): { source?: any; iconStyle?: any } => {
  const { source, iconStyle, rotationField } = params;
  return {
    source: rotateSourceData(source, rotationField),
    iconStyle: rotateIconStyle(iconStyle, rotationField),
  };
};
