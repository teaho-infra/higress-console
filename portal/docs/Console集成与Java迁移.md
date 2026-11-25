# Console 集成与 Java 迁移

## 使用入口

打开 <http://127.0.0.1:18001/walmart-quota>，或在 Console 菜单展开 **Walmart 额度管理**，选择对应子菜单。

六个原生 Console 页面：`overview`（用量总览）、`accounts`（用户与应用）、`departments`（部门模板）、`tiers`（档位配置）、`prices`（模型单价）、`audit`（变更审计），URL 前缀均为 `/walmart-quota/`。支持直接访问、刷新和浏览器前进后退。页面内不再显示 Portal 侧栏或工作区顶栏。

只需登录 Console，额度页面复用 Console 会话，不再要求独立额度密码，也不显示退出额度登录按钮。个人预算、部门兜底、模型价格、审计、CSV 批量导入均保留。

`http://127.0.0.1:18890/` 保留为兼容入口，使用相同 React 源码和 Java 后端。旧 Node 服务已停止。

## 代码区隔

| 位置 | 职责 |
|---|---|
| `frontend/src/custom/walmart-quota/index.jsx` | 原 Portal 页面；在 Console React 树中渲染，不使用 iframe |
| 同目录 `BulkImport.jsx`、`import.mjs`、`money.mjs`、`api.js` | 导入、金额计算、原 API 适配 |
| 同目录 `style.css`、`menu.ts` | `.walmart-quota` 范围内样式及定制菜单 |
| `frontend/src/pages/walmart-quota/*.tsx` | 六个独立页面入口；index 重定向至总览 |
| `backend/walmart-quota/` | 独立 Spring Boot 2.7.18 模块，Java 8 兼容字节码 |
| `QuotaAutoConfiguration` | 开关控制的自动装配，仅注册额度 API |
| `QuotaApi` | 原 HTTP 接口、参数校验和响应格式 |
| `QuotaSessions`、`QuotaSettings` | 独立会话、CSRF、登录限流及配置 |
| `QuotaGateway` | 固定网关地址和管理 Key，10 秒总超时，不跟随重定向 |
| `QuotaApplication` | 独立兼容入口启动类，不参与 Console 扫描 |
| `backend/walmart-quota-tools/` | Java 部署、迁移、模拟模型、Wasm 文件服务、初始化与验收 CLI |
| `portal/src/` | 指向定制前端的兼容适配，不维护第二份页面实现 |
| `portal/tests/`、`portal/docs/` | 浏览器测试及脱敏记录 |

原有文件的接入改动只有三处：

1. `frontend/src/pages/_defaultProps.tsx`：导入并加入定制菜单。
2. `backend/pom.xml`：新增 `walmart-quota` Maven profile 模块声明。
3. `backend/console/pom.xml`：同名 profile 引入 Java 模块依赖。

没有修改官方 Console 控制器、SDK、认证逻辑或 `ai-quota-walmart` 插件源码；Higress 网关镜像未变。Console 自身需要包含新模块和页面的新镜像。

后续合并开源版本时，保留两个定制后端目录、定制前端目录及薄路由，只复核上述三处接入。不开启 Maven profile 可构建不含定制后端的 Console；定制菜单需要同步移除或不使用。

## 构建

以下命令在 `higress-console` 根目录执行：

```bash
npm --prefix frontend run build
mvn -f backend/walmart-quota/pom.xml test
mvn -f backend/walmart-quota-tools/pom.xml package
mvn -f backend/pom.xml -P walmart-quota \
  -DskipTests -Dcheckstyle.skip=true -Dpmd.skip=true -Dgpg.skip=true \
  -Dmaven.javadoc.skip=true -Denforcer.skip=true \
  -Dskip.npm=true -Dskip.npx=true -Dskip.installnodenpm=true \
  -Dlombok.delombok.skip=true package
```

集成打包复用已经构建的 `frontend/build`。第一步不要省略；后端测试单独执行，上面打包步骤不重复运行官方项目测试。

### 2026-11-25：L3 移除后的静态资源修复

浏览器打开“档位配置”编辑框时报 `Cannot read properties of undefined (reading 'quota')`。检查正在返回的 `/js/9722-ef6c968f3f8329f2be57.js`，发现它仍循环渲染 L1/L2/L3，而运行数据已只有 L1/L2。根因是旧 `src/main/resources/static` 资源进入 JAR，Maven 又按目标文件时间戳跳过了新 `index.html`。

`backend/console/pom.xml` 现于 `prepare-package` 将 `frontend/build` 强制复制到 `target/classes/static`；清除本地生成的旧静态目录后重新打包。新 JAR 只含 `9722-5b51d2c22c3ff74e14d1.js`，入口指向 `main-53b3b9e3741a51b86ff1.js`。部署镜像 `higress-console/walmart-quota:20261125-no-l3-fix` 后，服务 `/assets-manifest.json` 指向新脚本；该脚本没有 L3。`CONSOLE_TEST_URL=http://127.0.0.1:18001 node portal/tests/browser-console-migration.mjs` 验证编辑框仅显示 L1/L2、无页面错误，HTTP 页面返回 200。

