import { cpSync, rmSync } from "node:fs";
import { execFileSync } from "node:child_process";

// Exactly the Docker build's asset-to-jar path, not test-only static resources.
execFileSync("npm", ["run", "build"], { stdio: "inherit" });
const destination = "backend/src/main/resources/static";
rmSync(destination, { recursive: true, force: true });
cpSync("dist", destination, { recursive: true });
try {
  // Maven copies resources without deleting obsolete hashes from earlier local builds.
  rmSync("backend/target/classes/static", { recursive: true, force: true });
  execFileSync(process.env.MAVEN ?? "./mvnw", ["-B", "-f", "backend/pom.xml", "package", "-DskipTests"], { stdio: "inherit" });
} finally {
  rmSync(destination, { recursive: true, force: true });
}
