import { implementWidget } from '@antv/li-sdk';
import component from './Component';
import registerForm from './registerForm';
import { ICON } from './constants';

export default implementWidget({
  version: 'v0.1',
  metadata: {
    name: 'SearchControl',
    displayName: '搜索定位',
    description: '搜索图层字段并定位到对应要素',
    type: 'Auto',
    category: 'MapControl',
    icon: ICON,
  },
  defaultProperties: {
    position: 'lefttop',
    label: '搜索定位',
    layerId: '',
    datasetId: '',
    searchField: '',
    zoomLevel: 11,
  },
  component,
  registerForm,
});