### 2026-11-25：预算结算周期

“档位配置”支持设置下一周期的天数（1–365 天）及手动重置。保存周期只修改下期设置，当前周期余额和消费不变；重置会立即开启新周期，按当前档位重新初始化用户预算，临时加量不结转。若已设置下期天数，重置立即采用。重置须管理员确认，`/cycle` 与 `/reset` 均通过原 Console 会话、CSRF 和网关管理 Key 鉴权，并记录审计工单。结算中的旧请求仍入旧周期账本；周期配置与账本一样保存在 Redis，须启用 Redis 持久化。

重置时也可选择启止日。页面向 `/reset` 传 `start_date`、`end_date`（`YYYY-MM-DD`），以 Asia/Shanghai 自然日计算，结束日包含全天；选定区间须包含今天且为 1–365 天。所选周期结束后按“下一周期天数”自动滚动。接口不传日期时保留原有立即起算方式。网关侧和 Redis 脚本均校验区间，审计记录保存实际生效的起止时间。

Higress 插件改动：`dimensional.go` 增加管理员动作校验；`money_ledger.lua` 从 Redis 周期配置计算当前周期、延期结算及工单幂等；`cycle_integration_test.py` 使用独立 Redis 前缀验证切换、重置、过期工单及迟到结算。Console 改动：`QuotaApi.java` 放行两项管理动作；`index.jsx`、`style.css` 增加周期配置区和确认操作；浏览器脚本覆盖两项按钮。

调试结果：Go 单元测试、隔离 Redis 周期集成测试、Java 接口 12 项检查和 Console 浏览器回归通过；Console `higress-console/walmart-quota:20261125-cycle` 为 1/1，页面 HTTP 200，Wasm 摘要为 `41ee9d7721852a96198348a43d4f8555decb551570d54eb8b2b70d096559a1bb`。真实网关只读取 `/meta`：当前周期 `2026-11-23`、当前/下期均为 14 天；未对真实账户执行重置。隔离 Redis 容器在调试后已关闭。

### 2026-11-25：重置周期可设置启止日

改动文件：Higress `plugins/wasm-go/extensions/ai-quota-walmart/{dimensional.go,money_ledger.lua,cycle_integration_test.py,README.md}`；Console `frontend/src/custom/walmart-quota/index.jsx`、`portal/tests/browser-console-migration.mjs`、本文。`/reset` 新增可选的 `start_date`、`end_date`；Console 页面提交这两个日期，接口和 Redis 均拒绝缺项、过期或未来区间。审计保留实际边界，旧账本不删除。无需改动 Java API 透传代码。

调试命令与结果：

```bash
# 独立 Redis：只用于测试，测试后 docker stop。
docker run --rm -d --name walmart-cycle-test-redis -p 127.0.0.1:16389:6379 redis:7-alpine
cd /home/teaho/IdeaProjects/teaho-infra/higress/plugins/wasm-go/extensions/ai-quota-walmart
python3 cycle_integration_test.py                         # PASS
GOMODCACHE=/tmp/higress-quota-modcache go test ./...       # PASS
GOMODCACHE=/tmp/higress-quota-modcache GOOS=wasip1 GOARCH=wasm go build -buildmode=c-shared -o main.wasm .
sha256sum main.wasm                                        # a77530d2d3e6a2750b4ecfad772bea240bd72979af4c68fa79c2d4a799177512
docker stop walmart-cycle-test-redis

cd /home/teaho/IdeaProjects/teaho-infra/higress-console
npm --prefix frontend run build                            # PASS
mvn -q -f backend/pom.xml -P walmart-quota -DskipTests -Dcheckstyle.skip=true -Dpmd.skip=true -Dgpg.skip=true -Dmaven.javadoc.skip=true -Denforcer.skip=true -Dskip.npm=true -Dskip.npx=true -Dskip.installnodenpm=true -Dlombok.delombok.skip=true clean package
node --test portal/tests/server.test.mjs                    # 12/12 PASS
portal/scripts/quota-tools install --wasm /home/teaho/IdeaProjects/teaho-infra/higress/plugins/wasm-go/extensions/ai-quota-walmart/main.wasm --apply
(cd backend && minikube -p higress-dev image build -t higress-console/walmart-quota:20261125-cycle-dates -f walmart-quota/Console.Dockerfile .)
kubectl --context higress-dev -n higress-system set image deployment/higress-console higress-console=higress-console/walmart-quota:20261125-cycle-dates
kubectl --context higress-dev -n higress-system rollout status deployment/higress-console --timeout=120s
CONSOLE_TEST_URL=http://127.0.0.1:18001 node portal/tests/browser-console-migration.mjs # PASS
```

部署核查：Console `higress-console/walmart-quota:20261125-cycle-dates` 为 1/1，`/walmart-quota/tiers` 返回 HTTP 200；WasmPlugin URL 含上述 `a77530...` 摘要。真实网关 `/meta` 只读检查成功，当前周期 ID 为 `r1:1795597886`，当前及下期均为 14 天。本次未对真实预算执行 `/reset`。

