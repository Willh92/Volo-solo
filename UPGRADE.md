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

## 启动

在部署目录执行 `sh start.sh`（Linux）或 `start.cmd`（Windows），默认监听 TCP 8080。脚本优先使用 `JAVA_HOME`，端口可通过 `--listen_port 8080` 指定。未显式设置环境变量 `JAVA_OPTS` 时，启动脚本与 `volo-unix.init.example` 默认启用 `-Xms128m -Xmx384m -XX:+UseG1GC -XX:+UseStringDeduplication -Djava.awt.headless=true -Dio.netty.allocator.maxOrder=9`；站点可根据负载在环境中自定义 `JAVA_OPTS` 覆盖默认值。

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

`listenSocket` 中的权限值只控制 socket 文件，不改变父目录和项目的权限或属组。建议通过共享组配合 `0660`；放宽 socket 权限也不能代替父目录的执行权限。锁文件始终保持 `0600`，Nginx 不需要读取它。

路径可改为 `/run/volo/volo.sock`。运行目录在系统重启后由服务脚本重建，不能直接使用全局可写的 `/dev/shm` 根目录。

Nginx 完整上游示例见 `nginx-unix.conf.example`，核心地址为：

```nginx
proxy_pass http://unix:/dev/shm/volo/volo.sock:;
```

不指定替换 URI 时保留原始路径和查询参数，无需追加 `$request_uri`。Nginx 继续负责 HTTPS 443；修改配置后执行 `nginx -t`，通过后再 reload。

程序拒绝覆盖已有 socket、普通文件或符号链接。异常退出后若遗留 `.sock`，须先确认相关进程均已停止，再由管理员处理。`.sock.lock` 文件保留，锁随进程退出释放。切回 TCP 时清空 `listenSocket` 并移除 `--listen_socket`；服务模板将 `LISTEN_SOCKET` 设为空即可。

### 缓存与压缩

前端构建会对 `js/`、`scss/`、`skins/`、`plugins/`、`images/` 下体积达到 1 KiB 的静态 JS/CSS/SVG/JSON 资源统一生成预压缩 `.gz` 旁路文件，运行时直接发送预压缩字节流并附带 `Vary: Accept-Encoding`、ETag 与 `Cache-Control` 协商缓存（即使站点尚未完成首次初始化，静态资源仍直接响应而不触发初始化数据库查询）；本地图床 `/image/*` 支持 ETag、`Last-Modified` 条件请求（`304 Not Modified`）并限制为白名单光栅图片类型。正文、摘要及评论编辑器共享已加载的运行库。

Nginx、面板或 CDN 不应向静态响应统一追加 `Cache-Control: no-store`、`no-cache`，也无需统一清除 `Expires`。静态资源和动态页面的缓存策略由应用设置：匿名访问不保留空会话且不下发临时会话 Cookie；已登录状态下访问前台页面自动切换为 `Cache-Control: private, no-cache`，登录与后台页面始终禁止缓存。

压缩配置参考 `nginx-cache.conf.example`。静态资源响应的缓存策略应有效且无冲突，支持 gzip 的请求应返回 `Content-Encoding: gzip`。

## 后台入口与登录

`adminPath` 留空时，面板为 `/admin-index.do`。设置 `adminPath=/private-panel` 并重启后，面板为 `/private-panel`，原面板入口关闭。登录页可在“安全 → 访问防护”中单独修改，保存后立即生效，无需重启；留空使用 `/start`，自定义路径不能占用系统路由或已有文章、独立页面地址。旧配置尚未保存独立登录路径时，仍沿用 `adminPath` 对应的 `/private-panel/login`。管理员登录后，各皮肤优先使用原有管理按钮或菜单显示实际后台入口和退出登录，不改变站点保存的自定义导航。

“偏好设定 → 前端开放设置”提供两个独立开关：登录入口默认隐藏，开启后向未登录访客显示配置的登录地址；评论开关沿用原交互配置，控制评论区及访客提交评论。搜索保持可用。两个开关均不隐藏已登录管理员的后台管理和退出入口，也不影响后台管理已有评论。设置保存在现有 option 表，无需新增表或字段。

