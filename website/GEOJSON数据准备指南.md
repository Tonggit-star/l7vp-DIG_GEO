# GeoJSON 数据准备指南

## 问题说明

在断网环境下使用"中国行政"相关图层时，页面会崩溃，因为无法加载必需的 GeoJSON 边界数据文件。

## 解决方案

你需要准备以下 GeoJSON 数据文件，放在 `website/public/data/` 目录下：

| 文件名 | 说明 | 用途 |
|--------|------|------|
| china_boundary.json | 中国国界数据 | 包含海岸线、国界、港澳边界等 |
| china_province.json | 中国省级边界 | 包含各省边界和行政编码 |
| china_city.json | 中国市级边界 | 包含各市边界和行政编码 |
| china_district.json | 中国区县级边界 | 包含各区县边界和行政编码 |
| world_country.json | 世界国家边界 | 用于全球 choropleth 图层 |

## 数据要求

这些 GeoJSON 文件必须满足以下格式要求：

```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "geometry": { ... },
      "properties": {
        "name": "北京市",      // 地区名称（必须）
        "adcode": "110000",    // 行政编码（必须）
        "centroid": [lng, lat], // 中心点坐标（可选，用于点标注）
        "type": "national"     // 类型标识（china_boundary.json 专用）
      }
    }
  ]
}
```

## 获取数据的方法

### 方法一：在线下载（推荐）

如果你能访问外网，可以运行以下命令下载数据：

```powershell
cd f:\code\Java\L7VP\L7VP\website
.\download-geojson.ps1
```

### 方法二：手动下载

1. 访问以下地址下载数据文件：
   - https://npm.elemecdn.com/static-geo-atlas@0.1.0/geo-data/choropleth-data/country/100000_country_boundary.json
   - https://npm.elemecdn.com/static-geo-atlas@0.1.0/geo-data/choropleth-data/country/100000_country_province.json
   - https://npm.elemecdn.com/static-geo-atlas@0.1.0/geo-data/choropleth-data/country/100000_country_city.json
   - https://npm.elemecdn.com/static-geo-atlas@0.1.0/geo-data/choropleth-data/country/100000_country_district.json
   - https://npm.elemecdn.com/static-geo-atlas@0.1.0/geo-data/choropleth-data/world/all_world_country.json

2. 将下载的文件重命名为上述表格中的文件名

3. 将文件放入 `f:\code\Java\L7VP\L7VP\website\public\data\` 目录

### 方法三：使用开源数据

你可以从以下开源项目中获取中国行政区划 GeoJSON 数据：
- https://github.com/modood/Administrative-divisions-of-China
- https://github.com/luxiangqiang/ChinaGeoJson

下载后需要确保数据包含 `name` 和 `adcode` 字段。

## 目录结构

```
website/
├── public/
│   └── data/
│       ├── china_boundary.json    # 中国国界
│       ├── china_province.json    # 中国省界
│       ├── china_city.json        # 中国市界
│       ├── china_district.json    # 中国区县界
│       └── world_country.json     # 世界国家
└── ...
```

## 注意事项

1. **断网环境**：如果你是纯内网环境，必须通过方法二或三手动准备数据文件

2. **数据格式**：用户提供的原始 Shapefile 转换的数据可能缺少 `name` 和 `adcode` 字段，需要使用包含这些标准字段的数据

3. **文件大小**：这些 GeoJSON 文件通常较大（几MB到几十MB），请确保有足够的存储空间

4. **数据更新**：行政区划数据可能随时间变化，请使用最新版本的数据

## 测试验证

完成数据准备后，可以运行项目测试图层是否正常工作：

```powershell
yarn start:website
```

然后尝试创建一个"中国行政"图层，如果浏览器控制台没有警告信息，说明配置成功。
