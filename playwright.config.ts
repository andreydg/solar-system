import { defineConfig } from "@playwright/test";

const port = Number(process.env.E2E_PORT ?? 18080);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error("Invalid E2E_PORT");
const baseURL = `http://127.0.0.1:${port}`;

export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  timeout: 90_000,
  use: {
    viewport: { width: 800, height: 600 },
    baseURL,
    timezoneId: "America/Los_Angeles",
    launchOptions: { args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader"] },
  },
  webServer: {
    command: `java -jar backend/target/solar-system-backend-0.1.0.jar --server.port=${port} --solar-system.storage=in-memory --solar-system.jpl.async-validation-enabled=false`,
    url: baseURL + "/actuator/health",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
