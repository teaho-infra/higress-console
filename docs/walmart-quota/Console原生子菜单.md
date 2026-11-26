> 历史调试记录：独立 `portal/` 已移除，当前操作命令以 [README](README.md) 和 [Java 工具指南](java-tools.md) 为准。

# Console 原生子菜单

入口：<http://127.0.0.1:18001/walmart-quota/overview>。

展开 **Walmart 额度管理**：

| 子菜单 | URL |
|---|---|
| 用量总览 | `/walmart-quota/overview` |
| 用户与应用 | `/walmart-quota/accounts` |
| 部门模板 | `/walmart-quota/departments` |
| 档位配置 | `/walmart-quota/tiers` |
| 模型单价 | `/walmart-quota/prices` |
| 变更审计 | `/walmart-quota/audit` |

旧 `/walmart-quota` 自动跳转总览。Console 负责菜单和路由，业务内容直接在其页面中渲染；移除内部 Portal 导航和工作区栏。独立 Portal 兼容入口仍保留自己的导航。Console 页面复用 Console 登录认证，不再要求独立额度密码；接口和插件保留。

## 文件改动

- `frontend/src/custom/walmart-quota/menu.ts`：六个 Console 子菜单。
- 同目录 `ConsolePage.tsx`：连接 Console 路由与业务页面。
- 同目录 `index.jsx`：受控页面选择，关闭内部导航；保留刷新、导入、编辑、退出额度登录。
- 同目录 `style.css`：取消内层定高滚动，弹层高于 Console 固定顶栏。
- `frontend/src/pages/walmart-quota/*.tsx`：六个页面入口及旧入口重定向。
- `portal/tests/browser-console-navigation.mjs`：实际登录、六个菜单、刷新、选中态、前进后退、导入开关、移动端检查。
- 现有 `browser-console-*.mjs`：导航定位改为 Console 链接。

没有增加官方 Console 文件接入点，没有修改后端或限额插件。

## 调试命令

在仓库根目录执行：

```bash
npm --prefix frontend run build
mvn -f backend/pom.xml -P walmart-quota \
  -DskipTests -Dcheckstyle.skip=true -Dpmd.skip=true -Dgpg.skip=true \
  -Dmaven.javadoc.skip=true -Denforcer.skip=true \
  -Dskip.npm=true -Dskip.npx=true -Dskip.installnodenpm=true \
  -Dlombok.delombok.skip=true package
(cd backend && minikube -p higress-dev image build \
  -t higress-console/walmart-quota:20261125-menu-v2 \
  -f walmart-quota/Console.Dockerfile .)
kubectl --context higress-dev -n higress-system set image deployment/higress-console \
  higress-console=higress-console/walmart-quota:20261125-menu-v2
kubectl --context higress-dev -n higress-system rollout status deployment/higress-console
cd /home/teaho/IdeaProjects/teaho-infra/higress-console
node tests/browser-console-navigation.mjs
CONSOLE_TEST_URL=http://127.0.0.1:18001 node tests/browser-console-migration.mjs
```

第一轮浏览器检查发现固定顶栏遮挡导入关闭按钮，调整定制页面 stacking context 和弹层层级后复测。导航测试读取实际配置但不修改预算；CSV 测试拦截接口，不写入真实账户。

报告和截图：`evidence-console-navigation/`。执行日志：`.debug/java-migration/navigation*.log`。

复测结果：原生路由浏览器检查 10 项通过；模拟接口的 CSV、CSRF、样式隔离等检查 7 项通过。Console 前端、后端打包和独立 Portal 兼容构建均通过，集群滚动更新成功。
