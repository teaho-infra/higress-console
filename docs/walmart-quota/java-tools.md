# Java 部署与验收工具

Portal 原有 Python 部署、模拟服务、初始化和验收工具已迁入独立 Maven 模块 `backend/walmart-quota-tools`。可执行 JAR 使用 Spring Boot 2.7.18 打包，兼容 Java 8。配额 WASM 源码、网关镜像和内置插件固定摘要不变。历史文档中的 Go 插件仓库 Python 测试不属于 Portal 运行依赖。

## 构建与首次部署

在 Console 仓库根目录执行：

```bash
mvn -f backend/walmart-quota-tools/pom.xml clean package
backend/walmart-quota-tools/quota-tools
# 默认只生成私密清单，不操作集群或初始化用户。
backend/walmart-quota-tools/quota-tools prepare
# 全新演示环境才执行以下部署步骤：
docker build -t walmart-quota-tools:local backend/walmart-quota-tools
minikube -p higress-dev image load walmart-quota-tools:local
backend/walmart-quota-tools/quota-tools prepare --apply
kubectl --context higress-dev -n higress-system rollout status deployment/walmart-quota-portal --timeout=60s
backend/walmart-quota-tools/quota-tools install --wasm /absolute/path/plugin.wasm
# 确认私密清单后安装现有制品，不重新编译 WASM：
backend/walmart-quota-tools/quota-tools install --wasm /absolute/path/plugin.wasm --apply
```

`prepare --apply` 拒绝覆盖已有 Deployment，因为其中 Redis 没有持久化。已有环境使用下面的保留账本迁移命令。默认配置为 `backend/walmart-quota-tools/config/money-config.json`；显式添加 `--config backend/walmart-quota-tools/config/quota-config.json` 可选择旧 Token 策略。

`.walmart-quota/.debug/cluster/credentials.json` 原样复用，目录权限 0700、文件权限 0600。已有 `.walmart-quota/.env` 内容不覆盖，只修正文件权限；文件缺失时才从保存的凭据生成。工具不打印凭据，报告写入前替换所有保存的密钥。已有集群对应的私密凭据不能删除后重新生成。

`install --apply` 强制要求已有凭据，并在任何集群写入前校验 Redis 密码与现有 Kubernetes Secret 一致；缺失或不一致直接失败，不生成新密码。

## 已有环境迁移：保留 Redis 账本

先构建并加载 Java tools 镜像，再指定当前已安装的同一份内容寻址 WASM 制品：

```bash
backend/walmart-quota-tools/quota-tools migrate-fixtures --wasm /absolute/path/plugin.wasm
backend/walmart-quota-tools/quota-tools migrate-fixtures --wasm /absolute/path/plugin.wasm --apply
```

命令先核对凭据和制品 SHA-256，在新的 `.walmart-quota/.debug/cluster/fixture-migration-UUID` 私密目录保存原始 ingress、配额插件、窗口插件和 `McpBridge/default`。不带 `--apply` 时仅生成快照与新清单。

应用时创建独立 `walmart-quota-portal-java-fixtures` Deployment/Service，使用不同标签，不修改原 Deployment、Redis 容器或 Redis Service。等待就绪后复制原 WASM，并从新 Pod 通过 Service HTTP 下载计算 SHA-256。制品校验通过才切换配额插件 URL 与两条演示 ingress 后端。

为避免原 Service 不再被 ingress 引用后 Redis 上游从 Envoy 消失，迁移还在 `McpBridge/default` 注册：

| 字段 | 值 |
| --- | --- |
| name | `walmart-quota-redis` |
| type | `dns` |
| domain | `walmart-quota-portal.higress-system.svc.cluster.local` |
| port | `6379` |
| protocol | `tcp` |