管理路径支持多级路径，仅允许英文字母、数字、连字符和下划线，不能占用系统路由前缀。路径配置不替代身份验证。

登录、评论限流和 WAF 共用可信代理规则。`loginTrustedProxies=unix` 只信任实际启用的 Unix 监听；TCP 代理应配置准确 IP。Nginx 前还有 CDN 时，应仅对可信 CDN 还原访客 IP，再覆盖传给应用的 `X-Real-IP`。

登录限制为每个来源与账号 10 分钟内 5 次、每个来源合计 20 次；IPv6 同一 /64 共用预算。匿名评论参数位于 `local.properties`。限制在当前 Java 进程内维护，多实例部署应在入口统一限流。

## 安全防护

### IP 限制与自动风控

安全菜单依次提供访问防护、2FA 验证、评论防护、地区规则和 IP 限制。IP 列表可按来源、状态、地址或备注筛选；地区列表支持分页和跨页排序，排序后需保存地区规则。

手动 IP/CIDR 封禁默认永久，可选择 1、3、7、30 天或自定义天数。生效期间返回 403，到期立即放行，支持提前解除；旧规则没有到期时间时继续按永久处理。封禁记录包含对象类型、来源、原因、创建时间和到期时间。当前支持 IP/网段，尚未开放用户账号封禁。

“编辑封禁”可修改备注和期限，默认保留原到期时间；另选时长时，从保存时重新计算。过期记录可通过“重新生效”设置新期限。自动记录保留来源与触发原因，累计次数可以修改或重置为 0；重置不改变当前期限，删除整条记录则清除累计历史。

访问防护可独立启停自动风控。路径遍历立即触发；在 2 分钟窗口内，3 个不同敏感文件/脚本探测路径或 8 次探测、20 个不同 404 路径或 60 次 404、40 次无效/未授权/超限响应、480 次非静态请求会自动封禁 24 小时。普通缺失图片、脚本、样式、字体不计入通用 404 规则。明确的可执行 XSS 载荷永久封禁，包括请求路径及评论元信息、正文中代码块之外的可执行标签；内容过滤和输出转义仍独立生效。规则列表标明自动风控或手动封禁，并保存具体触发原因，不保存攻击原文。

自动封禁到期后再次触发时复用同一地址的记录，保留最初创建时间并更新最近触发时间。累计第 2、3、4 次及之后分别封禁 3、7、30 天；有效期内重复请求不延长期限。旧自动记录没有累计字段时按已触发 1 次处理，重置为 0 后下次按首次 24 小时处理。历史记录超过保留上限被清理后，不再累计该记录。

自动风控不记录已登录管理员的请求，也不封禁内网、回环、链路本地等内部地址。通过 Nginx 时必须配置可信代理，按真实客户端 IP 判断；仅 Nginx 与 Java 的连接地址为回环并不代表外部访客是内网。管理员登录状态可用于解除 IP 封禁，当前管理连接仍不可被手动规则覆盖。未登录的管理入口不享受管理员豁免。地区限制仍按已保存策略执行。

风控和封禁使用现有 option 表持久保存，不需要额外建表。到期判断根据服务器时间；重启后保留期限。封禁列表最多保留 1024 条记录，满额时优先清理已到期项。

### 2FA 双重验证

在“安全 → 2FA 验证”中绑定并配置基于时间的一次性动态口令（RFC 6238 TOTP，兼容 Google Authenticator、Microsoft Authenticator、2FAS、1Password、Bitwarden 等标准身份验证器）。绑定二维码与 `otpauth://` 链接全部在本地生成，不请求外部服务。

提供五项后台配置：登录 2FA 状态（启用／暂停）、适用账号范围（仅管理员／全部后台登录账号）、验证器显示名称、时间偏移容差（严格当前窗口／前后 ±30 秒／前后 ±60 秒）及浏览器免二次验证时长（每次均需验证／7 天／14 天／30 天）。在已绑定密钥状态下切换启停、换绑密钥、重新生成恢复码或解绑时，均需校验当前动态口令或应急恢复码，并受独立重试限流保护。

