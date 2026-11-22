import React, { useEffect, useState } from "react";
import { quota } from "./api";
import { parseImport, makeJobs, runImport, importTemplate } from "./import.mjs";
function download(name, text, type) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export default function BulkImport({ onClose, onChanged }) {
  const [jobs, setJobs] = useState([]),
    [batch, setBatch] = useState(""),
    [file, setFile] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [confirmed, setConfirmed] = useState(false),
    [attempted, setAttempted] = useState(false);
  useEffect(() => {
    const key = (e) => {
      if (e.key === "Escape" && !busy) onClose();
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, [busy, onClose]);
  async function preview(f) {
    setJobs([]);
    setError("");
    setConfirmed(false);
    setAttempted(false);
    setFile(f?.name || "");
    if (!f) return;
    setBusy(true);
    try {
      if (f.size > 1024 * 1024) throw new Error("文件不能超过 1 MB");
      const bytes = await f.arrayBuffer();
      let text;
      try {
        text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
      } catch {
        throw new Error("文件须为 UTF-8 CSV；可用 Excel 另存为 CSV UTF-8。");
      }
      const departments = new Set();
      for (let offset = 0; ; offset += 200) {
        const d = await quota(
          `accounts?dimension=department&offset=${offset}&limit=200`
        );
        for (const item of d.accounts || []) departments.add(item.subject);
        if ((d.accounts || []).length < 200) break;
      }
      const rows = parseImport(text, departments),
        meta = await quota("meta"),
        id = "IMPORT-" + crypto.randomUUID();
      setBatch(id);
      setJobs(makeJobs(rows, meta.period, id));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  async function start() {
    setBusy(true);
    setError("");
    try {
      const meta = await quota("meta");
      if (meta.period !== jobs[0].body.period)
        throw new Error("周期已切换，请下载当前结果，再重新选择文件进行预览。");
      setAttempted(true);
      const results = await runImport(
        jobs,
        (body) => quota("assign", body),
        setJobs
      );
      setJobs(results);
      await onChanged();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  const successes = jobs.filter((j) => j.status === "success").length,
    errors = jobs.filter((j) => j.status === "error").length;
  const close = () => {
    if (!busy) onClose();
  };
  return (
    <div className="backdrop" onClick={close}>
      <section
        className="drawer bulk-import"
        role="dialog"
        aria-modal="true"
        aria-label="批量导入个人预算"
        onClick={(e) => e.stopPropagation()}
      >
        <header>
          <div>
            <h2>批量导入个人预算</h2>
            <p>先预览校验，再逐条写入网关；应用统计由实际请求产生。</p>
          </div>
          <button
            className="icon-button"
            aria-label="关闭导入"
            disabled={busy}
            onClick={close}
          >
            ×
          </button>
        </header>
        <div className="editor">
          <p>
            CSV UTF-8，最多 200 条 / 1
            MB。字段：用户标识、所属部门、档位。档位留空或填 inherit
            表示继承部门；部门须提前创建。
          </p>
          <button
            className="secondary"
            disabled={busy}
            onClick={() =>
              download(
                "个人预算导入模板.csv",
                importTemplate,
                "text/csv;charset=utf-8"
              )
            }
          >
            下载 CSV 模板
          </button>
          <label>
            选择 CSV 文件
            <input
              aria-label="选择 CSV 文件"
              type="file"
              accept=".csv,text/csv"
              disabled={busy}
              onChange={(e) => preview(e.target.files?.[0])}
            />
          </label>
          {error && (
            <div className="error" role="alert">
              {error}
            </div>
          )}
          {jobs.length > 0 && (
            <>
              <p className="muted">
                {file} · 共 {jobs.length} 条 · 工单批次 {batch}
              </p>
              <div className="table-wrap import-preview">
                <table>
                  <thead>
                    <tr>
                      <th>用户标识</th>
                      <th>所属部门</th>
                      <th>档位</th>
                      <th>结果</th>
                    </tr>
                  </thead>
                  <tbody>
                    {jobs.map((j) => (
                      <tr key={j.body.ticket_id}>
                        <td>{j.body.subject}</td>
                        <td>{j.body.department || "—"}</td>
                        <td>
                          {j.body.tier === "inherit" ? "继承部门" : j.body.tier}
                        </td>
                        <td
                          className={j.status === "error" ? "text-danger" : ""}
                        >
                          {j.message ||
                            {
                              pending: "待导入",
                              running: "正在导入",
                              success: "已生效",
                            }[j.status]}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <label className="import-confirm">
                <input
                  type="checkbox"
                  checked={confirmed}
                  disabled={busy || attempted}
                  onChange={(e) => setConfirmed(e.target.checked)}
                />
                我确认新增或更新这些用户配置：保留已消费和加量，替换基准档位并结束原临时升档。
              </label>
              <p className="info">
                逐条生效，非整批事务；成功项不会回滚。失败可重试，沿用原工单，成功项不会重复提交。处理期间请勿刷新页面。
              </p>
              <div role="status">
                成功 {successes} 条，失败 {errors} 条，未完成{" "}
                {jobs.length - successes - errors} 条
              </div>
              <div className="import-actions">
                <button
                  className="primary"
                  disabled={busy || !confirmed || successes === jobs.length}
                  onClick={start}
                >
                  {busy
                    ? "正在处理…"
                    : attempted
                    ? "重试失败及未完成项"
                    : "确认导入"}
                </button>
                <button
                  className="secondary"
                  disabled={busy}
                  onClick={() =>
                    download(
                      batch + ".json",
                      JSON.stringify(
                        { batch, exported_at: new Date().toISOString(), jobs },
                        null,
                        2
                      ),
                      "application/json"
                    )
                  }
                >
                  下载结果与工单
                </button>
              </div>
            </>
          )}
          {!jobs.length && busy && <p role="status">正在校验文件与部门配置…</p>}
        </div>
      </section>
    </div>
  );
}
