# 个人金额预算与部门档位兜底

用户已确认：个人配置优先；无个人配置继承所属部门 L1/L2/L3；每人独立额度；按模型金额计费。部门关系默认由 Portal 管理员维护（实现选择），不信任客户端部门头。保持已有 Key Auth 和可信用户头边界。

## 统一接口契约

- 新模式 `budget_policy: personal_department_money`，保留未设置此项时旧插件行为。独立 Redis 前缀 `wmt_portal_money_v2:`，不把历史 Token 余额当金额。
- 币种 CNY。存储单位微元：1 元=1,000,000 微元。`quota/limit/balance/spent/value` 都是微元整数；`usage` 仍为真实 Token。金额范围 0..9,999,999,999,999。
- 模型价格：每百万输入/输出 Token 的微元单价；合计费用=(input_tokens*input_price_micros+output_tokens*output_price_micros)/1,000,000，整笔向上取整至微元。Go big.Int 精确计算，避免 Lua 浮点乘法误差。
- 请求准入时固定模型单价，改价只影响新请求。未知模型价格拒绝；未识别到 usage 的处理沿用原插件并明确限制。不增加钱包、缓存专项价格或多币种。
- 14 天周期沿用原周期边界。所有用户有自己的 limit/spent/balance。耗尽403，不切换到部门/应用支付。应用仅汇总 Token 和 cost_micros，无共享预算。
- 部门档位是模板；部门修改只影响新初始化用户/下一周期。个人配置变更立即生效但保留已消费和临时加量。临时升档仅本周期。用户重新分配/改为继承时重新计算基数，保留已消费，不赠送重复余额。

继续使用 `/v1/chat/completions/quota` API，现有工单幂等/CSRF/权限/严格字段验证不削弱：
- GET meta: 原周期和tiers，加 `budget_policy`, `currency:"CNY"`, `amount_unit:"micro_yuan"`, `prices`。
- tiers: 仍 `L1/L2/L3:{quota,models}`，quota改为微元预算。示例100/300/1000元，明确仅演示。
- GET accounts: dimension=user/app/department。部门记录 `subject,tier`；不展示部门共享余额。
- POST assign: dimension=user,subject,department,tier,period,ticket_id；tier=L1/L2/L3 或 `inherit`。个人显式档位无需部门；inherit须存在有效部门。兼容 app 字段作为展示信息。
- POST assign: dimension=department,subject,tier,period,ticket_id。部门只允许 assign。
- user snapshot: department,base_tier（inherit或显式档位）,tier（本周期实际档位）,quota_source（personal/department/unconfigured）,limit,spent,balance,usage,input_tokens,output_tokens,cost_micros,requests,period,effective_dimension=user,effective_subject=subject。
- POST tier/delta/refresh: 仅用户；delta value微元。保留既有工单/period。
- GET prices 与 POST prices `{prices:{model:{input_price_micros,output_price_micros}},period,ticket_id}`，全量替换，允许空列表但模型请求会被拒绝。价格为0..9,999,999,999,999整数，模型名非空且<=256bytes。单价0是显式免费配置；不以缺失价格当免费。
- GET audit 记录配置、价格和预算变更。operator由认证身份产生。

## 页面与验收

保留现有页面结构，增加部门模板管理和模型价格页。用户表显示个人/部门来源、人民币预算/已消费/余额及真实Token。配置中明确“继承部门”，避免展示部门共用余额。周期预算卡参考用户截图，显示上限、已消费、余额、下次重置；周期仍固定双周，不展示未实现的无限额度/钱包控件。

执行分工：插件与精度/继承测试；Portal+BFF与浏览器测试；部署脚本和实际网关验收。验收覆盖个人L1优先于部门L3、同部门两用户余额隔离、耗尽不兜底扣款、不同模型/输入输出单价、缺价拒绝、修改价格不追溯、周期重置、幂等、越权、流式。调试证据新目录 docs/evidence-money，不覆盖首版。

实现裁定：GET不写账本，assign/refresh/首次有效请求初始化；assign结束原临时升档并保留消费和加量；即使免费模型，耗尽预算仍403；缺失用户401、未配置有效档位403。
