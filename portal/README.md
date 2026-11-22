# Higress 个人金额预算 Portal

当前版本：**个人配置优先，部门档位兜底，每人独立预算，按模型金额计费**。新增 `ai-quota-walmart` WASM 插件，无需修改网关镜像。

访问 http://127.0.0.1:18890/ ，管理员密码在本目录 `.env` 的 `PORTAL_PASSWORD`。Portal 是独立页面，未嵌入原 Console 导航。

## 规则

1. 用户有个人档位就使用个人档位，即使个人 L1 低于部门 L3。
2. 用户选择“继承部门”时，部门档位作为每个人的预算模板。A 消费不会减少 B 的余额。
3. 额度耗尽返回 403，不转扣部门或应用。应用仅汇总用量和费用。
4. 每个模型配置输入、输出每百万 Token 的人民币单价；Token 统计和人民币费用分开。未配置价格的模型拒绝调用，价格为 0 才表示显式免费。
5. 周期沿用 14 天。个人配置、部门关系持久保存；本周期升档与加量到期失效。周期初始化不会重复赠送预算。

部门关系由管理员在 Portal 维护。个人身份仍来自可信应用提供的 `x-codebuddy-user-id`，应用由 Key Auth 认证。不要将可任意伪造用户头的入口直接开放给不可信客户端；这里没有新增用户登录或 OIDC 映射。

## 操作

- **部门档位**：创建部门，选择默认 L1/L2/L3。部门是模板，没有共享余额。
- **用户预算**：创建用户并选所属部门；选择“继承部门”，或显式指定个人档位。
- **预算调整**：查看上限、已消费、剩余预算和下次重置；可临时升档、追加/扣减金额。
- **模型价格**：填写模型 ID、输入/输出单价，单位为元/百万 Token。最多 6 位小数，不静默四舍五入。
- **审计**：配置和价格变更都有工单记录。同工单重试不重复加钱，不同内容必须换工单号。

部门模板与全局档位模板的修改只影响新初始化账户及下一周期。修改个人基准配置立即重算基数，保留已消费与临时加量，并结束原临时升档。已开始的请求按准入时单价结算，改价不追溯消费。

## 批量导入个人预算

在“个人预算与应用统计”点击 **批量导入** → 下载 CSV 模板 → 选择文件 → 校验预览 → 确认导入。

```csv
用户标识,所属部门,档位
user-001,研发部,inherit
user-002,,L1
```

支持 CSV UTF-8（Excel 另存为此格式），最多 200 条、1 MB。部门须先创建；档位留空或 `inherit` 表示继承部门，显式 L1/L2/L3 优先。用户标识按文本读取，保留前导零；Excel 编辑时也应把该列设为文本。

导入会新增或更新个人配置，保留已消费和临时加量，但替换基准档位并结束原临时升档。逐行生效，不是整批事务。结果显示每行成功/失败，支持只重试失败及未完成项、下载 JSON 结果与工单。同一弹窗内重试沿用原工单；请勿在处理中刷新页面。应用用量仍由模型请求产生，不导入统计数据。

[导入调试记录](docs/批量导入调试.md)

## 模拟环境

当前独立路由 `Host: quota-portal.local` 使用本地模拟模型，不产生付费模型调用。

| 示例 | 配置 |
|---|---|
| L1 / L2 / L3 | 每人每周期 ￥100 / ￥300 / ￥1,000 |
| demo-model | 输入 ￥2、输出 ￥8 / 百万 Token |
| demo-model-pro | 输入 ￥10、输出 ￥40 / 百万 Token |
| user-alice | 个人 L1，所属研发部默认 L2 |
| user-bob | 继承研发部 L2，个人独立余额 |
| user-carol | 继承产品部 L3，个人独立余额 |

**这些价格和预算仅用于调试，不是真实模型报价。** 模拟模型每次返回输入 10、输出 20 Token，因此两种模型分别扣 ￥0.000180 和 ￥0.000900。

## 启动与复现

