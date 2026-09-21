几个改造需求：

1. 库号加代号
   - 目前”图标类型“中的”基于字段“仍然是指定一个字段，而按照我的需求，是由图标库的library code和codename这两个字段来锁定到某个图标。
   - 目前上传图标的时候会自动生成一个library code，使用的是uuid，我觉得默认值就按现有最大值在后面递增就行，库号library code不用递增，随便选一个现有的就行，代号codename递增。
   - 当前如果手动修改库号代号的时候，出现填入的库号代号与现有库号代号有重复的时候，页面会报错，增加一个弹窗提示。
2. 内置经纬度转换
   - 我们这个行业通常有两种经纬度格式，一种是“点度”也就是现在使用的十进制；另一种是“度分秒”，也就是60进制拼接在一起的字符串例如：120度10分59.99秒，那么在数据库里面的存储的就是120.595999。我需要你内置转换算法，并且在所有配置坐标字段的地方，在”类型“中，把当前的“经纬度”改名为“点度”，另外增加一个“度分秒”选项，然后把geometry去掉吧，我应该用不到（你可以给我介绍一下geometry是用来干什么的）。
3. API调用（针对中台）
   - 系统部署进入内网后，需要调用数据中台发布的API接口，F:\code\Java\L7VP\doc\数据中台路径下面有个SimpleDaasDmsService.java请你参考这个文档，把数据中台用户名密码做成配置文件，你可以默认写成用户名：sjzt_admin，密码：sjzt_admin@4321
   - 现在新增数据集的时候有个http请求，请你参考这个功能，新增一类数据集选项，就叫”中台API“，配置信息应该和“http请求”差不多，只不过需要你在后台先获取中台系统的token才能把token传进去获取到数据。这也是为什么现在的“http请求”我没法用，因为我需要token。
4. 分析布局的这个侧边栏，有没有可能做成透明的？让我还能看到下面的地图，并且增加一个透明度的控制条。这个需求不是特别强烈，你评估一下可行性在与我讨论
5. 访问8000端口直接跳转到8000/project页面，不希望看到现在8000端口那个页面。
6. 增加数据库类数据源
   - 还有一个比较大的需求改动，那就是增加一个mysql驱动来连接外部doris数据库，读取数据并上图。
   - 首先我需要在project页面左上角再增加一个中台数据库配置的按钮，弹窗，在弹窗中配置数据中台的ip端口用户名密码、模式名等信息，并能够测试连接。这里配置的信息是schema级粒度的。
   - 然后在build页面里面新增数据集的时候，还要再新增一类数据集，就叫做“中台数据库”，在这里面需要一个下拉框选择数据库模式，也就是project页面配置好的，然后再一个下拉框选择数据表。
   - 既然这个功能做了，那最好不止支持连接mysql的数据库，针对达梦数据库也实现一下，逻辑是一致的。需要在project页面的配置弹窗中增加一个选项，选择数据库类型
   - 这类数据源很显然数据不用存入我们的dig_geo中，只需要存储配置信息。
   - 是不是需要有个地方设置更新周期啊，每隔多长时间重新获取一次数据并刷新图上显示。
   - 我把doris驱动和mysql驱动放在F:\code\Java\L7VP\deploy\backend\lib下了，这两个驱动应该都能连接中台的doris数据库。
7. 由于上述功能改造需要新建一些数据库表，再单独写一个数据库文件吧，把这次更新需要创建的表sql给我，我来执行创建。

先不要直接开发，我需要你以对话的形式与我确认需求，最终生成一个新的改造需求设计文档，直到没有任何疑问，不要自己猜测。











测试发现问题：

1. 跳转不行，浏览器输入http://localhost:8000/并没有直接跳转至project页面。

2. 数据库配置那里的样式有点小问题，图标库维护的弹窗也是一样的问题，就是左边这一栏，选中之后，背景色就变成几乎白色的了，但字也还是白色，就啥都看不清了，要不把选中后 的字体颜色改为黑色。

3. 预览数据的按钮点了之后一闪而过就消失了，看不到数据。

4. 新增数据集的时候，选择数据表的下拉框应该也同时支持模糊搜索，不然表太多了就不好找了。

5. 新增好的数据集下面显示的数据行数是0，感觉没有获取到数据。