产物：`backend/console/target/higress-console.jar`。必须用 `-P walmart-quota` 打包，并在运行时设置 `WALMART_QUOTA_ENABLED=true`。

## 部署配置

Java 模块读取以下配置（也支持 `walmart.quota.*` Spring 属性）：

| 环境变量 | 含义 |
|---|---|
| `WALMART_QUOTA_ENABLED` | Console 中显式设为 `true` |
| `PORTAL_PASSWORD` | 仅独立 Portal 兼容入口需要，至少 12 字符；Console 集成模式不需要 |
| `GATEWAY_ADMIN_KEY` | Key Auth 的 quota-admin 凭据，仅服务端保存 |
| `GATEWAY_URL` | 集群内设为 `http://higress-gateway.higress-system.svc.cluster.local` |
| `GATEWAY_HOST` | `quota-portal.local` |
| `PORTAL_SECURE_COOKIE` | HTTPS 部署设为 `true` |

本地镜像构建：

```bash
(cd backend && minikube -p higress-dev image build \
  -t higress-console/walmart-quota:20261125 \
  -f walmart-quota/Console.Dockerfile .)
```

`Console.Dockerfile` 复用已有 Console Java 运行环境，只替换 JAR。运行参数和原 Console Secret 保留。额度密码和管理 Key 由独立 Kubernetes Secret 引用，禁止放入镜像或前端代码。

当前已部署镜像 `higress-console/walmart-quota:20261125`；服务沿用 `18001 → NodePort 30001`。

## Java 模拟服务与 Redis

新 Java 服务名：`walmart-quota-portal-java-fixtures`，提供模型 `8080` 和 Wasm 下载 `8090`。

旧 `walmart-quota-portal` Pod 保留，因为 Redis 无持久化，重建会丢数据。旧 Python 模拟容器仍随这个 Pod 存在，但模型和 Wasm 下载流量已切到 Java；不要直接删除旧 Pod。

Redis 独立注册为 `McpBridge/default` 中的 `walmart-quota-redis` DNS 服务源。额度及 token-window 插件引用 `walmart-quota-redis.dns`，指向原 Redis Service，密码及账本前缀不变。这样 Redis 连接不再依赖模型路由的后端。

```bash
# 已有集群迁移；无 --apply 时仅生成清单与备份。
portal/scripts/quota-tools migrate-fixtures \
  --wasm /绝对路径/ai-quota-walmart/plugin.wasm --apply
```

工具会验证已有凭据和 Wasm SHA，备份配置、等待 Java 服务就绪、验证下载文件，然后修改专属路由和插件配置。重复执行不会重复添加 Redis 服务源。见 [Java 工具指南](java-tools.md)。

## 实际调试记录（2026-11-25）

| 检查 | 结果 |
|---|---|
| Java API / 自动装配 | 18 项通过 |
| Java 部署工具 | 10 项通过 |
| 原 HTTP / CSV / 金额契约，连接真实 Java 服务 | 18 项通过 |
| Console 与兼容前端构建 | 均通过 |
| Console 真实浏览器：登录、模板、预算、模型价格、移动端 | 10 项通过 |
| Console CSV：校验、预览、部分失败、仅重试失败项、插件落账 | 10 项通过 |
| Java CLI → Higress → Java 模拟模型 → Redis | 金额验收 108 项通过，含 SSE、幂等、429、Redis 故障及恢复 |

调试中修复：严格拒绝尾随无效 JSON；保留 URL 查询归一化；恢复独立入口的浏览器安全头；安装时缺少/不匹配凭据直接拒绝；迁移后通过独立 DNS 服务源恢复 Redis 集群发现。

```bash
cd portal
npm test
node tests/browser-console-java.mjs --allow-mutations
node tests/browser-console-import.mjs --allow-mutations
scripts/quota-tools verify-money --allow-mutations
```

后三条会创建测试账户/审计记录；金额验收还会临时改价并恢复、暂停演示 Redis 验证故障恢复。应逐条执行，不要与正常业务并行。没有自动重新初始化旧用户。

证据：`evidence-console-java/`、`evidence-money/gateway-report.json`。原始日志与备份在忽略提交的 `portal/.debug/java-migration/` 和 `portal/.debug/cluster/fixture-migration-*`，其中可能含私密配置，不要提交。

## Console 统一登录（2026-11-25）

- `ConsoleAuthentication.java` 通过原 `SessionService.validateSession` 验证每次额度请求，不修改官方认证源码。
- `QuotaApi.java` 在 Console 模式取消独立密码登录和退出，匿名接口仍返回 401；保留 Origin 与 CSRF 校验。CSRF 会话本身不授予访问权限。
- `QuotaSettings.java` 在 Console 模式不再要求 `PORTAL_PASSWORD`，独立运行仍要求。
- 前端 `index.jsx` 移除 Console 内的额度退出按钮和密码表单；会话失效时引导回 Console 登录。
- 验证：`cd portal && node tests/browser-console-auth.mjs`；报告见 `evidence-console-auth/`。
