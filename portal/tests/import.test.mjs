import test from "node:test";
import assert from "node:assert/strict";
import { parseImport, makeJobs, runImport } from "../src/import.mjs";
const departments = new Set(["研发部", "部门,一"]);
test("CSV preserves string IDs, BOM, reordered headers and quoted commas", () => {
  const rows = parseImport(
    '\ufeff档位,用户标识,所属部门\r\n,001,研发部\r\nL1,"user,""one""","部门,一"\r\n',
    departments
  );
  assert.deepEqual(rows, [
    { subject: "001", department: "研发部", tier: "inherit" },
    { subject: 'user,"one"', department: "部门,一", tier: "L1" },
  ]);
});
test("reject duplicate users, unknown department, invalid tier and missing inheritance department", () => {
  for (const csv of [
    "a,研发部,L1\na,研发部,L2",
    "a,不存在,inherit",
    "a,研发部,L4",
    "a,,inherit",
  ])
    assert.throws(() =>
      parseImport("subject,department,tier\n" + csv, departments)
    );
  assert.equal(
    parseImport("subject,department,tier\na,,L1", departments)[0].tier,
    "L1"
  );
});
test("reject malformed CSV, extra columns, wrong headers and overlong identifiers", () => {
  for (const csv of [
    'subject,department,tier\n"a,研发部,L1',
    'subject,department,tier\n"a"x,研发部,L1',
    "subject,department,tier\na,研发部,L1,x",
    "subject,department,unknown\na,研发部,L1",
    "subject,department,tier\n" + "用".repeat(86) + ",,L1",
  ])
    assert.throws(() => parseImport(csv, departments));
});
test("empty files and over 200 rows rejected before any calls", () => {
  assert.throws(() => parseImport("subject,department,tier", departments));
  assert.throws(() =>
    parseImport(
      "subject,department,tier\n" +
        Array.from({ length: 201 }, (_, i) => `u${i},,L1`).join("\n"),
      departments
    )
  );
});
test("failed retries use identical ticket and body; successful rows never resent", async () => {
  const jobs = makeJobs(
    [
      { subject: "a", department: "研发部", tier: "inherit" },
      { subject: "b", department: "", tier: "L2" },
    ],
    "2026-11-09",
    "IMPORT-test"
  );
  const calls = [];
  const first = await runImport(
    jobs,
    async (body) => {
      calls.push(body);
      if (body.subject === "b") throw new Error("network failure");
    },
    () => {}
  );
  assert.deepEqual(
    first.map((x) => x.status),
    ["success", "error"]
  );
  const second = await runImport(
    first,
    async (body) => calls.push(body),
    () => {}
  );
  assert.equal(calls.length, 3);
  assert.deepEqual(calls[1], calls[2]);
  assert.equal(second[1].status, "success");
});
test("authentication or period conflicts stop later rows, retaining partial result", async () => {
  const jobs = makeJobs(
    ["a", "b", "c"].map((subject) => ({ subject, department: "", tier: "L1" })),
    "p",
    "IMPORT-stop"
  );
  let count = 0;
  const result = await runImport(
    jobs,
    async () => {
      if (++count === 2)
        throw Object.assign(new Error("period changed"), { status: 409 });
    },
    () => {}
  );
  assert.equal(count, 2);
  assert.deepEqual(
    result.map((x) => x.status),
    ["success", "error", "pending"]
  );
});