6. 度分秒也是两个配置项，经度一个下拉框，纬度一个下拉框！！只是这两个字段都是度分秒格式的字符串而已。

7. 侧边栏透明度的滑动条，没有显示出来，看不到。你是不是可以参考图标图层控制图标透明度的按钮来做。

8. 目前侧边栏默认好像也有点问题，现在底色变成纯白了，与之前也不一样，我需要默认还是之前那种颜色。然后现在也不是透明的。

9. 基于库号和代号的字段选择的时候，我选不到我想用的字段，因为我的数据中那两个字段里面都是数字，被识别为数值字段，而这里被限制只能选择文本字段了！！

   有什么疑问，你可以先问我，再修改问题。





​	1.数据库查询失败: 第1 行附近出现错误: 无效的表或视图名[TEST_GEO] 点击预览数据报错，你是不是sql前面没加模式名啊？而且注意一下大小写.

2.使用过程中报错。

Error: Invalid LngLat latitude value: must be between -90 and 90

▶ 9 stack frames were collapsed.

AppService.fitMapBounds

.@antv/packages/li-editor/dist/esm/services/app-service.js:307

`  304 |   //   [bounds[1][0] + pading, bounds[1][1] + pading],  305 |   // ];  306 | > 307 |   sceneInstance === null || sceneInstance === void 0 || sceneInstance.fitBounds(bounds);      | ^  308 | }  309 |   310 | /**`View compiled

AppService.syncMapBounds

.@antv/packages/li-editor/dist/esm/services/app-service.js:329

`  326 |       });  327 |       return;  328 |     }> 329 |     this.fitMapBounds(bounds);      | ^  330 |   }  331 | }]);  332 | return AppService;`View compiled

(anonymous function)

.@antv/packages/li-editor/dist/esm/services/editor-service.js:59

```
  56 |   if (bounds) {  57 |     // 放到下一帧，图层加载到地图上渲染耗时，影响 fitBounds 流程度  58 |     requestIdleCallback(function () {> 59 |       _this.appService.syncMapBounds(bounds);     | ^  60 |     });  61 |   }  62 | });
```

3.度分秒格式的数据上图失败，没有显示出来图标。在我配置好经纬度后，只有一个接口被调用了，且没有报错。Request URL

http://localhost:8000/api/projects/44ca4250-651c-47bd-93b3-6d766a51e503

Request Method

PUT

Status Code

200 OK

Remote Address

[::1]: 8000

Referrer Policy

strict-origin-when-cross-origin









1.我配置了一个点度格式的达梦数据集，但是一点击“添加”按钮立即报错：数据集"asd"请求失败 非法度分秒值: 分或秒不能 >= 60。实际上我这个数据也不一定是度分秒格式啊。然后不知道是不是因为这个，下面条数显示的是0行。

还会出现Error: Invalid LngLat latitude value: must be between -90 and 90这个报错

然后度分秒格式的数据上图还是不行。唯一的那一个接口也没报错。

2.我选择小写表名的时候，还是报错了，可能是达梦自动转成了大写，但是我这个实例是大小写敏感的，你是不是应该在表名外面套个双引号，避免表名大小写错误。





1. 用库号代号锁定图标后，没有生效，什么都没上图。
2. 达梦数据库的数据集配置好了之后，正常看到数据了，但是返回project页面再进来，又显示为0并且看不到和图层经纬度绑定的字段了，字段下拉框里面没东西。
3. 我一开始选中了点度，但切换至度分秒被选中了之后，图标位置没变，并且我已经选中了度分秒，但是退出到project页面，再进来，又切回了点度，是不是保存没生效？





三个问题的根因分析：

**问题2** — 我等了半天下拉框还是为空。console里面有报错：

Warning: [antd: Spin] `tip` only work in nest or fullscreen pattern.

数据集查询服务 undefined 未在资产中.

Warning: findDOMNode is deprecated and will be removed in the next major release. Instead, add a ref directly to the element you want to reference. Learn more about using refs safely here: https://reactjs.org/link/strict-mode-find-node

[li-sdk]: 服务 undefined 未注册成功

Uncaught (in promise) Error: Invalid LngLat latitude value: must be between -90 and 90



