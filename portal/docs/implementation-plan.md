# Higress 分档额度与 Portal 实施计划

来源：飞书《Token限额-Higress实现方案》，document_id EPyrdQFmAon34jxHrnMcJbIYn7g，revision 15。用户已要求实现和调试首版，本次直接在指定仓库执行，不发布远端。

## 设计与约定
- Higress 新增独立 ai-quota-walmart Go WASM 插件，版本 1.0.0-alpha；官方 ai-quota 不改。沿用官方 usage 解析和流式处理，未配置 dimensions 时保留官方接口模式。
- 用户 x-codebuddy-user-id 必须由可信应用/身份代理提供；应用来自 key-auth 生成的 x-mse-consumer。不能把公开客户端传入的用户头当独立认证。新演示采用 Key Auth 应用认证，不改变已有 JWT 路由。
- 按飞书规则：user 存在且用户档位 >= 应用档位，扣用户余额，否则扣应用余额；两个维度都统计。未分配个人档位回落应用，应用默认 L1。
- L1/L2/L3 示例基数 330000000 / 1000000000 / 2300000000 Token；不展示伪造人民币价格。
- 周期从 2026-01-05T00:00:00+08:00 开始，14 天，周期 ID 用开始日期 YYYY-MM-DD，结束为排他边界。纠正文档含混的 Wxx-A/B 示例。切周期与初始化、变更、幂等均用 Redis Lua 原子操作；按周期隔离账本，迟到响应记原周期。
- 升档保留已消费额，不重复赠送基数；临时升档/加量仅当前周期，持久基准档位独立配置。连续两周期低使用降档作为可核查周期任务实现或明确记录首版边界。
- 余额耗尽 403 / ai-quota-walmart.noquota；窗口限流 429；跨周期、冲突工单、禁止降档 409；Redis 故障 503，失败关闭。
- 首版仍是响应 usage 扣账，明确并发超额窗口；不声称严格预扣预算。
- Portal 独立 React + Vite，Node BFF，目录 higress-console/portal。BFF 从环境读取网关管理 Key，使用独立登录会话，管理密钥不返回前端；只允许固定管理路径和同源变更。
- 页面参考 Octafuse users/[id]/page.tsx、analytics/users/page.tsx、AuditWalletPlanBlock：左导航，周期摘要、三档卡片、用户/应用表、额度配置侧栏、实际用量条、审计前后值。保留中文语义，不复制其服务端逻辑。

## 实施与验证
- [x] 插件：测试先覆盖档位优先、周期边界、大整数、幂等与冲突；实现 Lua 原子账本与管理接口，编译 WASM。
- [x] Portal：API 认证/CSRF/白名单/错误处理测试；真实后台数据的档位配置、用户额度、用量和审计 UI，生产构建。
- [x] 独立本地测试路由：新 Redis、mock 模型、key-auth、统计、窗口限流、定制插件；不覆盖已有路由与余额。
- [x] 运行 HTTP 验收：403/429、双维度、升档立即生效、幂等、跨周期、重置不回赠、流式、故障；保存机器可读结果。
- [x] 浏览器实际操作与截图；记录命令、失败与修正、源码列表、启动/停止/回滚说明和限制。

## 管理接口契约（网关 /v1/chat/completions/quota 下）
统一 JSON {ok:true,...} 或 {ok:false,error:string,message:string}，POST 支持 JSON 和 form。管理员来自经过 key-auth 的 quota-admin。
- GET /meta => {ok,period,period_start,period_end,tiers:{L1:{quota,models},...}}
- GET /accounts?dimension=user|app => {ok,period,accounts:[{dimension,subject,app,tier,base_tier,quota,limit,usage,requests,period,...}]}，支持 limit/cursor 或 offset，数量上限有界。
- GET /?dimension=&subject= （/usage 同义查询）=> 账户快照；GET /audit => {ok,records:[{ticket_id,action,dimension,subject,operator,period,occurred_at,before,after}]}。
- POST /assign {dimension,subject,app?,tier,ticket_id,period}：配置持久基准档位/新账户，不重置使用量。
- POST /tier {dimension,subject,tier,ticket_id,period}：本周期升档，只升不降。
- POST /delta {dimension,subject,value,ticket_id,period}：本周期加量或扣减，整数。
- POST /refresh {dimension,subject,ticket_id,period}：幂等初始化当前周期，不回赠已消费余额。
- POST /tiers {tiers,ticket_id,period}：修改档位模板；必须定义现有账户影响并有审计（实现前协调）。
# 历史实现计划（已被新额度规则取代）

> 本文保留初版实现记录。当前规则见 [金额预算调试](金额预算调试.md)：仅 L1=500 元和 L2=1500 元；不再支持 L3 或周期内升档，项目临时额度使用经审批的单次 2000 元加量包。
