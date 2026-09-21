cd f:/code/Java/L7VP && npx lerna run build --scope=@antv/li-analysis-assets 2>&1 | tail -3 && taskkill //F //IM node.exe 2>/dev/null; sleep 2; cd website && npx umi dev --port 8000 2>&1 &
sleep 25 && curl -s -o NUL -w "FE:%{http_code}" http://localhost:8000/





1. project页面不能向下滚动，project多了之后点不到下面的了。
2. 删除项目的时候需要一个弹窗提示，避免用户使用的时候误删。
3. 







前期我反馈过一个问题，就是“信息框”、“属性面板”、图标显示“标签字段”等需要渲染的功能，在我的内网部署环境里面的Win7电脑107内核的谷歌浏览器上怎么都不好使，具体体现在“信息框”划入了没反应，“属性面板”点击没反应右边没有弹出详情，图标显示“标签字段”配置了也不显示（偶尔有少量数据（10+条）的能成功显示）。现在我把我开发机的谷歌浏览器版本回退至104版本（我没下载到107），发现这些功能都能正常使用，但是划入图标并成功显示“信息框”的瞬间，console提示了：Warning: ReactDOM.render is no longer supported in React 18. Use createRoot instead. Until you switch to the new API, your app will behave as if it's running React 17. Learn more: https://reactjs.org/link/switch-to-createroot


我现在又测试了一下，除了上述“信息框”、“属性面板”、图标显示“标签字段”这三个功能外，我发现还有一些东西在内网也失效了，例如

A:地图缩放器，是显示不出来的。

B:热力图在内网的有一台电脑上可以正常显示，在其他电脑（核显或独显显存较低）不行。

C:"属性面板"点不出来，感觉像是软件根本没有监听到鼠标点击事件。怎么才能通过console看出有没有监听到鼠标点击事件，可以考虑加一些日志。

然后我又抄了一些日志出来，请你排查：

1. webGl:invalid_operation:use program:program not valid
2. uncaught(in promise)Typeerror:Cannot read properties of null (reading 'tostring')
3. uncaught(in promise)Typeerror:Cannot read properties of undifined(reading 'filter')
4. assync.js:6920
5. context creating error webglContextEvent:Could not create a Webgl context, bindtoCurrentThread failed Glversion:26.21.14.4166 target :canvas Nvidia Quadro K4000

我理解，这些问题的根源无非就是数据质量有问题（有空字段）、显卡不好显存不够、我们使用的webgl库或接口在这些win7上面存在不兼容的情况。你再分析分析，然后告诉我分析结果，先不修改代码。