**问题3 我是点击页面左下角菜单栏的返回的，等了好几秒再退出的。

切换至度分秒的时候报错【li-sdk]: 服务 undefined 未注册成功.



1.达梦数据库还是只有第一次进来的时候能正常接收到，保存后再进来就没了，显示0条，并且等很久都没有变化，感觉没有在获取这个远程数据的信息。

2.基于字段确定图标依然不生效，index.js:126 [li-sdk]: 服务 undefined 未注册成功.

3.经纬度的那个选项，选了之后再进来依然切回了点度，你到底保没保存！！！并且点击切换过去位置也不对，还是按照点度在渲染！！！







{
    "iconAtlas": {
        "/icons/962fadf3-da8d-4efc-bfbe-447b93f4b7a9/1f90f126-33ed-4b52-a65c-87b93d68f49f.jpeg": "/icons/962fadf3-da8d-4efc-bfbe-447b93f4b7a9/1f90f126-33ed-4b52-a65c-87b93d68f49f.jpeg"
    },
    "icon": "/icons/962fadf3-da8d-4efc-bfbe-447b93f4b7a9/1f90f126-33ed-4b52-a65c-87b93d68f49f.jpeg",
    "iconType": "fixed",
    "radius": 20,
    "iconStyle": {
        "opacity": 1
    },
    "label": {
        "visible": false,
        "style": {
            "fill": "blue",
            "fontSize": 12,
            "textAnchor": "center",
            "textOffset": [
                0,
                0
            ],
            "stroke": "#606060",
            "strokeWidth": 0.5
        }
    },
    "minZoom": 0,
    "maxZoom": 24,
    "blend": "normal",
    "visible": true
}





第一个问题：当我配置了一个新的达梦数据集时，你调用了经纬度转换，但是实际上，你并不能从数据判断我这些数据到底是度分秒还是点度，因为点度也有可能都不超过60。你应该在我配置图层的坐标，点击“度分秒”的时候再做转换才对。现在还是不对，我点击“度分秒”的时候没有响应的坐标转换的信息被打印。

[database-dataset] getDatabaseData 被调用, properties: {"connectionId":"3db505b6-3521-4428-a37c-cf5343e3a6eb","tableName":"TEST_GEO"}
helper.js:16 [database-dataset] getDatabaseData 被调用, properties: {"connectionId":"3db505b6-3521-4428-a37c-cf5343e3a6eb","tableName":"TEST_GEO"}
index.js:89 update state.datasets =>  (2) [{…}, {…}]
helper.js:23 [database-dataset] fetch 响应状态: 200
geo-parser.js:226 [geo-parser] 检测到 DMS 列: COLUMN_3 原始值: 118.4755 类型: string 转换后: 118.79861111111111
geo-parser.js:226 [geo-parser] 检测到 DMS 列: COLUMN_4 原始值: 31.5808 类型: string 转换后: 31.968888888888888
geo-parser.js:230 [geo-parser] 转换映射: (2) ['COLUMN_3', 'COLUMN_4']
index.tsx:131 [Builder] onUpdate spec.widgets count= 8 spec keys= (3) ['map', 'layers', 'widgets']
index.js:98 update state.layers =>  (2) [{…}, {…}]
index.tsx:131 [Builder] onUpdate spec.widgets count= 8 spec keys= (3) ['map', 'layers', 'widgets']
index.js:98 update state.layers =>  (2) [{…}, {…}]

第二个问题：达梦数据库数据集在我第二次进入的时候仍然变成了0条，虽然关联图层还在，但是图层中配置来源字段的地方已经下拉框中是空的了。

重新进入这个project的时候，只打印了：

数据集查询服务 undefined 未在资产中.

[li-sdk]: 服务 undefined 未注册成功.

不知道是没查询到数据还是配置信息没存储下来。







1. 我点击度分秒之后：update state.layers =>  (2) [{…}, {…}] useLayerProps.js:137 [useLayerProps] DMS 转换: lonField= undefined latField= undefined rows= 1 index.tsx:133 [Builder] onUpdate spec.widgets count= 8 spec keys= (3) ['map', 'layers', 'widgets'] 
   位置没变化。