首次绑定或重新生成时会签发 8 组一次性应急恢复码（服务端仅保存加盐 SHA-256 摘要，明文仅展示一次）。勾选“记住此浏览器”后写入 `HttpOnly`、`SameSite=Lax` 签名 Cookie（HTTPS 下自动附带 `Secure`）；管理员主动撤销受信浏览器、换绑／解绑 2FA 或修改账号登录密码后，已有免验证 Cookie 立即失效。

配置持久保存在现有 option 表（键 `voloTotpAuth`），无需改动数据库表结构。

### 验证码

未启用 2FA（以及首次初始化、快速迁移阶段）时，后台登录使用本地图片验证码；启用 2FA 后，后台登录自动关闭图片验证码并改由第二步动态口令保护，暂停或解绑 2FA 后自动恢复图片验证码。游客发表评论、回复和编辑时，验证码显示在评论编辑区；“安全 → 评论防护”可关闭游客验证码，默认启用，管理员评论免验证。验证码有效期 3 分钟，一次使用，并与用途及真实客户端地址绑定；点击图片可换一张。多实例部署需要保持会话粘滞，验证码状态不跨进程共享。

验证码为五位字母或数字，不区分大小写，由 Java 2D 本地生成。最小化 Linux 主机需安装字体环境，例如 Debian / Ubuntu 的 `fontconfig` 和 `fonts-dejavu-core`；项目 Docker 镜像已包含这些组件。

### 文章访问密码

受保护文章的密码校验独立限流：同一来源 IP 与文章每 10 分钟最多 5 次提交，同一来源跨文章合计最多 20 次，IPv6 同一 /64 共用预算。超限返回 429、`Retry-After` 和原页面内提示；成功校验会清除该文章的失败计数，跨文章总预算仍保留。限制保存在当前 Java 进程内，重启后清空，多实例需要在反代层增加共享限流。管理员登录的重试计数与文章密码分开。

## 评论与地区访问

### 数据升级与访客身份

启动时由原有 UpgradeService 升级链为评论表补充 `commentClientIP`、`commentNotifyEmail`、`commentGuestKeyHash`、`commentDeleted` 四个兼容字段，完成后更新数据库版本。MySQL、MariaDB、H2 使用相同的 JDBC 元数据检查，支持重复启动和部分 DDL 完成后重试。已有评论与旧通知邮箱绑定继续可读；没有历史 IP 的评论不会推测或补写 IP。升级前备份数据库，先在备份副本验证。

访客填写的资料保存在当前浏览器的本地存储，不创建用户账号；删除浏览器站点数据会清除记忆。资料仅用于填写评论，不代表登录或身份验证。通知邮箱保存在评论记录中，仅管理员可见。

访客 IP 从可信代理配置读取，未登录管理员的访问者只看到打码地址与省市信息。管理员登录后可在前台各皮肤的评论中看到访客完整 IP、位置及封禁按钮；这些信息通过不缓存的权限接口单独加载。管理员自己的评论不保存 IP，也不显示位置。IP 封禁规则保存在现有 option 表，管理员可在“安全 → IP 限制”页面管理单 IP / CIDR 网段，或从评论直接封禁；规则立即生效并返回 403。系统拒绝添加覆盖当前管理连接 IP 的规则，以免锁住自身。

封禁列表支持按地址或备注搜索及分页。IP/CIDR 规则与地区访问规则分别生效，地区“允许”规则不会绕过已配置的 IP 封禁。

### 多条地区访问规则

在“安全 → 地区规则”中管理规则，默认暂停地区限制。启用后，按列表从上到下执行。每条规则分别设置命中操作和未命中操作：未命中时可继续下一条、允许或拒绝。默认继续下一条；所有规则都继续且未命中时允许访问。禁用的规则完全跳过；列表中的启用／禁用按钮立即保存，其他编辑和排序需点击底部“保存地区规则”。

每条规则可以填写多个国家代码及省市范围，二者为“或”关系。国家使用两位代码，如 `US`、`CN`，逗号分隔；范围每行一项，如 `CN`、`CN/浙江省`、`CN/广东省/深圳市`，也接受反斜杠形式 `CN\浙江省`、`CN\广东省\深圳市`。保存后统一为斜杠格式。只匹配省市时留空国家代码，名称以本地 IP 库为准，不进行模糊包含匹配。

