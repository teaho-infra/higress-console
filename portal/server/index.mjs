import { createPortal } from "./app.mjs";
import { fileURLToPath } from "node:url";
const server = createPortal({
  password: process.env.PORTAL_PASSWORD,
  adminKey: process.env.GATEWAY_ADMIN_KEY,
  gatewayUrl: process.env.GATEWAY_URL || "http://127.0.0.1:18080",
  gatewayHost: process.env.GATEWAY_HOST || "quota-portal.local",
  secureCookie: process.env.PORTAL_SECURE_COOKIE === "true",
  staticDir: fileURLToPath(new URL("../dist", import.meta.url)),
});
server.listen(
  Number(process.env.PORTAL_PORT || 18890),
  process.env.PORTAL_BIND || "127.0.0.1",
  () =>
    console.log(
      `Higress Quota Portal listening on ${JSON.stringify(server.address())}`
    )
);
for (const signal of ["SIGTERM", "SIGINT"])
  process.on(signal, () => server.close(() => process.exit(0)));
