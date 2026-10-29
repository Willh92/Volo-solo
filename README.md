# Volo

Volo 是从 [Bolo（bolo-blog/bolo-solo）](https://github.com/bolo-blog/bolo-solo) 分支开发的 Java 博客系统。Bolo 基于 [Solo](https://github.com/88250/solo) 深度定制；本项目保留原作者、贡献者的版权及许可证声明。

项目仓库：[Willh92/Volo-solo](https://github.com/Willh92/Volo-solo)。当前版本 **3.0.0**。

Volo 面向个人博客，支持 Markdown 写作、文章与页面管理、分类、标签、评论、主题切换及数据备份。服务端使用 **Java 17 + Latke 3.5.1 + Netty 4.2.17.Final**，以独立 ZIP 运行，不需要外置 Servlet 容器。

## 功能

### 写作与站点管理

- 内置 **Vditor 4.0.1**，支持所见即所得、即时渲染和分屏预览；脚本、样式、语言包及渲染资源从本站加载。
- 文章发布、草稿、分类、标签、归档、站内搜索和评论；支持自定义导航、友情链接、关注站点和联系方式。
- 提供 JSON、SQL、Hexo 导出与备份导入；可选 GitHub 公开文章备份、社区同步、邮件及 Server 酱提醒。
- 本地或自定义图床、公告、自定义页脚及站点统计。外部服务需自行配置，部署说明见 [UPGRADE.md](UPGRADE.md)。

### 外观与阅读

- **Volo Studio** 是新安装的默认皮肤，与登录页、后台共用六种可选强调色；已有站点可在后台手动切换。
- 共提供 **16 款皮肤**，保留各自的布局、装饰和交互特色，并适配桌面、手机、浅色与深色界面。
- 外观支持按时间自动、跟随系统、固定浅色及固定深色。默认按访问者本地时间切换：**07:00–19:00 浅色，其余时间深色**；手动选择保存在浏览器中。
- 皮肤接入 PJAX 内部导航；文章目录支持章节定位、折叠和长目录内部滚动，手机端使用目录图标。无章节标题时不显示空目录。
- Volo Studio 使用左侧封面预览，优先显示正文第一张图片，无图时使用简洁插画；卡片空白区域也可进入文章。搜索位于标题栏，联系方式位于页脚。
- 默认皮肤优先显示博主头像，缺失或加载失败时回退到 Volo 标识；公告在桌面与手机端均置于文章列表前，未配置时隐藏。列表底部按后台“每页文章数”显示分页。
- 默认皮肤接入热门文章、动态评论页和友情链接图标；未配置或无内容的可选模块不保留空块。皮肤预览仅随链接中的 `skin` 参数生效，直接访问普通网址恢复站点配置。
- 看板娘的脚本、样式、**26 套模型**及换装贴图随包提供，支持模型选择、本地提示语和切页清理；资源来源与原作者说明保留。

### 评论与访客管理

- 皮肤原有的评论头像在缺失或加载失败时，使用昵称文字与固定配色生成本地头像；已有头像继续保留。
- 新访客不创建登录账号；昵称、主页、通知邮箱和随机身份凭证保存在当前浏览器，后续可直接回复。邮箱只供管理员查看，不在公开页面展示。
- 评论按两层展示，回复子评论通过 @ 昵称标明对象。访客编辑、删除自己的评论时，必须同时匹配浏览器身份凭证和原始 IP；身份丢失或 IP 改变后不能修改旧评论。管理员可以编辑所有评论。
- 默认删除只移除本条内容并保留回复；管理员可选择删除整个讨论。历史访客账号可编辑或删除，删除账号不会删除其历史评论，管理员账号不可删除或降级。
- 普通访客在前台看到打码 IP 和离线 IP 库估计的省市信息；管理员登录后，可在各皮肤评论中查看访客的完整 IP 和位置，并从其后方直接封禁。后台保留原有封禁操作，支持单 IP 及 IPv4/IPv6 CIDR。管理员评论不保存 IP 或位置。
- IP 封禁列表支持搜索和分页；地区访问策略可拒绝或仅允许指定国家、省、市，同时支持 ip2region 与 MMDB，并单独设置无法识别位置时的处理方式。
- 离线定位支持内置 ip2region 及本地 GeoLite2/GeoIP2 城市 MMDB，优先中文名称。`local.properties` 的 `geoipDatabaseDir` 可指向已有 Nginx GeoIP 目录，配置及数据替换后重启；不请求外部定位服务。

### 登录与安全

- 登录使用 AJAX，错误密码原地提示，成功后通过 PJAX 进入后台；按来源地址与账号限制重试。
- 匿名评论限制提交间隔、同源频率、单篇文章和全站总量，并拦截短时间重复内容；管理员登录后可正常回复。
- 评论敏感词使用 [houbb/sensitive-word](https://github.com/houbb/sensitive-word) 0.29.5 的本地 DFA 匹配。后台“偏好设定 → 博客安全”可开关内置词库、沿用自定义过滤词并配置白名单；发表与访客编辑使用同一规则，管理员可编辑原文。回复对象由服务端核实，不接受客户端指定被回复昵称。
- Nginx 反代地址只从明确配置的可信代理读取，登录、评论限流和 WAF 使用同一套客户端 IP 规则。
- 前台不显示管理员入口；管理路径可配置。被拦截的管理页面直接返回 403，错误页面保留可取消的首页倒计时。
- 旧版 Bolo 体验计划已停用，不再弹出邀请或自动上传访问统计、错误日志。
- 限制上传类型、文件大小和图片尺寸；保护配置与模板文件，校验管理写请求来源，并修复解压、YAML 与动态内容输出中的安全问题。
- 关于页异步读取本项目 GitHub 仓库及正式 Release 信息；请求失败时保留本地版本信息，不使用站点配置的 GitHub PAT。

## 安装与升级

运行需要 Java 17+ 和可用的数据库。包内包含 MySQL、MariaDB 和 H2 驱动；已有 H2 1.4 数据需要单独迁移，不能直接使用新版驱动打开。完整步骤见 [UPGRADE.md](UPGRADE.md)。

1. 升级前备份数据库、旧部署目录、本地图床和自定义主题。
2. 将独立 ZIP 解压到新的空目录，避免旧依赖 JAR 残留。
3. 编辑 `WEB-INF/classes/local.properties` 和 `WEB-INF/classes/latke.properties`。升级时保留原数据库、表前缀、域名及其他部署配置，按升级说明合并新增键。
4. 在部署目录执行 `sh start.sh --listen_port 8080`（Linux）或 `start.cmd --listen_port 8080`（Windows）。脚本优先使用 `JAVA_HOME`，启动类仍为 `org.b3log.solo.Starter`。
5. 默认通过 `http://localhost:8080/start` 初始化或登录；若已设置自定义管理路径，使用对应的登录地址。

### MariaDB

`local.properties` 只需一份。MariaDB 使用包内驱动，SQL 方言仍为 `MYSQL`；替换示例中的连接信息并保留原账号和表前缀：

```properties
runtimeDatabase=MYSQL
jdbc.driver=org.mariadb.jdbc.Driver
jdbc.URL=jdbc:mariadb://localhost:3306/solo?allowLocalInfile=false
```

### Nginx HTTPS 443 → Java HTTP 8080

在 `latke.properties` 中填写公网地址，Java 仍用 `--listen_port 8080` 启动：

```properties
serverScheme=https
serverHost=example.com
serverPort=443
```

静态资源默认继承这些地址；单独配置了 `staticServer*` 时也要同步调整。`serverPort` 用于生成浏览器地址，**不控制 Java 监听端口**。

在 `local.properties` 中列出实际的 Nginx 连接来源，例如 `loginTrustedProxies=127.0.0.1,::1`，并让 Nginx 覆盖 `X-Real-IP`：`proxy_set_header X-Real-IP $remote_addr;`。CDN、多层代理及完整配置见 [反向代理说明](UPGRADE.md#nginx-https)。

同机代理可选用 Unix socket 替代 TCP：设置 `listenSocket=/dev/shm/volo/volo.sock`、`loginTrustedProxies=unix`，启动时不再传 `--listen_port`。包内 `volo-unix.init.example` 自动创建运行目录，保留服务启停、重启及状态查询；Nginx 配置见 `nginx-unix.conf.example`。公网地址仍为 HTTPS 443，权限和迁移步骤见 [Unix socket 说明](UPGRADE.md#linux-unix-socket-上游)。

### 管理入口与常用设置

`local.properties` 中的 `adminPath` 留空或缺失时，保留 `/start` 与 `/admin-index.do`。设置 `adminPath=/private-panel` 并重启后，登录页为 `/private-panel/login`，面板为 `/private-panel`，原管理页面入口关闭。路径配置不替代登录验证。

| 后台设置 | Volo Studio 中的展示位置 |
| --- | --- |
| 导航管理 | 标题栏导航；手机端通过“更多”展开自定义链接 |
| 联系方式 | 页脚图标区；未填写的项目不显示 |
| 链接管理、我的关注 | “链接与订阅”及对应列表页 |
| 公告 | 首页文章列表前；未配置时隐藏 |
| 热门文章 | 首页侧栏；无内容时隐藏 |
| 评论动态 | 动态评论页；默认首页不展示最新评论 |
| 自定义页脚、统计 | 页脚 |
| 强调色 | 登录页、后台与 Volo Studio |

Java 包名与数据库表前缀继续兼容旧站点。对外版本为 **3.0.0**，内部数据库升级版本为 **2026.11.01**。旧版 Solo/Bolo 的升级链保留，已有 4.3.4 数据库在首次启动时自动扩展评论字段，重复启动不会重复添加。

## 本地构建

需要 JDK 17+、Maven、Node.js 20.19+ 和 Yarn 1.22.22。

```sh
yarn install --frozen-lockfile --ignore-scripts --non-interactive
yarn build
node scripts/fetch-geoip.mjs
yarn verify:appearance
yarn verify:admin
yarn verify:navigation
yarn verify:kanbanniang
node scripts/verify-editor-runtime.mjs
node scripts/verify-comments.mjs
node scripts/verify-recommendations.mjs
node scripts/verify-skin-controls.mjs
node scripts/verify-utility-pages.mjs
mvn -Pci -Dlicense.skip=true verify
```

独立运行包位于 `target/bolo-netty.zip`，该内部文件名为兼容已有构建流程保留。必须先构建前端，才能把固定版本的编辑器、字体及公共库打入包中。也可通过项目的 `Dockerfile` 构建 Java 17 运行镜像。

测试覆盖鉴权、重试与评论限流、代理地址、上传与解压、迁移安全、文章筛选及前端生命周期。CI 在 Java 17、21 上构建并运行检查。

依赖审计脚本为 `scripts/audit-dependencies.mjs`。

## 来源与许可证

- 本项目：[Volo-solo](https://github.com/Willh92/Volo-solo)
- 直接上游：[Bolo](https://github.com/bolo-blog/bolo-solo)
- 原始基础：[Solo](https://github.com/88250/solo)、[Latke](https://github.com/88250/latke)

本项目遵循 [GNU Affero General Public License v3](LICENSE)。原有版权、作者记录与第三方许可证继续保留；第三方资源的使用同时受各自许可证约束。
