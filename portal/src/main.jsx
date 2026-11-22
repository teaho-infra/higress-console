import React, { useEffect, useState, useMemo } from "react";
import { createRoot } from "react-dom/client";
import { request, quota } from "./api";
import "./style.css";
import BulkImport from "./BulkImport";
import { toMicros, fromMicros, money } from "./money.mjs";
const nf = new Intl.NumberFormat("zh-CN");
const count = (n) => nf.format(Number(n) || 0);
const compact = (n) => {
  n = Number(n) || 0;
  return n >= 1e8
    ? (n / 1e8).toFixed(2) + " 亿"
    : n >= 1e4
    ? (n / 1e4).toFixed(2) + " 万"
    : count(n);
};
const date = (v) =>
  v
    ? new Date(
        typeof v === "number" ? (v < 1e12 ? v * 1000 : v) : v
      ).toLocaleString("zh-CN", { hour12: false })
    : "—";
const paths = {
  grid: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
  users:
    "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M17 4a4 4 0 0 1 0 7 M22 21v-2a4 4 0 0 0-3-3.87",
  layers: "m12 3 10 6-10 6L2 9z M2 15l10 6 10-6 M2 12l10 6 10-6",
  chart: "M4 3v18h17 M8 16v-5 M13 16V7 M18 16v-8",
  history: "M3 12a9 9 0 1 0 3-7 M3 3v5h5 M12 7v5l3 2",
  arrow: "M5 12h14 M13 6l6 6-6 6",
  refresh:
    "M20 7a9 9 0 0 0-15-2L2 8 M2 3v5h5 M4 17a9 9 0 0 0 15 2l3-3 M22 21v-5h-5",
  plus: "M12 5v14 M5 12h14",
  close: "m6 6 12 12 M6 18 18 6",
  search: "M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0",
  shield: "m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6z m-4 9 3 3 5-6",
  logout: "M9 4H4v16h5 M9 12h12 m-5-5 5 5-5 5",
  chevron: "m9 5 7 7-7 7",
};
function Icon({ name, size = 18 }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={paths[name] || paths.grid} />
    </svg>
  );
}
function Badge({ tier }) {
  return (
    <span className={"badge " + (tier || "inherit")}>
      {tier === "inherit" ? "继承部门" : tier || "未配置"}
    </span>
  );
}
function Progress({ used = 0, total = 0 }) {
  const pct = total > 0 ? Math.min(100, Math.max(0, (used / total) * 100)) : 0;
  return (
    <div
      className={
        "progress " + (pct >= 100 ? "danger" : pct >= 80 ? "warn" : "")
      }
      role="progressbar"
      aria-valuenow={Math.round(pct)}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-label="额度使用率"
    >
      <span style={{ width: pct + "%" }} />
    </div>
  );
}
function Empty({ title = "暂无账户", children }) {
  return (
    <div className="empty">
      <Icon name="layers" size={32} />
      <h3>{title}</h3>
      <p>{children || "配置个人档位，或创建部门模板供用户继承。"}</p>
    </div>
  );
}
function Login({ onLogin }) {
  const [password, setPassword] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  return (
    <div className="login">
      <div className="login-brand">
        <div className="brandmark">H</div>
        <strong>
          Higress <span>PORTAL</span>
        </strong>
      </div>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError("");
          try {
            await request("login", "POST", { password });
            onLogin();
          } catch (e) {
            setError(e.message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <div className="eyebrow">AI GATEWAY / GOVERNANCE</div>
        <h1>
          让每一份额度
          <br />
          都有清晰的去向。
        </h1>
        <p>统一配置个人预算与部门默认档位，查看实际用量和变更记录。</p>
        <label>
          管理员密码
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            autoFocus
          />
        </label>
        {error && (
          <div role="alert" className="error">
            {error}
          </div>
        )}
        <button className="primary" disabled={busy}>
          {busy ? "正在登录…" : "进入额度管理"}
          <Icon name="arrow" />
        </button>
        <small>仅授权管理员可调整额度。所有变更保留审计记录。</small>
      </form>
      <div className="login-foot">HIGRESS · TOKEN QUOTA PORTAL</div>
    </div>
  );
}
function App() {
  const [authenticated, setAuthenticated] = useState(null),
    [page, setPage] = useState("overview"),
    [meta, setMeta] = useState(null),
    [users, setUsers] = useState([]),
    [apps, setApps] = useState([]),
    [departments, setDepartments] = useState([]),
    [audit, setAudit] = useState([]),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [drawer, setDrawer] = useState(null),
    [tierEditor, setTierEditor] = useState(false),
    [importOpen, setImportOpen] = useState(false),
    [search, setSearch] = useState(""),
    [dim, setDim] = useState("user"),
    [updated, setUpdated] = useState(null),
    [truncated, setTruncated] = useState(false);
  useEffect(() => {
    request("session")
      .then(() => setAuthenticated(true))
      .catch(() => setAuthenticated(false));
  }, []);
  async function load() {
    setBusy(true);
    setError("");
    try {
      const [m, u, a, h, d] = await Promise.all([
        quota("meta"),
        quota("accounts?dimension=user&limit=200"),
        quota("accounts?dimension=app&limit=200"),
        quota("audit?limit=100"),
        quota("accounts?dimension=department&limit=200"),
      ]);
      setMeta(m);
      setDepartments(d.accounts || []);
      setUsers(u.accounts || []);
      setApps(a.accounts || []);
      setAudit(h.records || []);
      setTruncated(
        Boolean(
          u.next_cursor ||
            a.next_cursor ||
            (u.accounts || []).length >= 200 ||
            (a.accounts || []).length >= 200 ||
            (d.accounts || []).length >= 200
        )
      );
      setUpdated(new Date());
    } catch (e) {
      setError(e.message);
      if (e.status === 401) setAuthenticated(false);
    } finally {
      setBusy(false);
    }
  }
  useEffect(() => {
    if (authenticated) load();
  }, [authenticated]);
  const rows = dim === "user" ? users : apps;
  const visible = rows.filter((a) =>
    (a.subject + " " + (a.department || a.app || ""))
      .toLowerCase()
      .includes(search.toLowerCase())
  );
  const total = users.reduce((s, a) => s + Number(a.usage || 0), 0),
    requests = users.reduce((s, a) => s + Number(a.requests || 0), 0);
  const titles = {
    overview: ["用量总览", "每人独立预算，按模型输入和输出 Token 单价计费。"],
    accounts: [
      "个人预算与应用统计",
      "配置基准档位，管理本周期升档与临时加量。",
    ],
    tiers: ["档位配置", "统一管理 L1、L2、L3 的 人民币预算与模型范围。"],
    departments: ["部门模板", "部门仅提供默认档位，每位成员拥有独立预算。"],
    prices: ["模型单价", "配置每百万输入、输出 Token 的人民币单价。"],
    audit: ["变更审计", "每次额度调整都有工单、操作人和前后状态。"],
  };
  async function saved() {
    setDrawer(null);
    setTierEditor(false);
    setNotice("配置已保存，网关即时生效。");
    await load();
  }
  if (authenticated === null)
    return <div className="boot">正在连接额度管理服务…</div>;
  if (!authenticated) return <Login onLogin={() => setAuthenticated(true)} />;
  return (
    <div className="app">
      <aside className="sidebar">
        <div className="logo">
          <div className="brandmark">H</div>
          <strong>
            Higress<span>QUOTA PORTAL</span>
          </strong>
        </div>
        <div className="nav-label">AI 网关治理</div>
        <nav>
          {[
            ["overview", "grid", "用量总览"],
            ["accounts", "users", "用户与应用"],
            ["departments", "users", "部门模板"],
            ["tiers", "layers", "档位配置"],
            ["prices", "chart", "模型单价"],
            ["audit", "history", "变更审计"],
          ].map(([id, icon, label]) => (
            <button
              key={id}
              className={page === id ? "active" : ""}
              onClick={() => {
                setPage(id);
                setSearch("");
              }}
            >
              <Icon name={icon} />
              {label}
              {page === id && <span className="nav-dot" />}
            </button>
          ))}
        </nav>
        <div className="sidebar-note">
          <Icon name="shield" />
          <strong>个人独立预算</strong>
          <p>个人档位优先，未配置时继承部门档位。每人独立扣费。</p>
        </div>
        <div className="sidebar-user">
          <div className="avatar">A</div>
          <div>
            额度管理员<small>portal-admin</small>
          </div>
          <button
            title="退出登录"
            aria-label="退出登录"
            onClick={async () => {
              await request("logout", "POST", {});
              setAuthenticated(false);
            }}
          >
            <Icon name="logout" />
          </button>
        </div>
      </aside>
      <main>
        <header className="topbar">
          <span>
            工作空间 <Icon name="chevron" size={12} /> <strong>额度管理</strong>
          </span>
          <div>
            <span className={"status-dot " + (error ? "bad" : "")} />
            {error ? "连接异常" : "管理服务已连接"}
            <span className="sep" />
            人民币预算 / 实际 Token 计量
          </div>
        </header>
        <div className="content">
          <div className="page-heading">
            <div>
              <div className="eyebrow">TOKEN GOVERNANCE</div>
              <h1>{titles[page][0]}</h1>
              <p>{titles[page][1]}</p>
            </div>
            <div className="heading-actions">
              <button className="secondary" onClick={load} disabled={busy}>
                <Icon name="refresh" />
                {busy ? "刷新中…" : "刷新数据"}
              </button>
              {page === "accounts" && (
                <button
                  className="secondary"
                  disabled={!meta}
                  onClick={() => setImportOpen(true)}
                >
                  批量导入
                </button>
              )}
              {!["audit", "prices"].includes(page) && (
                <button
                  className="primary"
                  disabled={!meta}
                  onClick={() =>
                    page === "tiers"
                      ? setTierEditor(true)
                      : setDrawer({
                          dimension:
                            page === "departments" ? "department" : "user",
                          subject: "",
                          app: "",
                          base_tier: page === "departments" ? "L1" : "inherit",
                          fresh: true,
                        })
                  }
                >
                  <Icon name="plus" />
                  {page === "tiers" ? "编辑档位模板" : "配置额度"}
                </button>
              )}
            </div>
          </div>
          {error && (
            <div role="alert" className="error">
              <strong>数据读取失败：</strong>
              {error}
              <button onClick={load}>重试</button>
            </div>
          )}
          {notice && (
            <div role="status" className="success">
              {notice}
              <button aria-label="关闭提示" onClick={() => setNotice("")}>
                <Icon name="close" size={14} />
              </button>
            </div>
          )}
          <section className="period-banner">
            <div className="period-icon">
              <Icon name="history" size={22} />
            </div>
            <div>
              <strong>当前双周周期</strong>
              <span>
                {meta
                  ? `${date(meta.period_start)} — ${date(meta.period_end)}`
                  : "正在读取周期…"}
              </span>
            </div>
            <span className="period-tag">14 天 / 自动重置</span>
            <div className="period-id">
              周期编号<strong>{meta?.period || "—"}</strong>
            </div>
          </section>
          {truncated && (
            <div className="info">
              当前页面显示每维度前 200
              个账户；汇总仅覆盖这些账户。完整数据可通过管理 API 分页查询。
            </div>
          )}
          {page === "overview" && (
            <>
              <div className="metrics">
                <Metric
                  title="本周期 Token 用量"
                  value={compact(total)}
                  detail="按用户汇总，应用统计不重复计入"
                  icon="chart"
                />
                <Metric
                  title="已计量请求"
                  value={count(requests)}
                  detail="以网关完成 usage 记账为准"
                  icon="arrow"
                />
                <Metric
                  title="用户账户"
                  value={count(users.length)}
                  detail={`${
                    users.filter((x) =>
                      ["personal", "department"].includes(x.quota_source)
                    ).length
                  } 个已配置预算`}
                  icon="users"
                />
                <Metric
                  title="本周期费用"
                  value={money(
                    users.reduce(
                      (sum, user) => sum + (user.cost_micros || 0),
                      0
                    )
                  )}
                  detail={`${apps.length} 个应用，按用户汇总避免重复计费`}
                  icon="layers"
                />
              </div>
              <p className="muted">
                演示预算模板；生产使用前请按组织政策调整。
              </p>
              <TierCards tiers={meta?.tiers} onEdit={() => setPage("tiers")} />
              <div className="section-heading">
                <div>
                  <h2>额度使用情况</h2>
                  <p>查看个人预算、已消费金额与剩余金额。</p>
                </div>
                <button
                  className="text-button"
                  onClick={() => setPage("accounts")}
                >
                  管理全部账户 <Icon name="arrow" size={15} />
                </button>
              </div>
              <AccountTable
                rows={visible}
                dim={dim}
                setDim={setDim}
                search={search}
                setSearch={setSearch}
                onEdit={setDrawer}
                limited
              />
            </>
          )}
          {page === "accounts" && (
            <>
              <AccountTable
                rows={visible}
                dim={dim}
                setDim={setDim}
                search={search}
                setSearch={setSearch}
                onEdit={setDrawer}
              />
              <div className="info">
                <Icon name="shield" />{" "}
                每人独立扣费，个人预算耗尽即拒绝请求。应用仅汇总统计，不能与用户用量相加。
              </div>
            </>
          )}
          {page === "tiers" && (
            <>
              <TierCards
                tiers={meta?.tiers}
                onEdit={() => setTierEditor(true)}
              />
              <section className="panel policy">
                <div>
                  <div className="eyebrow">EFFECTIVE QUOTA</div>
                  <h2>个人优先，部门提供默认档位</h2>
                  <p>
                    个人显式档位始终优先；继承部门的用户根据部门模板获得独立预算。部门不承担共享扣费。
                  </p>
                </div>
                <div className="policy-flow">
                  <div>
                    个人 L2 + 部门 L1
                    <strong>
                      个人 L2 额度 <Icon name="arrow" />
                    </strong>
                  </div>
                  <div>
                    个人 L1 + 部门 L3
                    <strong>
                      个人 L1 额度 <Icon name="arrow" />
                    </strong>
                  </div>
                  <div>
                    未分配个人档位
                    <strong>
                      继承部门档位，个人独立预算 <Icon name="arrow" />
                    </strong>
                  </div>
                </div>
              </section>
              <div className="info">
                档位模板用于新账户及新周期初始化，当前已初始化账户不会被批量重置。周期内调整请在个人预算页面执行升档或加量。
              </div>
            </>
          )}
          {page === "departments" && (
            <section className="panel">
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>部门</th>
                      <th>默认档位</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {departments.map((d) => (
                      <tr key={d.subject}>
                        <td>{d.subject}</td>
                        <td>
                          <Badge tier={d.tier} />
                        </td>
                        <td>
                          <button
                            className="text-button"
                            onClick={() =>
                              setDrawer({ ...d, dimension: "department" })
                            }
                          >
                            配置 {d.subject}
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="info">
                修改部门模板影响新初始化用户或下一周期，不重置成员已消费金额。
              </p>
            </section>
          )}
          {page === "prices" && meta && (
            <PriceEditor meta={meta} onSaved={saved} />
          )}
          {page === "audit" && <AuditTable records={audit} />}
          <footer>
            <span>Higress · 分档额度管理</span>
            <span>
              {updated
                ? "更新于 " + updated.toLocaleTimeString("zh-CN")
                : "尚未加载数据"}{" "}
              · 周期结束不结转临时额度
            </span>
          </footer>
        </div>
      </main>
      {importOpen && (
        <BulkImport onClose={() => setImportOpen(false)} onChanged={load} />
      )}
      {drawer && (
        <AccountEditor
          account={drawer}
          departments={departments}
          meta={meta}
          onClose={() => setDrawer(null)}
          onSaved={saved}
        />
      )}
      {tierEditor && (
        <TierEditor
          meta={meta}
          onClose={() => setTierEditor(false)}
          onSaved={saved}
        />
      )}
    </div>
  );
}
function PriceEditor({ meta, onSaved }) {
  const [rows, setRows] = useState(() =>
    Object.entries(meta.prices || {}).map(([model, p]) => ({
      model,
      input: fromMicros(p.input_price_micros),
      output: fromMicros(p.output_price_micros),
    }))
  );
  const [ticket, setTicket] = useState("PRICES-" + crypto.randomUUID()),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const update = (i, k, v) =>
    setRows(rows.map((r, j) => (j === i ? { ...r, [k]: v } : r)));
  return (
    <section className="panel">
      <form
        className="editor"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError("");
          try {
            const prices = Object.create(null);
            for (const r of rows) {
              const model = r.model.trim();
              if (
                !model ||
                new TextEncoder().encode(model).length > 256 ||
                Object.hasOwn(prices, model)
              )
                throw new Error("模型名称不能为空、重复或超过 256 字节");
              prices[model] = {
                input_price_micros: toMicros(r.input),
                output_price_micros: toMicros(r.output),
              };
            }
            await quota("prices", {
              prices,
              period: meta.period,
              ticket_id: ticket,
            });
            setTicket("PRICES-" + crypto.randomUUID());
            await onSaved();
          } catch (e) {
            setError(e.message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <p>
          演示环境价格仅用于验证，不代表供应商实际报价。单位：元 / 百万
          Token。零单价表示免费；未配置单价的模型拒绝请求。修改仅影响新请求。
        </p>
        {rows.map((r, i) => (
          <div className="price-row" key={i}>
            <label>
              模型名称
              <input
                required
                value={r.model}
                onChange={(e) => update(i, "model", e.target.value)}
              />
            </label>
            <label>
              输入单价
              <input
                required
                inputMode="decimal"
                value={r.input}
                onChange={(e) => update(i, "input", e.target.value)}
              />
            </label>
            <label>
              输出单价
              <input
                required
                inputMode="decimal"
                value={r.output}
                onChange={(e) => update(i, "output", e.target.value)}
              />
            </label>
            <button
              type="button"
              className="secondary"
              aria-label={"删除模型 " + r.model}
              onClick={() => setRows(rows.filter((_, j) => j !== i))}
            >
              删除
            </button>
          </div>
        ))}
        <button
          type="button"
          className="secondary"
          onClick={() =>
            setRows([...rows, { model: "", input: "0", output: "0" }])
          }
        >
          添加模型
        </button>
        {!rows.length && (
          <p className="info">
            尚未配置价格。保存空列表后所有模型请求都会被拒绝。
          </p>
        )}
        <label>
          价格变更工单号
          <input
            required
            maxLength={128}
            value={ticket}
            onChange={(e) => setTicket(e.target.value)}
          />
        </label>
        {error && (
          <div className="error" role="alert">
            {error}
          </div>
        )}
        <button className="primary" disabled={busy}>
          {busy ? "正在保存…" : "保存模型单价"}
        </button>
      </form>
    </section>
  );
}

function Metric({ title, value, detail, icon }) {
  return (
    <section className="metric">
      <div>
        {title}
        <Icon name={icon} />
      </div>
      <strong>{value}</strong>
      <small>{detail}</small>
    </section>
  );
}
function TierCards({ tiers, onEdit }) {
  return (
    <div className="tier-cards">
      {["L1", "L2", "L3"].map((key, i) => (
        <section className={"tier-card tier-" + key} key={key}>
          <div>
            <Badge tier={key} />
            <span>{["日常使用", "高频协作", "项目攻坚"][i]}</span>
            <button aria-label={"编辑 " + key + " 档位"} onClick={onEdit}>
              <Icon name="arrow" size={16} />
            </button>
          </div>
          <h2>
            {tiers?.[key] ? money(tiers[key].quota) : "—"}{" "}
            <small>元 / 双周</small>
          </h2>
          <p>
            可用模型：
            {tiers?.[key] &&
            (!tiers[key].models?.length || tiers[key].models.includes("*"))
              ? "全部模型"
              : tiers?.[key]?.models?.join("、") || "—"}
          </p>
        </section>
      ))}
    </div>
  );
}
function AccountTable({
  rows,
  dim,
  setDim,
  search,
  setSearch,
  onEdit,
  limited,
}) {
  return (
    <section className="panel">
      <div className="table-toolbar">
        <div className="tabs">
          {[
            ["user", "个人预算"],
            ["app", "应用统计"],
          ].map(([id, label]) => (
            <button
              key={id}
              className={dim === id ? "selected" : ""}
              onClick={() => setDim(id)}
            >
              {label}
            </button>
          ))}
        </div>
        <div className="search">
          <Icon name="search" />
          <input
            aria-label="搜索账户"
            placeholder="搜索主体或部门"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
      </div>
      {!rows.length ? (
        <Empty />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>主体 / 部门</th>
                <th>{dim === "user" ? "预算来源 / 档位" : "统计范围"}</th>
                <th>实际 Token</th>
                <th>已消费金额</th>
                {dim === "user" && (
                  <>
                    <th>个人余额 / 上限</th>
                    <th>操作</th>
                  </>
                )}
              </tr>
            </thead>
            <tbody>
              {rows.slice(0, limited ? 6 : rows.length).map((a) => (
                <tr key={a.subject}>
                  <td>
                    <strong>{a.subject}</strong>
                    <small className="muted block">
                      {a.department || a.app || "—"}
                    </small>
                  </td>
                  <td>
                    {dim === "user" ? (
                      <>
                        <Badge tier={a.tier} />
                        <small className="muted block">
                          {{
                            personal: "个人配置",
                            department: "继承部门",
                            unconfigured: "未配置",
                          }[a.quota_source] || "未配置"}
                        </small>
                      </>
                    ) : (
                      "仅统计，无共享预算"
                    )}
                  </td>
                  <td>
                    {count(a.usage)}
                    <small className="muted block">
                      输入 {count(a.input_tokens)} / 输出{" "}
                      {count(a.output_tokens)}
                    </small>
                  </td>
                  <td>{money(dim === "app" ? a.cost_micros : a.spent)}</td>
                  {dim === "user" && (
                    <>
                      <td className="quota-cell">
                        <div>
                          <strong>{money(a.balance)}</strong>
                          <span>/ {money(a.limit)}</span>
                        </div>
                        <Progress used={a.spent} total={a.limit} />
                      </td>
                      <td>
                        <button
                          className="text-button"
                          aria-label={"配置 " + a.subject}
                          onClick={() => onEdit({ ...a, dimension: "user" })}
                        >
                          配置
                        </button>
                      </td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="table-foot">
        {rows.length} 个账户<span>人民币 / Token 来自实际响应 usage</span>
      </div>
    </section>
  );
}
function Modal({ title, subtitle, onClose, children }) {
  useEffect(() => {
    const h = (e) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, []);
  return (
    <div className="backdrop" onClick={onClose}>
      <section
        className="drawer"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(e) => e.stopPropagation()}
      >
        <header>
          <div>
            <h2>{title}</h2>
            <p>{subtitle}</p>
          </div>
          <button
            className="icon-button"
            aria-label="关闭配置"
            onClick={onClose}
          >
            <Icon name="close" />
          </button>
        </header>
        {children}
      </section>
    </div>
  );
}
function AccountEditor({ account, departments, meta, onClose, onSaved }) {
  const [action, setAction] = useState("assign"),
    [dimension, setDimension] = useState(account.dimension || "user"),
    [subject, setSubject] = useState(account.subject || ""),
    [department, setDepartment] = useState(account.department || ""),
    [tier, setTier] = useState(
      account.base_tier ||
        account.tier ||
        (account.dimension === "department" ? "L1" : "inherit")
    ),
    [value, setValue] = useState("10"),
    [ticket, setTicket] = useState("PORTAL-" + crypto.randomUUID()),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const actions = [
    ["assign", "基准配置"],
    ["tier", "本周期升档"],
    ["delta", "临时加量"],
    ["refresh", "周期初始化"],
  ];
  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const data = {
        dimension,
        subject: subject.trim(),
        period: meta.period,
        ticket_id: ticket.trim(),
      };
      if (action === "assign" || action === "tier") data.tier = tier;
      if (action === "assign" && dimension === "user") {
        data.department = department;
        if (tier === "inherit" && !department)
          throw new Error("继承部门时请选择有效部门");
      }
      if (action === "delta") {
        const n = toMicros(value, { signed: true });
        if (!n) throw new Error("加量不能为零");
        data.value = n;
      }
      await quota(action, data);
      await onSaved();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      title={account.fresh ? "配置新账户" : "额度配置 · " + account.subject}
      subtitle="调整即时生效，变更保留工单与审计记录。"
      onClose={onClose}
    >
      <form className="editor" onSubmit={submit}>
        <div className="editor-tabs">
          {actions
            .filter(
              ([id]) =>
                (dimension !== "department" && !account.fresh) ||
                id === "assign"
            )
            .map(([id, label]) => (
              <button
                type="button"
                key={id}
                onClick={() => {
                  setAction(id);
                  if (id === "tier" && tier === "inherit")
                    setTier(account.tier || "L1");
                }}
                className={action === id ? "selected" : ""}
              >
                {label}
              </button>
            ))}
        </div>
        {!account.fresh && dimension === "user" && (
          <div className="balance-box">
            <div>
              个人剩余预算<strong>{money(account.balance)}</strong>
            </div>
            <div>
              已消费<strong>{money(account.spent)}</strong>
            </div>
            <div>
              预算上限<strong>{money(account.limit)}</strong>
            </div>
          </div>
        )}
        <div className="form-grid">
          <label>
            主体维度
            <select
              value={dimension}
              disabled={!account.fresh}
              onChange={(e) => {
                setDimension(e.target.value);
                if (e.target.value === "department" && tier === "inherit")
                  setTier("L1");
              }}
            >
              <option value="user">用户</option>
              <option value="department">部门</option>
            </select>
          </label>
          <label>
            主体标识
            <input
              value={subject}
              disabled={!account.fresh}
              onChange={(e) => setSubject(e.target.value)}
              placeholder="例如 user-001"
              maxLength={128}
              required
            />
          </label>
        </div>
        {dimension === "user" && action === "assign" && (
          <label>
            所属部门
            <select
              value={department}
              onChange={(e) => setDepartment(e.target.value)}
            >
              <option value="">未关联部门</option>
              {departments.map((d) => (
                <option key={d.subject} value={d.subject}>
                  {d.subject}
                </option>
              ))}
            </select>
            <small>部门由管理员维护；个人档位可以独立配置。</small>
          </label>
        )}
        {(action === "assign" || action === "tier") && (
          <label>
            {action === "assign" ? "基准档位" : "申请档位"}
            <div className="tier-choice">
              {(dimension === "user" && action === "assign"
                ? ["inherit", "L1", "L2", "L3"]
                : ["L1", "L2", "L3"]
              ).map((t) => (
                <button
                  type="button"
                  className={tier === t ? "chosen" : ""}
                  aria-label={t === "inherit" ? "继承部门" : "选择 " + t}
                  aria-pressed={tier === t}
                  key={t}
                  onClick={() => setTier(t)}
                >
                  <Badge tier={t} />
                  <strong>
                    {t === "inherit"
                      ? "部门默认"
                      : money(meta.tiers?.[t]?.quota)}
                  </strong>
                  <small>元 / 双周</small>
                </button>
              ))}
            </div>
          </label>
        )}
        {action === "delta" && (
          <label>
            追加金额（元）
            <input
              type="text"
              inputMode="decimal"
              value={value}
              onChange={(e) => setValue(e.target.value)}
              step="0.000001"
              required
            />
            <small>正数增加余额，负数扣减；仅当前周期有效。</small>
          </label>
        )}
        <label>
          变更工单号
          <input
            value={ticket}
            onChange={(e) => setTicket(e.target.value)}
            required
            maxLength={128}
          />
          <small>
            同一工单重复提交不会重复增加额度；修改内容请使用新工单号。
          </small>
        </label>
        <div className="summary-box">
          <div>
            <span>当前周期</span>
            <strong>{meta.period}</strong>
          </div>
          <div>
            <span>下次重置（固定 14 天）</span>
            <strong>{date(meta.period_end)}</strong>
          </div>
          <p>
            {action === "assign"
              ? "配置持久基准档位，取消临时升档，保留已消费与临时加量。"
              : action === "tier"
              ? "临时升档只在当前周期生效，不允许降档。"
              : action === "delta"
              ? "临时加量不结转到下一周期。"
              : "仅初始化尚未初始化的周期；不会恢复已经消耗的额度。"}
          </p>
        </div>
        {error && (
          <div role="alert" className="error">
            {error}
          </div>
        )}
        <div className="drawer-actions">
          <button type="button" className="secondary" onClick={onClose}>
            取消
          </button>
          <button className="primary" disabled={busy}>
            {busy ? "正在保存…" : "保存配置"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
function TierEditor({ meta, onClose, onSaved }) {
  const [tiers, setTiers] = useState(
      Object.fromEntries(
        Object.entries(meta.tiers).map(([k, v]) => [
          k,
          { ...v, quota: fromMicros(v.quota) },
        ])
      )
    ),
    [ticket, setTicket] = useState("TIERS-" + crypto.randomUUID()),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const normalized = Object.fromEntries(
        Object.entries(tiers).map(([k, v]) => {
          const n = toMicros(v.quota);
          if (!Number.isSafeInteger(n) || n < 0)
            throw new Error(k + " 预算必须非负");
          return [
            k,
            {
              quota: n,
              models:
                typeof v.models === "string"
                  ? v.models
                      .split(",")
                      .map((x) => x.trim())
                      .filter(Boolean)
                  : v.models,
            },
          ];
        })
      );
      await quota("tiers", {
        tiers: normalized,
        ticket_id: ticket,
        period: meta.period,
      });
      await onSaved();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      title="编辑档位模板"
      subtitle="新账户与新周期使用此模板，当前周期已初始化账户保持不变。"
      onClose={onClose}
    >
      <form className="editor" onSubmit={submit}>
        {["L1", "L2", "L3"].map((t) => (
          <div className="tier-form" key={t}>
            <Badge tier={t} />
            <label>
              周期预算（元）
              <input
                type="text"
                inputMode="decimal"
                min="1"
                step="0.000001"
                value={tiers[t].quota}
                onChange={(e) =>
                  setTiers({
                    ...tiers,
                    [t]: { ...tiers[t], quota: e.target.value },
                  })
                }
                required
              />
            </label>
            <label>
              允许的模型
              <input
                value={
                  Array.isArray(tiers[t].models)
                    ? tiers[t].models.join(",")
                    : tiers[t].models
                }
                onChange={(e) =>
                  setTiers({
                    ...tiers,
                    [t]: { ...tiers[t], models: e.target.value },
                  })
                }
                placeholder="留空或 * 表示全部模型"
              />
            </label>
          </div>
        ))}
        <label>
          变更工单号
          <input
            value={ticket}
            onChange={(e) => setTicket(e.target.value)}
            required
          />
        </label>
        {error && (
          <div className="error" role="alert">
            {error}
          </div>
        )}
        <div className="drawer-actions">
          <button type="button" className="secondary" onClick={onClose}>
            取消
          </button>
          <button className="primary" disabled={busy}>
            {busy ? "正在保存…" : "保存档位模板"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
function AuditTable({ records }) {
  const labels = {
    assign: "基准配置",
    tier: "本周期升档",
    delta: "临时加量",
    refresh: "周期初始化",
    tiers: "档位模板",
    prices: "模型单价",
  };
  return (
    <section className="panel">
      <div className="section-heading padded">
        <div>
          <h2>额度变更记录</h2>
          <p>最近 100 条操作 · 重复工单不重复写入</p>
        </div>
        <span className="pill">{records.length} 条</span>
      </div>
      {!records.length ? (
        <Empty title="暂无变更记录">保存额度配置后，记录会出现在这里。</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>操作 / 工单</th>
                <th>主体</th>
                <th>变更前 → 变更后</th>
                <th>操作人</th>
                <th>时间 / 周期</th>
              </tr>
            </thead>
            <tbody>
              {records.map((r, i) => (
                <tr key={r.ticket_id || i}>
                  <td>
                    <strong>{labels[r.action] || r.action}</strong>
                    <small className="muted block ticket">{r.ticket_id}</small>
                  </td>
                  <td>
                    {r.dimension === "user"
                      ? "用户"
                      : r.dimension === "app"
                      ? "应用"
                      : r.dimension === "department"
                      ? "部门"
                      : "模板"}
                    <small className="block">
                      {r.subject ||
                        (r.action === "prices" ? "全部模型单价" : "全部档位")}
                    </small>
                  </td>
                  <td>
                    <div className="audit-diff">
                      <Snapshot
                        value={r.before}
                        prices={r.action === "prices"}
                      />
                      <Icon name="arrow" size={14} />
                      <Snapshot
                        value={r.after}
                        prices={r.action === "prices"}
                      />
                    </div>
                  </td>
                  <td>{r.operator || "—"}</td>
                  <td>
                    {date(r.occurred_at)}
                    <small className="muted block">{r.period}</small>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
function Snapshot({ value, prices }) {
  if (prices && value && typeof value === "object") {
    const entries = Object.entries(value.prices || value);
    return (
      <span className="template-snapshot">
        {entries.length
          ? entries.map(([model, price]) => (
              <small className="block" key={model}>
                <b>{model}</b>
                <br />
                输入 {money(price.input_price_micros)} / 输出{" "}
                {money(price.output_price_micros)}
                <br />
                每百万 Token
              </small>
            ))
          : "未配置单价"}
      </span>
    );
  }

  if (value?.L1)
    return (
      <span className="template-snapshot">
        {["L1", "L2", "L3"].map((t) => (
          <small className="block" key={t}>
            <b>{t}</b> {money(value[t]?.quota)}
            <br />
            {!value[t]?.models?.length || value[t].models.includes("*")
              ? "全部模型"
              : value[t].models.join(", ")}
          </small>
        ))}
      </span>
    );
  if (!value) return <span className="muted">—</span>;
  if (typeof value !== "object") return <span>{String(value)}</span>;
  return (
    <span>
      {value.tier && <Badge tier={value.tier} />}
      <small className="block">
        {value.balance !== undefined || value.quota !== undefined
          ? money(value.balance ?? value.quota)
          : value.L1
          ? "三档模板"
          : JSON.stringify(value)}
      </small>
    </span>
  );
}
createRoot(document.getElementById("root")).render(<App />);
