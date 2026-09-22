<img src="https://gw.alipayobjects.com/zos/antfincdn/R8sN%24GNdh6/language.svg" width="18"> English | [简体中文](./README.zh-CN.md)

<h1 align="center">L7VP</h1>

<div align="center">

🌍 L7VP （<a href="https://github.com/antvis/L7" target="_blank">L7</a> Visualization Platform）is an geospatial intelligent visual analysis and application development tools.

<p align="center">
  <a href="https://locationinsight.antv.antgroup.com" target="_blank">Website</a> •
    <a href="https://locationinsight.antv.antgroup.com/#/docs" target="_blank">Document</a> •
  <a href="https://locationinsight.antv.antgroup.com/#/docs?path=cmp1vz2u5p07ghrt" target="_blank">SDK</a> •
  <a href="https://locationinsight.antv.antgroup.com/#/case" target="_blank">Case</a>
</p>

[![SDK Version](https://badgen.net/npm/v/@antv/li-sdk)](https://npmjs.com/@antv/li-sdk) [![Release Status](https://github.com/antvis/L7VP/workflows/release/badge.svg)](https://github.com/antvis/L7VP/actions?query=workflow:release) [![Percentage of issues still open](http://isitmaintained.com/badge/open/antvis/l7vp.svg)](http://isitmaintained.com/project/antvis/l7vp 'Percentage of issues still open') [![Average time to resolve an issue](http://isitmaintained.com/badge/resolution/antvis/l7vp.svg)](http://isitmaintained.com/project/antvis/l7vp 'Average time to resolve an issue') ![License](https://flat-badgen.vercel.app/github/license/antvis/L7VP)

<div align="center">
  <img src="https://mdn.alipayobjects.com/huamei_qa8qxu/afts/img/A*EAcUQb_UzAEAAAAAAAAAAAAADmJ7AQ/original.png" width="800">
</div>

</div>

<br>

AntV [L7VP](https://locationinsight.antv.antgroup.com) is a geospatial intelligent visual analysis tool and application development tool, originally named LocationInsight. L7VP is named after AntV L7 Visualization Platform, where L represents Location and 7 represents the seven continents of the world, implying the ability to provide visual analysis for global location data.

[L7VP](https://locationinsight.antv.antgroup.com) provides users with a powerful and flexible geographic visualization analysis tool through its rich geographic visualization effects, insight analysis capabilities, map application building tools, and open expansion capabilities, meeting various visualization needs and data analysis application scenarios.

## ✨ Features

- 🚀 Fast: Possess insight into spatio-temporal data, and quickly produce visualization results.
- 🛠 Expansion: With scalability, the business can be customized.
- 🏗 Embedding: Provide open components, business systems can be easily embedded.

## 🖥 Online

- <a href="https://locationinsight.antv.antgroup.com" target="_blank">Website</a>
- <a href="https://locationinsight.antv.antgroup.com/#/docs?path=get-started" target="_blank">Get Started</a>
- <a href="https://locationinsight.antv.antgroup.com/#/docs?path=data-formats" target="_blank">Data Formats</a>
- <a href="https://locationinsight.antv.antgroup.com/#/docs?path=layer-category" target="_blank">Layer Category</a>

<div align="center">
  <img src="https://github.com/antvis/L7VP/assets/26923747/9d064849-ef9f-4999-9d98-3a704df554fc" width="800">
</div>

Learn more 👉 <a href="https://locationinsight.antv.antgroup.com/#/docs" target="_blank">User Guide</a>

## 🐍 PyL7VP

```py
from pyl7vp import L7VP

l7vp_map = L7VP(height = 600)

# add dataset to map
l7vp_map.add_dataset({"id": "my_dataset", "type": 'local', "data": df})

# display map
l7vp_map.show()
```

<div align="center">
  <img src="https://mdn.alipayobjects.com/huamei_qa8qxu/afts/img/A*MsnaS4IHywoAAAAAAAAAAAAADmJ7AQ/original" width="800">
</div>

Learn more 👉 [PyL7VP](https://github.com/antvis/L7VP/tree/master/bindings/pyl7vp)

## 🔬 Run Locally

The runnable application is `website/` (the `examples/` directory was removed from this fork).
See `CLAUDE.md` for the full development and deployment guide.

### 📦 Installation

```bash
npm install
```

### 💻 Start Up

```bash
npm run start:website    # dev server on :8000
```

> ⚠️ The dev proxy forwards `/api` to `localhost:3001`, while the backend defaults to
> `server.port=3003`. Start the backend with `--server.port=3001`, or change the proxy in
> `website/config/config.ts`, otherwise every API call will fail.

## 🔨 Develop Website

### 📦 Installation

```bash
yarn install
```

### 🛫 Start Up Website

```bash
yarn run start:website
```

## 🤝 How to Contribute

Your contributions are always welcome! Please Do have a look at the [issues](https://github.com/antvis/l7vp/issues) first.

To become a contributor, please follow our [contributing guide](https://github.com/antvis/l7vp/blob/master/CONTRIBUTING.md).

## License

[Apache-2.0](./LICENSE)