在 `higress-console/portal` 执行，要求 Node >=22、Go 1.26、Python3 和已连接 `higress-dev` 的 kubectl。

```bash
npm ci --registry=https://registry.npmjs.org
npm run build
(cd ../../higress/plugins/wasm-go/extensions/ai-quota-walmart && \
 GOTOOLCHAIN=go1.26.0 GOMODCACHE=/tmp/walmart-quota-gomod \
 GOOS=wasip1 GOARCH=wasm go build -buildmode=c-shared -o plugin.wasm .)
python3 scripts/deploy_demo.py prepare
kubectl --context higress-dev -n higress-system rollout status \
 deployment/walmart-quota-portal --timeout=60s
python3 scripts/deploy_demo.py install \
 --wasm ../../higress/plugins/wasm-go/extensions/ai-quota-walmart/plugin.wasm
# 等待控制面下发，再执行：
python3 scripts/verify_money.py
python3 scripts/seed_money.py
npm start
```

若 18080 未转发，在另一个终端运行：

```bash
kubectl --context higress-dev -n higress-system port-forward service/higress-gateway 18080:80
```

`prepare` 复用私密凭据，不要删除 `.debug/cluster/credentials.json` 后直接覆盖已有资源。默认安装配置是 `scripts/money-config.json`，旧 Token 配置仍在 `scripts/quota-config.json`，可用 `install --config scripts/quota-config.json --wasm ...` 明确选择。当前 Portal 以金额模式为目标；旧模式回退需配套旧页面版本。

金额账本前缀 `wmt_portal_money_v2:`，与旧 Token 账本 `wmt_portal_demo:` 隔离，没有将历史 Token 数字转换成人民币。

## 精度与接口

存储单位是**微元**，1 元 = 1,000,000 微元。API 的 quota、limit、balance、spent、delta.value 均为微元整数，usage/input_tokens/output_tokens 是实际 Token。

```
费用微元 = 向上取整((输入Token × 输入单价微元 + 输出Token × 输出单价微元) / 1,000,000)
```

两项相加后只取整一次，Go 大整数计算避免浮点乘法误差。价格单位为每百万 Token 的微元。

沿用网关 `/v1/chat/completions/quota` 管理入口，仅 `quota-admin` consumer 可访问。浏览器通过有登录会话及 CSRF 校验的 `/api/quota/*` 代理，管理 Key 不下发前端。

| 接口 | 方法 | 内容 |
|---|---|---|
| meta | GET | 周期、币种、档位、模型价格 |
| accounts?dimension=user/app/department | GET | 用户账本、应用汇总或部门模板 |
| ?dimension=user&subject=... | GET | 单用户快照 |
| assign | POST | 用户个人/继承配置，或部门档位 |
| tier / delta / refresh | POST | 用户临时升档、加量、幂等初始化 |
| tiers | POST | 未来初始化使用的三档预算模板 |
| prices | GET/POST | 模型输入/输出单价，全量替换 |
| audit | GET | 配置变更审计 |

POST 必须带当前 period 和唯一 ticket_id。`operator`、内部结算金额和时间边界不可由客户端指定。具体请求示例见 [金额版实现与调试](docs/金额预算调试.md)。

## 限制

- 按响应 usage 后扣账，仍有并发超额窗口；无预扣与持久化结算重试。不提供钱包透支/充值、多币种、缓存专项价格、自动低用量降档或真实审批流。
- 本版固定双周，不提供截图中的自定义重置周期或无限额度。部门改档不批量改写当前用户账本。
- 演示 Redis 未持久化；WASM 在 Pod emptyDir，重启 Pod 后需重新 install。生产需要持久化与可靠制品分发。
- 单值上限 9,999,999,999,999 微元/Token，独立 Redis，不支持 Redis Cluster 跨 slot。审计最近 10,000 条；历史清理需单独策略。
- 页面列表有加载上限，完整数据可用分页 API 获取。

新版验证保存在 [evidence-money](docs/evidence-money)，旧版验证保存在 [evidence](docs/evidence)，不要混用两版测试结果。
