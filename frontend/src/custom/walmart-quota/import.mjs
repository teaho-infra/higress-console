// Strict CSV reader: preserve user IDs as strings; support BOM, CRLF and quoted fields.
function csvRows(text) {
  const rows = [];
  let row = [],
    field = "",
    quoted = false,
    closed = false;
  const finish = () => {
    row.push(field);
    field = "";
    closed = false;
  };
  text = text.replace(/^\uFEFF/, "");
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          quoted = false;
          closed = true;
        }
      } else field += c;
      continue;
    }
    if (c === ",") {
      finish();
      continue;
    }
    if (c === "\r" || c === "\n") {
      if (c === "\r" && text[i + 1] === "\n") i++;
      finish();
      rows.push(row);
      row = [];
      continue;
    }
    if (closed) throw new Error("引号闭合后只能接逗号或换行");
    if (c === '"') {
      if (field) throw new Error("引号只能出现在字段开头");
      quoted = true;
    } else field += c;
  }
  if (quoted) throw new Error("CSV 引号未闭合");
  if (field || closed || row.length) {
    finish();
    rows.push(row);
  }
  return rows.filter((r) => r.some((v) => v.trim()));
}
export function parseImport(text, departments) {
  if (new TextEncoder().encode(text).length > 1024 * 1024)
    throw new Error("文件不能超过 1 MB");
  const rows = csvRows(text);
  const aliases = {
    subject: "subject",
    user_id: "subject",
    用户标识: "subject",
    department: "department",
    所属部门: "department",
    部门: "department",
    tier: "tier",
    档位: "tier",
  };
  const headers = (rows.shift() || []).map((v) =>
    Object.hasOwn(aliases, v.trim().toLowerCase())
      ? aliases[v.trim().toLowerCase()]
      : undefined
  );
  if (
    headers.length !== 3 ||
    headers.some((v) => !v) ||
    new Set(headers).size !== 3
  )
    throw new Error(
      "表头必须是：用户标识、所属部门、档位（或 subject,department,tier）"
    );
  if (!rows.length || rows.length > 200)
    throw new Error("每次导入 1–200 条用户配置");
  const seen = new Set();
  return rows.map((r, i) => {
    const fail = (message) => {
      throw new Error(`第 ${i + 1} 条数据：${message}`);
    };
    if (r.length !== 3) fail("字段数量必须为 3");
    const d = Object.fromEntries(headers.map((k, j) => [k, r[j].trim()]));
    if (
      !d.subject ||
      new TextEncoder().encode(d.subject).length > 256 ||
      /[\r\n\u0000-\u001f]/.test(d.subject)
    )
      fail("用户标识须为 1–256 字节且不能包含换行或控制字符");
    if (seen.has(d.subject)) fail("用户标识重复：" + d.subject);
    seen.add(d.subject);
    d.tier =
      !d.tier || d.tier === "继承部门" || d.tier.toLowerCase() === "inherit"
        ? "inherit"
        : d.tier.toUpperCase();
    if (!["inherit", "L1", "L2"].includes(d.tier))
      fail("档位只能是 L1、L2 或 inherit");
    if (d.department && !departments.has(d.department))
      fail("部门不存在：" + d.department + "；请先在部门模板中创建");
    if (d.tier === "inherit" && !d.department) fail("继承部门必须填写所属部门");
    return { subject: d.subject, department: d.department, tier: d.tier };
  });
}
export function makeJobs(rows, period, batch) {
  return rows.map((row, i) => ({
    body: { ...row, dimension: "user", period, ticket_id: `${batch}-${i + 1}` },
    status: "pending",
    message: "",
  }));
}
export async function runImport(jobs, send, progress) {
  const next = jobs.map((j) => ({ ...j }));
  for (let i = 0; i < next.length; i++) {
    if (next[i].status === "success") continue;
    next[i] = { ...next[i], status: "running", message: "" };
    progress(next.map((j) => ({ ...j })));
    try {
      await send(next[i].body);
      next[i] = { ...next[i], status: "success", message: "已生效" };
    } catch (error) {
      next[i] = {
        ...next[i],
        status: "error",
        message: error.message || "请求失败",
      };
      if ([401, 403, 409].includes(error.status)) {
        progress(next.map((j) => ({ ...j })));
        break;
      }
    }
    progress(next.map((j) => ({ ...j })));
  }
  return next;
}
export const importTemplate =
  "\uFEFF用户标识,所属部门,档位\r\nuser-001,研发部,inherit\r\nuser-002,,L1\r\n";
