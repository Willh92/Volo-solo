# Volo

Volo 是从 [Bolo（bolo-blog/bolo-solo）](https://github.com/bolo-blog/bolo-solo) 分支开发的 Java 博客系统。Bolo 基于 [Solo](https://github.com/88250/solo) 深度定制；本项目保留原作者、贡献者的版权及许可证声明。

项目仓库：[Willh92/Volo-solo](https://github.com/Willh92/Volo-solo)。当前版本 **3.0.0**。

Volo 面向个人博客，支持 Markdown 写作、文章与页面管理、分类、标签、评论、主题切换及数据备份。服务端使用 **Java 17 + Latke 3.5.1 + Netty 4.2.17.Final**，以独立 ZIP 运行，不需要外置 Servlet 容器。

## 功能

### 写作与站点管理

- 内置 **Vditor 4.0.1**，支持所见即所得、即时渲染和分屏预览；脚本、样式、语言包及渲染资源从本站加载。
- 文章发布、草稿、分类、标签、归档、站内搜索和评论；支持自定义导航、友情链接、关注站点和联系方式。
- 提供 JSON、SQL、Hexo 导出与备份导入；可选 GitHub 公开文章备份、社区同步、邮件及 Server 酱提醒。
- “我的 GitHub”支持同步公开仓库、选择展示项目、包含或排除 Fork，并生成项目文章和统计卡片。
- 本地或自定义图床、公告、自定义页脚及站点统计。外部服务需自行配置，部署说明见 [UPGRADE.md](UPGRADE.md)。

### 外观与阅读

- **Volo Studio** 是新安装的默认皮肤，与登录页、后台共用六种可选强调色；已有站点可在后台手动切换。
- 共提供 **16 款皮肤**，支持桌面、手机、浅色与深色界面。
- 外观支持按时间自动、跟随系统、固定浅色及固定深色。默认按访问者本地时间切换：**07:00–19:00 浅色，其余时间深色**；手动选择保存在浏览器中。
- 皮肤支持 PJAX 导航、可折叠文章目录、章节定位和返回顶部；可通过 `skin` 参数预览皮肤。
- Volo Studio 提供正文首图封面、公告、分页文章列表、站内搜索、联系方式和自定义导航。管理员登录后可使用后台入口、退出登录和最新评论定位。
- 可选看板娘插件提供 **26 套本地模型**及换装贴图，资源随发行包提供。

### 评论与访客管理

- 支持评论头像与昵称文字头像。
- 新访客不创建登录账号；昵称、主页、通知邮箱和随机身份凭证保存在当前浏览器，后续可直接回复。邮箱只供管理员查看，不在公开页面展示。
- 评论按两层展示，回复子评论通过 @ 昵称标明对象。访客编辑、删除自己的评论时，必须同时匹配浏览器身份凭证和原始 IP；身份丢失或 IP 改变后不能修改旧评论。管理员可以编辑所有评论。
- 默认删除只移除本条内容并保留回复；管理员可选择删除整个讨论。历史访客账号可编辑或删除，删除账号不会删除其历史评论，管理员账号不可删除或降级。
- 普通访客只看到打码 IP 和估计的省市信息；管理员可查看访客完整 IP、位置，并从评论直接封禁。管理员评论不保存 IP 或位置。
- 离线定位支持内置 ip2region 和外部 GeoLite2/GeoIP2 城市 MMDB，可配置本地库目录，优先中文名称。

### 登录与安全

- AJAX 登录、本地图片验证码、登录重试限制、评论防刷和文章访问密码限流。
- “安全”菜单提供访问防护、评论防护、地区规则和 IP 限制。支持恶意扫描与 XSS 请求识别、临时或永久封禁、期限修改、提前解封和累计次数管理。
- IP 限制支持 IPv4、IPv6 和 CIDR，提供查询与分页。地区限制支持国家、省、市范围及多条有序规则，各规则可设置启停和命中、未命中操作，并提供统一的未知位置策略。
- 评论敏感词使用 [houbb/sensitive-word](https://github.com/houbb/sensitive-word) 本地词库，可配置自定义词和白名单。游客验证码默认启用，管理员可在评论防护中调整。
- 支持可信反向代理、自定义管理路径，以及独立的前台登录入口和评论开关。未登录时默认隐藏管理入口。
- 提供上传限制、管理请求来源校验和动态内容输出保护；后台日志支持容量轮换与分页浏览。

## 安装与升级

运行需要 Java 17+ 和可用的数据库。包内包含 MySQL、MariaDB 和 H2 驱动；已有 H2 1.4 数据需要单独迁移，不能直接使用新版驱动打开。完整步骤见 [UPGRADE.md](UPGRADE.md)。

1. 升级前备份数据库、旧部署目录、本地图床和自定义主题。
2. 将独立 ZIP 解压到新的空目录，避免旧依赖 JAR 残留。
3. 编辑 `WEB-INF/classes/local.properties` 和 `WEB-INF/classes/latke.properties`。升级时保留原数据库、表前缀、域名及其他部署配置，按升级说明合并新增键。
4. 在部署目录执行 `sh start.sh --listen_port 8080`（Linux）或 `start.cmd --listen_port 8080`（Windows）。脚本优先使用 `JAVA_HOME`，启动类仍为 `org.b3log.solo.Starter`。
5. 默认通过 `http://localhost:8080/start` 初始化或登录；若已设置自定义管理路径，使用对应的登录地址。

数据库驱动、日志、HTTPS 反代、TCP 与 Unix socket 的配置示例统一见 [升级与部署说明](UPGRADE.md)。`latke.properties` 的 `serverPort` 用于生成公网 URL，Java 监听端口由 `--listen_port` 控制。

### 管理入口与常用设置

`local.properties` 中的 `adminPath` 留空或缺失时，保留 `/start` 与 `/admin-index.do`。设置 `adminPath=/private-panel` 并重启后，初始登录页为 `/private-panel/login`，面板为 `/private-panel`，原管理页面入口关闭。登录页可在“安全 → 访问防护”中单独修改，保存后立即生效，留空使用 `/start`。路径配置不替代登录验证。

| 后台设置 | Volo Studio 中的展示位置 |
| --- | --- |
| 导航管理 | 标题栏导航；手机端通过“更多”展开自定义链接 |
| 联系方式 | 页脚图标区；未填写的项目不显示 |
| 链接管理、我的关注 | “链接与订阅”及对应列表页 |
| 公告 | 首页文章列表前；未配置时隐藏 |
| 热门文章 | 首页侧栏；无内容时隐藏 |
| 最新评论 | 仅管理员登录后在首页侧栏显示，链接定位到对应评论 |
| 评论动态 | 动态评论页 |
| 自定义页脚、统计 | 页脚 |
| 强调色 | 登录页、后台与 Volo Studio |

Java 包名与数据库表前缀继续兼容旧站点。对外版本为 **3.0.0**，内部数据库升级版本为 **2026.11.01**。旧版 Solo/Bolo 的升级链保留，已有 4.3.4 数据库在首次启动时自动扩展评论字段，重复启动不会重复添加。

后台列表提供直接可见的操作按钮，手机端以卡片显示。文章、草稿、分类、导航、链接及关注支持分页查询；评论可按内容、昵称、通知邮箱或 IP 查询，通知邮箱及完整 IP 仅供管理员使用。插件管理可启用或禁用对应功能。

## 开发与构建

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
mvn -Pci verify
```

独立运行包位于 `target/bolo-netty.zip`，该内部文件名为兼容已有构建流程保留。必须先构建前端，才能把固定版本的编辑器、字体及公共库打入包中。也可通过项目的 `Dockerfile` 构建 Java 17 运行镜像。

普通 Maven 构建不会重写源文件的许可证头；需要统一头部格式时可单独执行 `mvn license:format`。

测试覆盖鉴权、重试与评论限流、代理地址、上传与解压、迁移安全、文章筛选及前端生命周期。CI 在 Java 17、21 上构建并运行检查。

依赖审计脚本为 `scripts/audit-dependencies.mjs`。

## 来源与许可证

- 本项目：[Volo-solo](https://github.com/Willh92/Volo-solo)
- 直接上游：[Bolo](https://github.com/bolo-blog/bolo-solo)
- 原始基础：[Solo](https://github.com/88250/solo)、[Latke](https://github.com/88250/latke)

本项目遵循 [GNU Affero General Public License v3](LICENSE)。原有版权、作者记录与第三方许可证继续保留；第三方资源的使用同时受各自许可证约束。
