let csrf = "";
export async function request(path, method = "GET", data) {
  const response = await fetch("/api/" + path, {
    method,
    credentials: "same-origin",
    headers:
      method === "GET"
        ? {}
        : { "Content-Type": "application/json", "x-portal-csrf": csrf },
    body: data === undefined ? undefined : JSON.stringify(data),
  });
  let json;
  try {
    json = await response.json();
  } catch {
    throw new Error(`服务响应格式异常（HTTP ${response.status}）`);
  }
  if (!response.ok || json.ok === false) {
    const e = new Error(
      json.message || json.error || `HTTP ${response.status}`
    );
    e.status = response.status;
    throw e;
  }
  // Redis Lua represents an empty table as {}; tolerate it for known model lists only.
  function normalize(value) {
    if (!value || typeof value !== "object") return;
    for (const [key, child] of Object.entries(value)) {
      if (
        key === "models" &&
        child &&
        typeof child === "object" &&
        !Array.isArray(child) &&
        Object.keys(child).length === 0
      )
        value[key] = [];
      else normalize(child);
    }
  }
  normalize(json);
  if (json.csrf) csrf = json.csrf;
  return json;
}
export const quota = (action = "", data) =>
  request("quota/" + action, data === undefined ? "GET" : "POST", data);
