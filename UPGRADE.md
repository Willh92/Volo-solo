# Volo 3.0.0 升级与部署

运行环境为 Java 17+、Latke 3.5.1 和 Netty 4.2.17.Final。发行包独立运行，启动类为 `org.b3log.solo.Starter`，不支持部署为 Servlet WAR。对外应用版本为 3.0.0，内部数据库升级版本为 2026.11.01，保留旧版升级链。

## 升级

1. 停止服务，备份数据库、部署目录、本地图床和自定义主题。
2. 将发行包解压到新的空目录，避免旧依赖 JAR 残留。
3. 迁入 `WEB-INF/classes/local.properties`、`latke.properties` 和上传目录，保留数据库账号、表前缀、域名及主题配置，并合并新增配置键。
4. 配置数据库驱动、日志和反向代理，使用 Java 17+ 启动。
5. 检查登录、文章、搜索、编辑器、上传和数据导出。回滚时停止新版，恢复对应的程序与数据库备份。

### 数据库

MariaDB 使用 `MYSQL` SQL 方言和包内 MariaDB Connector/J 驱动：

```properties
runtimeDatabase=MYSQL
jdbc.driver=org.mariadb.jdbc.Driver
jdbc.URL=jdbc:mariadb://数据库主机:3306/数据库名?allowLocalInfile=false
```

