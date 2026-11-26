<!-- Portal deployment/verification commands migrated to Java; historical external plugin Python tests below are unchanged. -->
# 独立网关集成验证

工作目录 `/home/teaho/IdeaProjects/teaho-infra/higress-console`。

```sh
backend/walmart-quota-tools/quota-tools prepare
kubectl --context higress-dev -n higress-system rollout status deployment/walmart-quota-portal --timeout=60s
backend/walmart-quota-tools/quota-tools install --wasm /home/teaho/IdeaProjects/teaho-infra/higress/plugins/wasm-go/extensions/ai-quota-walmart/plugin.wasm
backend/walmart-quota-tools/quota-tools verify-gateway --allow-mutations
backend/walmart-quota-tools/quota-tools seed-legacy --allow-mutations
```

首次 apply 后需要等待控制面传播（约 10 秒），再运行验收。`prepare` 写入独立 Deployment、Service、ConfigMap、Secret、两个 Ingress、四个官方插件；`install` 仅写入专属 WASM 插件。现存 Higress gateway/controller/Console 部署、镜像、JWT 路由、Minimax 路由、其他 Redis 未更改。网关为现有 2.2.4，使用现有本地 18080 转发。若转发关闭：

```sh
kubectl --context higress-dev -n higress-system port-forward service/higress-gateway 18080:80
```

所有资源名以 `walmart-quota-portal` 开头，主机为 `quota-portal.local`。模型为本地 Python 模拟服务，每次真实 HTTP 请求返回 usage=30（10 输入、20 输出），支持 JSON/SSE 和 chunked 请求。没有付费模型调用。独立 Redis 有随机密码，演示数据未开启持久化。私密部署 JSON/Key 放在 `.walmart-quota/.debug/cluster/`，BFF `.env` 为 0600。请勿提交这些文件。

标准链为 transformer(AUTHN410，移除客户端 x-mse-consumer) → KeyAuth(AUTHN310，可信应用身份) → ai-statistics900 → ai-quota-walmart750 → ai-token-ratelimit600（仅 /limited 路由）。主路由 `/v1/chat/completions` 以及其 `/quota` 管理子路径；窗口测试路由 `/limited/v1/chat/completions`。KeyAuth 仅接受 x-api-key 请求头；密钥在转发上游前剥离。`x-codebuddy-user-id` 是可信应用提供的用户标识，演示没有独立用户身份认证；不应直接暴露给不可信公共客户端。

`cluster-report.json` 是实际网关请求的脱敏机器证据，包括 Redis 暂停 3.5 秒时 503 失败关闭和恢复。验证只清理专属 Redis 中的窗口键，不清理额度账本。随机测试用户避免覆盖其他测试；固定演示用户 user-alice、user-bob、user-carol 来自 seed-legacy，分别 L2/L1/L3，每次种子运行发送 3 次真实模拟请求。应用为 demo-app 和 second-app。

调试修正：首次安装立即验证时插件尚未传播，因此管理请求曾到达 mock；等待传播后正常。模型最初仅读取 Content-Length，无法读取 WASM buffer 后转发的 chunked 请求，已修正。一次 query-key 负例用了有效应用 key，随后立即轮换该 key，负例改为无效占位值；管理 key 不曾输出。

WASM 通过专属 Pod 的 Python 静态服务交付，文件名为 SHA256。文件存储在 emptyDir，Pod 重启后必须重新运行 install；无需外部发布。源 WASM 变动也须重新 install。Redis 重启将失去此演示数据；生产应使用持久化与备份。响应 usage 后扣账仍有并发超额窗口；本集成测试不宣称严格预扣。

仅回滚本演示：

```sh
kubectl --context higress-dev -n higress-system delete wasmplugin,ingress,service,deployment,secret,configmap -l app=walmart-quota-portal
```

不要删除现有 gateway/controller，不要执行共享 Redis 的 FLUSHDB。

安全复核后的验收新增：GET query 的 action 覆盖、内部 settle 路径、伪造 period_start_unix、畸形 app 对象、超范围 delta/档位模板、缺失/空值/非字符串/不允许的 model。无效管理请求需保持余额不变，并允许同工单号随后执行合法请求，验证失败请求没有占用幂等键。最终证据以安全修正后的 WASM 重跑结果为准；runtime 中 SHA256 对应最终安装文件。