允许、拒绝规则可以同时存在。例如按下列顺序配置：

| 顺序 | 操作 | 国家代码 | 省市范围 |
| --- | --- | --- | --- |
| 1 | 允许 | 留空 | `CN/广东省/深圳市` |
| 2 | 拒绝 | 留空 | `CN/广东省` |
| 3 | 允许 | `CN` | 留空 |
| 4 | 拒绝 | `US` | 留空 |

上例每条规则的未命中操作均选择“继续下一条”，允许深圳及中国其他省份，拒绝广东其他城市和美国，其他已知国家允许访问。较具体的例外应放在覆盖范围更大的规则前面。最多保存 128 条规则。

地区规则同时支持 ip2region 与 MMDB。国家无法识别，或优先规则所需省市信息缺失时，直接采用“位置无法识别时”策略，不跳过该规则继续匹配较低优先级规则。

地区配置使用现有 option 表的版本化 JSON，不修改数据库结构或数据库版本。旧单模式和列表默认策略按原效果读入，将未命中策略转换到最后一条启用规则。若旧配置没有启用规则且默认拒绝，保留该限制并在页面显示兼容提示。

保存后地区文档版本为 3，运行程序必须支持逐条命中和未命中操作。回退到只支持旧单模式或旧列表格式的程序时，应同时恢复对应的配置或数据库备份，避免丢失未命中操作的限制。

保存时拒绝封禁当前管理连接的配置。若后续 IP 或数据库变化导致无法进入后台，可在 `local.properties` 设置 `geoAccessEnabled=false` 并重启，暂时停用地区限制后修正规则；此项不关闭 IP/CIDR 封禁或登录限流。

### 离线定位库

发行包的 `geoip/` 包含 ip2region IPv4/IPv6 离线城市数据及许可证。`local.properties` 的 `geoipDatabaseDir` 可指定其他本地库目录，空值使用项目下的 `geoip`。支持 `GeoLite2-City.mmdb` / `GeoIP2-City.mmdb`，查询时优先使用城市 MMDB，再回退 xdb 与 `GeoLite2-Country.mmdb` / `GeoIP2-Country.mmdb`，名称优先中文。ASN 库不用于省市定位。

可与 Nginx 共用目录，例如 `geoipDatabaseDir=/usr/local/nginx-module/geoip`；Java 运行用户只需目录访问和文件读取权限。MMDB 由管理员提供及更新，发行包不包含这些文件。修改配置或替换库后重启 Volo。位置仅为 IP 库估计，不代表访客精确位置。没有匹配结果时保留未知，不会请求远端接口。

### 评论管理

评论以两层展示：主评论和回复，回复子评论通过 @ 昵称指明对象。游客的本地资料包含浏览器生成的随机身份凭证，服务端仅保存其哈希；编辑或删除时必须同时匹配凭证和原评论 IP。清除本地身份、变更 IP 或旧评论未绑定凭证时，游客不能修改原评论。管理员可以编辑所有评论。默认删除仅移除本条内容并保留回复；“删除讨论”删除主评论及整条回复链。

默认皮肤在管理员登录后提供导航中的后台入口和退出登录，并在首页侧栏显示最新评论；点击评论进入文章并定位到该评论。手机端管理和退出图标位于导航栏“更多”之后。访客不显示这些管理功能，最新评论数量沿用后台的显示数量设置。

## 数据与外部服务

### SMTP 通知

在“偏好设定 → 动态提醒”填写通用 SMTP 配置：服务器、端口、连接加密、身份验证、用户名、密码或授权码、发件人邮箱。SSL/TLS 默认 465，STARTTLS 默认 587，可信内部明文中继默认 25；显式填写端口优先。SMTP 服务器留空停用邮件通知，密码留空保留原密码。旧 QQ、企业邮箱和 163 选择自动转换为可编辑字段，不再提供服务商下拉项。SMTP TLS 校验证书与服务器身份，连接、读写均有超时限制。明文传输仅适合可信内部中继。

### 备份、同步与资源