保留原账号、密码、表前缀及适用的连接参数。远程连接启用 TLS 时应继续验证服务器证书，参见 [MariaDB 驱动文档](https://mariadb.com/docs/connectors/mariadb-connector-j/about-mariadb-connector-j)。

H2 1.4.199 数据文件不能直接使用 H2 2.5.252 打开。应先用旧驱动导出 SQL，再按 [H2 迁移说明](https://h2database.com/html/migration.html) 导入新库，并在数据副本上验证。MySQL、MariaDB 部署无需迁移到 H2。

### 日志

日志配置为 `WEB-INF/classes/log4j2.xml`，旧 `log4j.properties` 不再使用。需要文件日志时，将 `log4j2-file.xml.example` 复制为 `log4j2.xml`。文件写入 `${base.dir}/logs/solo.log`，每 50 MB 轮转，保留 5 份备份；未设置 `-Dbase.dir` 时使用部署目录。

## 启动

在部署目录执行 `sh start.sh`（Linux）或 `start.cmd`（Windows），默认监听 TCP 8080。脚本优先使用 `JAVA_HOME`，端口可通过 `--listen_port 8080` 指定。

自定义启动命令的类路径为 Linux `WEB-INF/classes:WEB-INF/lib/*:.`、Windows `WEB-INF/classes;WEB-INF/lib/*;.`；工作目录应为解压后的应用目录。

## Nginx HTTPS

在 `WEB-INF/classes/latke.properties` 中设置公网地址：

```properties
serverScheme=https
serverHost=example.com
serverPort=443
```

`serverPort` 用于生成网站 URL，不控制 Java 监听端口。静态资源默认继承公网地址；使用独立静态域名时设置对应的 `staticServer*`。启动参数 `--server_*`、`--static_server_*` 会覆盖文件配置。

### TCP 上游

在现有 HTTPS `server` 中配置：

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header scheme https;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
}
```

在 `local.properties` 中设置实际代理 IP，例如 `loginTrustedProxies=127.0.0.1,::1`。该键缺失或留空时不信任转发头。

Latke 读取 `scheme` 请求头；HTTPS 代理应固定覆盖为 `https`，或清除该头并使用 `serverScheme` 配置。`X-Forwarded-Proto` 不作为 Latke 的协议配置来源。

### Linux Unix socket 上游

Unix socket 使用 Latke 的原生 epoll 传输，启用后不开放 TCP 监听。可在 `local.properties` 中配置：

```properties
listenSocket=/dev/shm/volo/volo.sock,0660
loginTrustedProxies=unix
```

逗号后的权限为三位八进制数，可带前导 `0`。没有逗号、逗号后为空或只含空白时，默认使用 `0660`。例如 `/dev/shm/volo/volo.sock`、`/dev/shm/volo/volo.sock,` 和 `/dev/shm/volo/volo.sock,0660` 等价。非法权限或多余的逗号会阻止启动，不会悄悄回退。

执行 `sh start.sh` 启动；也可用 `--listen_socket '/dev/shm/volo/volo.sock,0660'` 指定路径和权限。不能同时传入 `--listen_port`。

SysV 服务模板为 `volo-unix.init.example`，默认 Java 用户为 `www`、部署目录为 `/usr/local/web/bolo`。按实际环境调整变量后安装：

```sh
install -m 755 volo-unix.init.example /etc/init.d/bolo
service bolo restart
service bolo status
```

模板通过 `LISTEN_SOCKET` 启动参数指定 socket，可写成 `LISTEN_SOCKET="/dev/shm/volo/volo.sock,0660"`，它优先于 `local.properties` 中的 `listenSocket`；无需重复配置。仍需设置 `loginTrustedProxies=unix`。脚本支持 `start`、`stop`、`restart`、`status`、`info`，自动创建 `0750` 运行目录。Java 按指定权限创建 socket，正常退出时清理。

服务脚本的 `SOCKET_GROUP` 应设为 Nginx worker 使用的组。例如项目文件为 `www:www`、Nginx worker 为 `nobody:nogroup` 时：

```sh
RUNNING_USER=www
SOCKET_GROUP=nogroup
```

脚本使用 util-linux `runuser`（不可用时回退到 `su`）的 `-g`、`-G` 参数保留 Java 用户原有主组和附加组，仅为该进程追加 socket 共享组，不修改系统账户或项目文件的属组。运行目录和 socket 为 `www:nogroup`；项目内新建文件仍使用主组 `www`。Nginx 用户配置无需修改。

运行目录不存在时由脚本自动创建。已有目录必须为 Java 用户所有、权限为 `0700` 或 `0750` 且不是符号链接；在应用已停止后，脚本会自动将该目录调整为配置的共享组和 `0750`。例如从 `www:www` 切换到 `www:nogroup`，更新脚本中的 `SOCKET_GROUP` 后执行：

```sh
service bolo restart
```

权限只影响 socket 文件，不改变父目录和项目的权限或属组。建议通过共享组配合 `0660`；放宽 socket 权限也不能代替父目录的执行权限。锁文件始终保持 `0600`，Nginx 不需要读取它。

路径可改为 `/run/volo/volo.sock`。运行目录在系统重启后由服务脚本重建，不能直接使用全局可写的 `/dev/shm` 根目录。

Nginx 完整上游示例见 `nginx-unix.conf.example`，核心地址为：

```nginx
proxy_pass http://unix:/dev/shm/volo/volo.sock:;
```

不指定替换 URI 时保留原始路径和查询参数，无需追加 `$request_uri`。Nginx 继续负责 HTTPS 443；修改配置后执行 `nginx -t`，通过后再 reload。

程序拒绝覆盖已有 socket、普通文件或符号链接。异常退出后若遗留 `.sock`，须先确认相关进程均已停止，再由管理员处理。`.sock.lock` 文件保留，锁随进程退出释放。切回 TCP 时清空 `listenSocket` 并移除 `--listen_socket`；服务模板将 `LISTEN_SOCKET` 设为空即可。

### 缓存与压缩

编辑器主脚本和 Lute 提供预压缩 gzip 版本，并使用 `Vary`、ETag、`Cache-Control` 协商缓存。正文、摘要及评论编辑器共享已加载的运行库。

Nginx、面板或 CDN 不应向静态响应统一追加 `Cache-Control: no-store`、`no-cache`，也无需统一清除 `Expires`。静态资源和动态页面的缓存策略由应用设置；登录与后台页面仍禁止缓存。

压缩配置参考 `nginx-cache.conf.example`。静态资源响应的缓存策略应有效且无冲突，支持 gzip 的请求应返回 `Content-Encoding: gzip`。

## 管理与访问保护

`adminPath` 留空时，登录页为 `/start`、面板为 `/admin-index.do`。设置 `adminPath=/private-panel` 并重启后，登录页为 `/private-panel/login`、面板为 `/private-panel`，原入口关闭。前台不提供管理链接。

管理路径支持多级路径，仅允许英文字母、数字、连字符和下划线，不能占用系统路由前缀。路径配置不替代身份验证。

登录、评论限流和 WAF 共用可信代理规则。`loginTrustedProxies=unix` 只信任实际启用的 Unix 监听；TCP 代理应配置准确 IP。Nginx 前还有 CDN 时，应仅对可信 CDN 还原访客 IP，再覆盖传给应用的 `X-Real-IP`。

登录限制为每个来源与账号 10 分钟内 5 次、每个来源合计 20 次；IPv6 同一 /64 共用预算。匿名评论参数位于 `local.properties`。限制在当前 Java 进程内维护，多实例部署应在入口统一限流。

## 数据与外部服务

- 本地图床应使用 Java 用户可读写的持久目录；更换路径前备份并迁移图片。容器部署应挂载持久卷。
- JSON、SQL、Hexo 导出位于“实用功能 → 数据导出”。请求体上限为 64 MiB，应用单文件上限为 50 MiB；大规模恢复应使用数据库备份工具。
- GitHub 备份、社区同步、SMTP 与 Server 酱通知需分别配置凭据。关于页读取公开仓库信息，不使用文章备份令牌。
- GitHub 公开仓库贡献文章使用后台设置的 GitHub 用户名；个人资料仓库 README 同步需配置具有相应仓库 Contents 读写权限的 Token。预先创建与用户名同名的公开资料仓库，可避免授予创建仓库权限。同步采用仓库默认分支，失败不会写入成功缓存，下一次任务会重试；同步开关保存后生效，定时任务约每天运行一次，也可在实用功能中手动刷新。
- 社区同步会向选定服务发送公开文章，重复导入评论可能产生重复内容。
- Bolo 遗留体验计划已停用，设置开关和邀请弹窗已移除。历史参与配置不再生效，不会自动上传访问统计或错误日志；本地日志浏览仍可使用。
- 模型许可与来源见 `plugins/kanbanniang/README.md`、`SOURCES.json`。自定义外部资源和可选第三方服务由其提供方维护。

## 构建

需要 Node.js 20.19+、Yarn 1.22.22、JDK 17+ 和 Maven：

```sh
yarn install --frozen-lockfile --ignore-scripts --non-interactive
yarn build
node scripts/fetch-geoip.mjs
mvn -Pci -Dlicense.skip=true verify
```

输出为 `target/bolo-netty.zip`，包含应用、依赖、启动脚本和本地静态资源。前端构建不可省略；离线 IP 库由固定版本下载脚本校验 SHA-256 后打包，运行时不会下载。

## 2026.11.01 数据升级与访客评论

启动时由原有 UpgradeService 升级链为评论表补充 `commentClientIP`、`commentNotifyEmail`、`commentGuestKeyHash`、`commentDeleted` 四个兼容字段，完成后更新数据库版本。MySQL、MariaDB、H2 使用相同的 JDBC 元数据检查，支持重复启动和部分 DDL 完成后重试。已有评论与旧通知邮箱绑定继续可读；没有历史 IP 的评论不会推测或补写 IP。升级前备份数据库，先在备份副本验证。

新访客不再写入用户表。访客填写的资料保存在当前浏览器的本地存储；删除浏览器站点数据会清除记忆。资料仅用于填写评论，不代表登录或身份验证。通知邮箱保存在评论记录中，仅管理员可见。

访客 IP 从可信代理配置读取，未登录管理员的访问者只看到打码地址与省市信息。管理员登录后可在前台各皮肤的评论中看到访客完整 IP、位置及封禁按钮；这些信息通过不缓存的权限接口单独加载。管理员自己的评论不保存 IP，也不显示位置。IP 封禁规则保存在现有 option 表，管理员可在博客安全页面管理单 IP / CIDR 网段，或从评论直接封禁；规则立即生效并返回 403。系统拒绝添加覆盖当前管理连接 IP 的规则，以免锁住自身。

封禁列表支持按地址或备注搜索及分页。地区规则同样位于“博客安全”，默认不限制，可选择“拒绝指定地区”或“仅允许指定地区”。国家使用两位代码，如 `US` 或 `CN`；省市规则每行一条，如 `CN/浙江省`、`CN/广东省/深圳市`。国家与省市列表合并匹配，只限制省市时应留空国家列表。省市名称以当前本地库为准，不进行模糊包含匹配。

地区规则同时支持 ip2region 与 MMDB。缺少国家或规则所需的省市信息时，按“位置无法识别时”的设置处理；仅允许模式默认拒绝未知位置。保存时拒绝封禁当前管理连接的规则。若后续 IP 或数据库变化导致无法进入后台，可在 `local.properties` 设置 `geoAccessEnabled=false` 并重启，暂时停用地区限制后修正规则；此项不关闭 IP/CIDR 封禁或登录限流。

发行包的 `geoip/` 包含 ip2region IPv4/IPv6 离线城市数据及许可证。`local.properties` 的 `geoipDatabaseDir` 可指定其他本地库目录，空值使用项目下的 `geoip`。支持 `GeoLite2-City.mmdb` / `GeoIP2-City.mmdb`，查询时优先使用城市 MMDB，再回退 xdb 与 `GeoLite2-Country.mmdb` / `GeoIP2-Country.mmdb`，名称优先中文。ASN 库不用于省市定位。

可与 Nginx 共用目录，例如 `geoipDatabaseDir=/usr/local/nginx-module/geoip`；Java 运行用户只需目录访问和文件读取权限。MMDB 由管理员提供及更新，发行包不包含这些文件。修改配置或替换库后重启 Volo。位置仅为 IP 库估计，不代表访客精确位置。没有匹配结果时保留未知，不会请求远端接口。

评论以两层展示：主评论和回复，回复子评论通过 @ 昵称指明对象。游客的本地资料包含浏览器生成的随机身份凭证，服务端仅保存其哈希；编辑或删除时必须同时匹配凭证和原评论 IP。清除本地身份、变更 IP 或旧评论未绑定凭证时，游客不能修改原评论。管理员可以编辑所有评论。默认删除仅移除本条内容并保留回复；“删除讨论”删除主评论及整条回复链。