所有其他 registry 原样保留。配额插件和窗口插件各 matchRule 的 Redis 配置仅将 `service_name` 改为 `walmart-quota-redis.dns`；密码、key prefix、价格与其他策略字段保留。重复执行可识别相同 registry，不重复添加；同名冲突配置会拒绝修改。后续 `install --apply` 自动沿用这个 Redis 别名和 Java 静态服务。

所有 spec 修改均使用 JSON Patch `test` 防止覆盖并发配置变更。含密码的插件 patch 写入 0600 临时文件，通过 `--patch-file` 传递并在调用后删除，不把秘密放进进程参数。路由切换按顺序完成，不是事务；中途失败可能使部分路由仍指向旧模拟服务。两套模拟响应一致，可根据私密快照只恢复对应资源的 `spec`，不要恢复或替换 Redis Deployment。快照不可提交或打印。新 WASM 卷仍为临时卷，模拟 Pod 更换后需重新复制并验证制品。

## 显式初始化与真实网关验收

以下命令均会修改演示状态，必须明确添加 `--allow-mutations`，不会由启动或构建自动执行。只连接 `127.0.0.1:18080`、Host `quota-portal.local`，使用上述免费模拟路由。每次只运行一套验收，避免并发修改价格或窗口。

```bash
kubectl --context higress-dev -n higress-system port-forward service/higress-gateway 18080:80
# 另一个终端，根据安装策略选择：
backend/walmart-quota-tools/quota-tools verify-money --allow-mutations
backend/walmart-quota-tools/quota-tools verify-gateway --allow-mutations
# 仅在明确需要固定演示账户时执行：
backend/walmart-quota-tools/quota-tools seed-money --allow-mutations
backend/walmart-quota-tools/quota-tools seed-legacy --allow-mutations
```

金额验收暂时修改全局演示价格并在 finally 恢复。两套验收仅清除本演示的 Token 窗口键，并暂停专属 Redis 验证故障关闭，不清除配额账本。Seed 会重新配置固定用户并产生真实模拟消费，不要无意重复运行。

金额断言覆盖身份拒绝、个人优先、继承预算独立、整数输入输出计价、SSE、幂等及冲突、周期、部门模板变更、消费保留、耗尽、伪造字段、统一舍入、免费模型、请求处理中改价、错误价格保留、窗口、审计与 Redis 故障恢复。旧模式覆盖凭据缺失、有效凭据仅放查询参数仍被拒、冒充身份、管理权限、保留字段及票据、数值范围、模型白名单、付款方、双计数器、升级、SSE、耗尽与故障恢复。

包含失败结果和响应的脱敏报告分别写入 `docs/walmart-quota/evidence-money/gateway-report.json` 与 `docs/walmart-quota/evidence/cluster-report.json`。Maven 测试不执行真实网关验收。

## 本地模拟与测试

```bash
backend/walmart-quota-tools/quota-tools mock --port 8080
backend/walmart-quota-tools/quota-tools static --port 8090 --directory /tmp/quota-wasm
mvn -f backend/walmart-quota-tools/pom.xml test
```

`QuotaToolsTest` 覆盖首次私密安装、凭据与环境复用、权限、Java 部署清单与插件摘要、chunked JSON/SSE、身份头、错误 JSON、静态目录穿越与符号链接拒绝、缺失/不匹配凭据时阻止安装、独立模拟部署、registry/插件字段保留以及迁移后安装目标。直接运行方式为 `java -jar backend/walmart-quota-tools/target/walmart-quota-tools-1.0.0.jar COMMAND --root .`。

## 本次环境的镜像构建

Docker Hub 拉取 Java 8 镜像超时，本次复用集群已有的 Console Java 21 运行环境；工具本身仍编译为 Java 8 字节码。实际成功命令（在 Console 根目录）：

```bash
(cd backend/walmart-quota-tools && minikube -p higress-dev image build \
  -t walmart-quota-tools:local \
  --build-opt build-arg=JAVA_RUNTIME=higress-console/console:v2.2.4 .)
```
