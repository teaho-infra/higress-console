export const MAX_MICROS = 9_999_999_999_999;
export function toMicros(value, { signed = false, max = MAX_MICROS } = {}) {
  if (
    typeof value !== "string" ||
    !(signed ? /^-?\d+(?:\.\d{1,6})?$/ : /^\d+(?:\.\d{1,6})?$/).test(value)
  )
    throw new Error("请输入普通十进制金额，最多 6 位小数");
  const negative = value.startsWith("-");
  const [whole, fraction = ""] = (negative ? value.slice(1) : value).split(".");
  let n = BigInt(whole) * 1_000_000n + BigInt(fraction.padEnd(6, "0"));
  if (n > BigInt(max)) throw new Error("金额超出允许范围");
  if (negative) n = -n;
  return Number(n);
}
export function fromMicros(value = 0) {
  if (!Number.isSafeInteger(value)) throw new Error("金额必须为安全整数微元");
  const n = BigInt(value),
    abs = n < 0n ? -n : n;
  const fraction = (abs % 1_000_000n)
    .toString()
    .padStart(6, "0")
    .replace(/0+$/, "");
  return (
    (n < 0n ? "-" : "") + abs / 1_000_000n + (fraction ? "." + fraction : "")
  );
}
export const money = (value) => "¥" + fromMicros(value ?? 0);