- 本地图床应使用 Java 用户可读写的持久目录；更换路径前备份并迁移图片。容器部署应挂载持久卷。
- JSON、SQL、Hexo 导出位于“实用功能 → 数据导出”，响应均禁止缓存并在 `finally` 中确保清理临时目录与归档文件；SQL 导出并发消费 `mysqldump` 标准输出与错误流并设 5 分钟超时保护。JSON 导出会自动剔除 `voloTotpAuth` 密钥配置；SQL 导出为完整原始库表灾难恢复备份，包含全部表记录，需加密并妥善保管。请求体上限为 64 MiB，应用单文件上限为 50 MiB；大规模恢复应使用数据库备份工具。
- GitHub 备份、云图床、社区同步、SMTP 与 Server 酱通知需分别配置凭据；后台设置接口对已保存的 GitHub PAT、云图床密钥（七牛／阿里云／腾讯云／又拍云／Picuang）、社区密钥、Server 酱令牌和邮件密码统一脱敏回显，留空保存保留原值，云图床面板支持一键清空已存密钥。关注站点 RSS/Atom 拉取与友链图标抓取仅允许公网 HTTP(S) 地址，连接阶段绑定已校验公网 IP（防 DNS 重绑定，忽略系统代理）并阻断回环、内网、链路本地、CGNAT 及 IPv6 NAT64/6to4/Teredo/ULA 等保留网段，同时限制重定向次数与响应体积。关于页读取公开仓库信息，不使用文章备份令牌。
- “偏好设定 → 我的 GitHub”统一管理 GitHub ID、自动同步、立即同步、是否包含 Fork 和逐仓库显示设置。同步读取该账号全部公开仓库，默认展示原创仓库；隐藏仓库或排除 Fork 后，统计卡片的项目数、Stars 和 Forks 只计算实际展示的仓库。Stars 是这些仓库获得的星标总数。显示选择在下次同步后保留；同步失败保留旧快照，空仓库列表会正常更新。
- GitHub 文章的头部横幅、统计卡片和统计标题链接到对应的 GitHub 用户主页。个人资料仓库 README 同步需配置具有相应仓库 Contents 读写权限的 Token。预先创建与用户名同名的公开资料仓库，可避免授予创建仓库权限。同步采用仓库默认分支，失败不会写入成功缓存，下一次任务会重试；同步开关保存后生效，定时任务约每天运行一次。
- “实用功能 → 维护工具”提供远程文章备份、社区站点刷新和通知邮箱缓存清理。社区备份和社区站点刷新需要启用 B3log 插件。
- 社区同步会向选定服务发送公开文章，重复导入评论可能产生重复内容。
- Bolo 遗留体验计划已停用，设置开关和邀请弹窗已移除。历史参与配置不再生效，不会自动上传访问统计或错误日志；本地日志浏览仍可使用。
- 模型许可与来源见 `plugins/kanbanniang/README.md`、`SOURCES.json`。自定义外部资源和可选第三方服务由其提供方维护。

## 日志

日志配置为 `WEB-INF/classes/log4j2.xml`，旧 `log4j.properties` 不再使用。需要文件日志时，将 `log4j2-file.xml.example` 复制为 `log4j2.xml`。文件写入 `${base.dir}/logs/solo.log`，每 50 MB 轮转，保留 5 份备份；未设置 `-Dbase.dir` 时使用部署目录。

后台首页显示最近 15 条日志，可点击“查看全部”进入实用功能的日志浏览。日志浏览支持分页，读取部署目录 `logs/admin-events.*.jsonl` 中保留的记录；每份最多 2 MB，共保留 5 份。目录不可写时使用最近 100 条内存日志，并在页面提示。操作日志不包含文章、评论的完整请求和访问密码；单条消息限制为 4096 字符，异常堆栈保留前 12 层。

由服务脚本重定向产生的 `console.log` 属于系统控制台输出，应交给系统 `logrotate` 管理；需要应用内文件轮换时使用上述 Log4j 2 文件配置。

## 构建

构建命令和工具版本见 [README.md](README.md#开发与构建)。输出为 `target/bolo-netty.zip`，包含应用、依赖、启动脚本和本地静态资源。离线 IP 库由固定版本下载脚本校验 SHA-256 后打包，运行时不会下载。
