# java-server/lib/ — 达梦 JDBC 驱动投放目录

这个目录**故意是空的**（只有一个 README）。构建前你必须自己往这里放一个文件：

```
java-server/lib/DmJdbcDriver8.jar
```

## 为什么必须手工放

`java-server/pom.xml` 把达梦驱动声明为 **system-scope** 依赖：

```xml
<dependency>
    <groupId>com.dameng</groupId>
    <artifactId>DmJdbcDriver8</artifactId>
    <version>8.1.1.128</version>
    <scope>system</scope>
    <systemPath>${project.basedir}/lib/DmJdbcDriver8.jar</systemPath>
</dependency>
```

system-scope 的依赖**不经过 Maven 仓库解析**——Maven 只检查这个路径上的文件是否存在。所以：

- 它**不在 Maven Central 上**，加镜像源、换私服都没用；
- 换台机器、重新 clone 之后，都得重新放一次；
- 达梦 JDBC 驱动是**商业授权文件**，本仓库又是公开仓库，所以驱动 jar **永不入库**（`.gitignore` 里用规则挡住了，见下）。

## 不放会怎样

构建**在 `compile` 之前就直接失败**，不是警告：

```
[ERROR] Failed to execute goal on project l7vp-server: Could not resolve dependencies for
        project com.antv:l7vp-server:jar:1.0.0: Could not find artifact
        com.dameng:DmJdbcDriver8:jar:8.1.1.128 at specified path
        /path/to/java-server/lib/DmJdbcDriver8.jar -> [Help 1]
[ERROR] ... DependencyResolutionException
[INFO] BUILD FAILURE
```

`-DskipTests` 和 `-Dflink.job.skip=true` **都救不了**——依赖解析发生在它们生效之前。

另外注意：编译期**没有任何 Java 源码 import 达梦的类**（唯一的引用是
`DbConnectionService.java` 里一个字符串字面量 `"dm.jdbc.driver.DmDriver"`）。
所以这个 jar 是给「构建能解析」和「运行期 JDBC 加载驱动」用的，不是给编译用的。
又因为 pom 里有 `<includeSystemScope>true</includeSystemScope>`，
驱动会被一并打进 fat jar —— 所以**运行期也不需要额外挂载它**，只需构建期存在。

## 怎么拿到

驱动随达梦 DM8 安装包提供，三条路任选：

1. **已装达梦的机器**：安装目录下的 `drivers/jdbc/` 里，通常叫 `DmJdbcDriver18.jar`
   （"18" 指 JDK 1.8+，不是数据库版本）。
2. **达梦 Docker 镜像**：镜像里就有，`docker cp` 出来即可。
3. **达梦官网**：注册后下载 DM8 安装包，从里面取。

## ⚠️ 文件名必须是 `DmJdbcDriver8.jar`

pom 认的是**路径**，不是 jar 内部标识。达梦官方发行版里的文件名多半是
`DmJdbcDriver18.jar` 或别的，**直接改名即可**：

```bash
mkdir -p java-server/lib
cp /path/to/DmJdbcDriver18.jar java-server/lib/DmJdbcDriver8.jar
```

`pom.xml` 里写的版本号 `8.1.1.128` 不会被校验——system-scope 只认文件存在。
所以任意 DM8 版本的驱动都能用。

## 放完后自检

确认拿到的是真驱动而不是空文件或损坏包：

```bash
ls -l java-server/lib/DmJdbcDriver8.jar          # 正常在 1~3 MB 量级
unzip -l java-server/lib/DmJdbcDriver8.jar | grep -i 'dm/jdbc/driver/DmDriver.class'
```

第二行有输出才算对。若构建时报 `zip END header not found`，
说明放进去的不是一个完整的 jar（占位空文件、下载中断等）。

然后构建：

```bash
cd java-server && mvn clean package -DskipTests
```

## 关于 .gitignore

仓库根 `.gitignore` 里有一条 `lib/`（本意是忽略前端 TS 构建产物目录），
它会命中**任意层级**的 `lib`，把整个 `java-server/lib/` 目录也一起忽略掉——
连这个 README 都提交不了，于是每个新人都卡在同一个构建失败上。

现已加例外规则修好：

```gitignore
lib/
!/java-server/lib/      # 先否定目录本身，git 才会往里看
/java-server/lib/*.jar  # 再单独忽略目录里的 jar
```

效果：**目录与说明入库，驱动 jar 不入库**。规则顺序不能颠倒——
只写 `!/java-server/lib/*.jar` 是无效的，因为父目录已被排除，git 根本不会往下看。

自检：

```bash
git check-ignore -v java-server/lib/DmJdbcDriver8.jar  # 应命中 /java-server/lib/*.jar
git check-ignore -v java-server/lib/README.md          # 应无输出（= 可提交）
```
