import { cpSync, rmSync } from "node:fs";
import { execFileSync } from "node:child_process";

// Exactly the Docker build's asset-to-jar path, not test-only static resources.
execFileSync("npm", ["run", "build"], { stdio: "inherit" });
const destination = "backend/src/main/resources/static";
rmSync(destination, { recursive: true, force: true });
cpSync("dist", destination, { recursive: true });
try {
  execFileSync(process.env.MAVEN ?? "mvn", ["-B", "-f", "backend/pom.xml", "package", "-DskipTests"], { stdio: "inherit" });
} finally {
  rmSync(destination, { recursive: true, force: true });
}
