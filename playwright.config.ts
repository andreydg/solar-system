import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  timeout: 90_000,
  use: {
    viewport: { width: 800, height: 600 },
    baseURL: "http://127.0.0.1:18080",
    timezoneId: "America/Los_Angeles",
    launchOptions: { args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader"] },
  },
  webServer: {
    command: "java -jar backend/target/solar-system-backend-0.1.0.jar --server.port=18080 --solar-system.storage=in-memory --solar-system.trajectories.prewarm-enabled=false --solar-system.jpl.async-validation-enabled=false",
    url: "http://127.0.0.1:18080/actuator/health",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
