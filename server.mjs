import { createReadStream, existsSync, statSync } from "node:fs";
import { createServer } from "node:http";
import { extname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = fileURLToPath(new URL(".", import.meta.url));
const distDir = resolve(__dirname, "dist");
const port = Number(process.env.PORT ?? 8080);
const hostname = "0.0.0.0";

const mimeTypes = {
  ".css": "text/css; charset=utf-8",
  ".gif": "image/gif",
  ".html": "text/html; charset=utf-8",
  ".ico": "image/x-icon",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".txt": "text/plain; charset=utf-8",
  ".webp": "image/webp",
};

const server = createServer((request, response) => {
  if (!request.url) {
    response.writeHead(400);
    response.end("Bad request");
    return;
  }

  const url = new URL(request.url, `http://${request.headers.host ?? "localhost"}`);
  const requestedPath = decodeURIComponent(url.pathname);
  const filePath = getStaticFilePath(requestedPath);

  if (!filePath) {
    response.writeHead(403);
    response.end("Forbidden");
    return;
  }

  if (existsSync(filePath) && statSync(filePath).isFile()) {
    streamFile(filePath, response);
    return;
  }

  streamFile(join(distDir, "index.html"), response);
});

server.listen(port, hostname, () => {
  console.log(`Solar System Explorer listening on ${hostname}:${port}`);
});

function getStaticFilePath(pathname) {
  const normalizedPath = normalize(pathname).replace(/^(\.\.[/\\])+/, "");
  const relativePath = normalizedPath === "/" ? "index.html" : normalizedPath.slice(1);
  const filePath = resolve(distDir, relativePath);

  return filePath.startsWith(distDir) ? filePath : null;
}

function streamFile(filePath, response) {
  const extension = extname(filePath);
  const contentType = mimeTypes[extension] ?? "application/octet-stream";

  response.writeHead(200, {
    "Cache-Control": extension === ".html" ? "no-cache" : "public, max-age=31536000, immutable",
    "Content-Type": contentType,
  });

  createReadStream(filePath).pipe(response);
}