2. 达梦数据集第二次进入：数据集查询服务 undefined 未在资产中.[li-sdk]: 组件 AnalysisLayout 未注册成功.
   我建议你再多打印一些日志，包括保存阶段、和查询阶段，以便排查问题。
3. 出现了一个额外的问题，我现在创建新项目，进来之后地图无法正常显示了，提示组件 AnalysisLayout 未注册成功.点击瓦片图层的隐藏，再显示，并拖动地图后恢复正常显示出来地图了。



1. 度分秒数据已经可以正常上图了，库号代号锁定图标也是正常的，enrichIconUrls等日志可以先关掉了。现在需要全力排查达梦数据集消失的问题。
2. 现象没有改变，仍然是第二次进来，数据就变为0条。我有个疑问，我们只在DB_CONNECTION中存储了schema粒度的连接信息，那数据集信息呢？好像成功存储在了datasets表中。那么第二次进入project的时候是不是应该去查询这个数据表的元数据？包括字段名、数据量等等？感觉完全没有查询。







我租用了一套华为云服务器，centoos7.6，鲲鹏架构，为了模拟我内网的飞腾芯片麒麟V10系统的环境。ip是1.94.200.29，密码是Labuse123123。我现在需要你把最新的前后端，打包上传至云服务器，并把他打包成镜像。另外我有个疑问，不上传可以在我本地windows上打包出来一个arm架构能用的镜像吗？能的话不上传也行。请你一定要注意，千万不要落下任何东西！！！不然我进了内网部署，很麻烦，缺东西我就还要再刻一张盘。现在你还有什么疑问吗？









我已经导入内网部署了，有一些小问题。 

1. 上传excel数据集保存的时候F12查看到upload接口报错，状态码500，我看到里面出现了localhost：3001的字眼，是不是有什么地方IP写死成localhost了？
2. 我配置达梦数据源和doris数据源的时候，测试连接时正常的，但是保存异常，我忘了具体的报错了，你先试着排查一下。

你先尝试排查一下，如果看不出来为什么，告诉我，我下午进入内网仔细记录。先不要修改代码。





还有一个问题，信息框功能（鼠标悬浮在图标上面时，会有一个小窗，显示更多数据）和显示“标签字段”的功能在多少版本以上的chrome才能支持？因为我内网客户端系统是win7的，chrome是107版本的（好像是），我可能需要更新浏览器才能使用这种功能，回答我的问题，不要动代码。





### 问题1：Excel 上传 500 → `localhost:3001` 硬编码

**根因**：[packages/li-editor/src/widgets/UploadDataset/UploadDataset/index.tsx:48](vscode-webview://12sn7sq75vnhqeub9k8freb9m3jhek85t0mi1t63e557jdmk1k8e/packages/li-editor/src/widgets/UploadDataset/UploadDataset/index.tsx#L48)



```typescript
const backendOrigin = 'http://localhost:3001';
const url = backendOrigin + '/api/projects/' + projectId + '/datasets/upload';
```

这行代码绕过了 nginx 代理，直接向 `localhost:3001` 发请求。注释说原因是"proxy 对大体积 POST body 会丢失 Content-Type"，但这只在本地开发环境有效。部署到 Docker 后，浏览器中的 `localhost:3001` 指向用户自己的电脑，而不是服务器，所以请求失败（500）。

**修复方向**：改为相对路径 `/api/projects/{id}/datasets/upload`，走 nginx 代理。需要同时确保 nginx 的 `proxy_request_buffering` 和 `client_max_body_size` 配置足够大以支持大数据上传。



关于这个问题，你现在可以修复一下了。最终我需要你制作好新的docker镜像（如果需要），1.94.200.29这台centos7.6云服务器上有docker，代码修改之后你自己上传，重新打包镜像，对nginx参数有要求你就直接修改。密码还是Labuse123123。



使用F:\programfiles\anaconda\envs\kag_demo路径的python环境，写一个生成随机生成坐标数据的脚本。数据写入达梦数据库，我本地，端口5236，用户名密码都是SYSDBA，你可以在HJJTS这个模式中随便建表，我需要包含名称，经度，纬度字段。我要用这些数据测试一个地理可视化的软件。我需要你预留一个数据量的输入变量，我需要压力测试地理可视化软件能顶住多少数据。每次执行脚本可以建一张新表在里面，表名你定。达梦驱动你用pip下载吧。



又发现一些问题：

1. 批量上传图标时，你好像只递增了一个数，只能成功上传第一个。剩下都会提示上传失败。
2. icon和封面截图的文件夹是不是要挂在容器外面啊，不然我每次更新镜像包的时候就把之前上传的文件都顶掉了。





问题清单：

1. “选中某字段用于临时配置值和图标的映射”这个功能前期被我关掉了，现在虽然按钮恢复显示了，但是功能有点不对，这个下拉框获取不到数据，这里的逻辑应该是把选中的字段中的值聚合（去重）之后显示出来，再依次指定图标映射。把这个功能恢复好。主要就是iconField和iconImgScale这两个东西和相关的计算逻辑。
2. 默认瓦片配置里面填写的瓦片层级字段，好像保存异常？或者新建project的时候没有读取到默认层级。我在配置里面修改成了0-24级（虽然我的瓦片没有24级，但我希望我放大到24级的时候也不要黑屏，图片可以糊，但是不能因为超出了我设置的层级就没加载底图了），但是我再次新建项目的时候这个设置没有生效。
3. 图标图层，图标在渲染的时候，外侧有一个模糊的圆，就是这个图标在渲染的时候好像套在一个圆里面，当我选用一个长方形的图标来上图的时候，这个半透明的圆就会盖住我这个图标的四个角。我希望这个圆可以去掉，或者更简单 的方式，把他从半透明变成纯透明。
4. 关闭度分秒坐标转换的在console里面打印的日志，这个功能已经稳定了。
5. project页面右上角的“常用瓦片”这个弹窗，修改成可以维护多个常用瓦片，也就是在这个弹窗中增加一个左侧的菜单栏（跟图标库那个弹窗差不多），然后选中左侧的瓦片，右侧展示对应的详细信息。需要一个新增按钮，支持新增常用瓦片。当我新建项目的时候，在进入build页面之前，弹窗，提示让我选择我需要的瓦片，被选中的瓦片（支持多选）会被自动在这个project里面创建好数据集和图层。
6. 增加一些空值判断，避免出现：uncaught(in promise)Typeerror:Cannot read properties of null (reading 'tostring')。例如：我选中某个字段作为标签字段，如果数据集里面这一列有null，那就把他转换为空字符串、或跳过不渲染都行。“属性面板”相关的数据逻辑也是这样，避免数据质量问题影响功能的正常运行。
7. 在新建图层的时候，目前有自动匹配的逻辑，比如数据集里面有字段名字叫“经度”，“纬度”，那么这两个字段就会被自动填入经纬度的映射下拉框里面。我现在需要你再增加对‘jd’,'wd'这两个字段名称的检测和自动填入，也就是经纬度的拼音前缀。
8. 增加开发一种新的“地图控件组件”资产，功能是：选择某个数据集、某一个字段作为搜索字段，然后选中某个搜索结果后，相机跳转至这个图标的位置，同时缩放层级也放大至某个设置好的层级。这个组件的样式可以参考“数据筛选器”这个组件，需要配置的信息包括：数据集、字段、跳转后的地图缩放等级（0-24，可不填，默认填写一个11级）。





| **#1** | 图标映射     | 需要新增后端 API `GET /api/.../unique` + 前端 `x-reactions` 联动，工作量较大 |
| ------ | ------------ | ------------------------------------------------------------ |
| **#5** | 多瓦片管理   | 需要新建 DB 表 + CRUD API + 左右布局 UI，工作量很大          |
| **#8** | 搜索定位控件 | 全新 Widget 资产，需要完整组件开发                           |

1. 图标映射可以先不做，但不做的话要把那两个被我解放开显示的下拉框关掉。
2. 多瓦片管理功能要做，DIG_GEO.TILE_CONFIG这张表继续使用就行了，只不过可以往里面存更多的数据。我在这张表里面添加了“DEFAULT” 这个字段，用于标记默认瓦片，用户可以把某些瓦片标记为默认。左右布局 UI参考“图标库”这个弹窗来做就行。
3. 搜索定位控件，这个也要开发，反正是一个独立的Widget 资产，开发出bug了也不影响现有功能。