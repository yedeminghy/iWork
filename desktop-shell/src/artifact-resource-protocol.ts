import { protocol } from 'electron';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { realpathSync, statSync } from 'node:fs';
import { readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

export const ARTIFACT_RESOURCE_SCHEME = 'agent-artifact';
const MAX_RESOURCE_BYTES = 16 * 1024 * 1024;
const MAX_RUNTIME_BYTES = 8 * 1024 * 1024;
const GRANT_TTL_MS = 30 * 60 * 1000;
const requireRuntime = createRequire(import.meta.url);
const MIME_TYPES: Record<string, string> = {
  '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif',
  '.webp': 'image/webp', '.svg': 'image/svg+xml', '.bmp': 'image/bmp', '.woff': 'font/woff', '.woff2': 'font/woff2',
  '.ttf': 'font/ttf', '.otf': 'font/otf', '.pdf': 'application/pdf'
};

type Grant = { kind: 'directory'; basePath: string; expiresAt: number }
  | { kind: 'html'; basePath: string; content: string; tailwindCss?: string; lucide: boolean; expiresAt: number }
  | { kind: 'file'; filePath: string; cleanupRoot: string; expiresAt: number };

export class ArtifactResourceProtocol {
  private readonly grants = new Map<string, Grant>();

  constructor(
    private readonly echartsRuntimePath: string,
    private readonly tailwindCompilerPath?: string,
    private readonly lucideRuntimePath?: string
  ) {}

  register(): void { protocol.handle(ARTIFACT_RESOURCE_SCHEME, request => this.handle(request)); }

  grantDirectory(basePath: string): { previewId: string; baseUrl: string } {
    const previewId = this.newToken();
    this.grants.set(previewId, { kind: 'directory', basePath: realpathSync.native(basePath), expiresAt: Date.now() + GRANT_TTL_MS });
    return { previewId, baseUrl: `${ARTIFACT_RESOURCE_SCHEME}://${previewId}/` };
  }

  async grantHtml(basePath: string, content: string): Promise<{ previewId: string; baseUrl: string; url: string }> {
    const previewId = this.newToken();
    const baseUrl = `${ARTIFACT_RESOURCE_SCHEME}://${previewId}/`;
    const requested = requestedRuntimes(content);
    const tailwindCss = requested.tailwind ? await this.compileTailwind(content) : undefined;
    this.grants.set(previewId, { kind: 'html', basePath: realpathSync.native(basePath), content, tailwindCss, lucide: requested.lucide && Boolean(this.lucideRuntimePath), expiresAt: Date.now() + GRANT_TTL_MS });
    return { previewId, baseUrl, url: `${baseUrl}preview.html` };
  }

  grantFile(filePath: string, cleanupRoot: string): { previewId: string; url: string } {
    const previewId = this.newToken();
    this.grants.set(previewId, { kind: 'file', filePath: realpathSync.native(filePath), cleanupRoot, expiresAt: Date.now() + GRANT_TTL_MS });
    return { previewId, url: `${ARTIFACT_RESOURCE_SCHEME}://${previewId}/preview.pdf` };
  }

  async release(previewId: string): Promise<void> {
    const grant = this.grants.get(previewId);
    this.grants.delete(previewId);
    if (grant?.kind === 'file') await this.removeTempRoot(grant.cleanupRoot);
  }

  async dispose(): Promise<void> {
    for (const id of [...this.grants.keys()]) await this.release(id);
  }

  private async handle(request: Request): Promise<Response> {
    try {
      const url = new URL(request.url);
      const grant = this.grants.get(url.hostname);
      if (!grant || grant.expiresAt < Date.now() || request.method !== 'GET') return new Response(null, { status: 404 });
      if (grant.kind === 'html' && url.pathname === '/preview.html') {
        const document = htmlPreviewDocument(grant.content, `${ARTIFACT_RESOURCE_SCHEME}://${url.hostname}/`, Boolean(grant.tailwindCss), grant.lucide);
        return new Response(document, { status: 200, headers: htmlHeaders(document) });
      }
      if (grant.kind === 'html' && url.pathname === '/runtime/tailwind.css' && grant.tailwindCss) {
        return textResponse(grant.tailwindCss, 'text/css; charset=utf-8');
      }
      if (grant.kind === 'html' && url.pathname === '/runtime/echarts.min.js') {
        return await runtimeResponse(this.echartsRuntimePath);
      }
      if (grant.kind === 'html' && url.pathname === '/runtime/lucide.min.js' && grant.lucide && this.lucideRuntimePath) {
        return await runtimeResponse(this.lucideRuntimePath);
      }
      let filePath: string;
      if (grant.kind === 'file') {
        if (url.pathname !== '/preview.pdf') return new Response(null, { status: 404 });
        filePath = grant.filePath;
      } else {
        const relative = decodeURIComponent(url.pathname).replace(/^\/+/, '').replaceAll('\\', '/');
        if (!relative || relative.split('/').some(segment => !segment || segment === '.' || segment === '..')) return new Response(null, { status: 404 });
        const candidate = realpathSync.native(path.resolve(grant.basePath, ...relative.split('/')));
        const escaped = path.relative(grant.basePath, candidate);
        if (escaped.startsWith('..') || path.isAbsolute(escaped)) return new Response(null, { status: 404 });
        filePath = candidate;
      }
      const extension = path.extname(filePath).toLocaleLowerCase();
      const mimeType = MIME_TYPES[extension];
      const fileStat = statSync(filePath);
      const maximumBytes = grant.kind === 'file' ? 80 * 1024 * 1024 : MAX_RESOURCE_BYTES;
      if (!mimeType || !fileStat.isFile() || fileStat.size > maximumBytes) return new Response(null, { status: 404 });
      const bytes = await readFile(filePath);
      return new Response(bytes, { status: 200, headers: { 'Content-Type': mimeType, 'Content-Length': String(bytes.length), 'Access-Control-Allow-Origin': '*' } });
    } catch {
      return new Response(null, { status: 404 });
    }
  }

  private newToken(): string {
    this.pruneExpired();
    if (this.grants.size >= 32) throw new Error('ARTIFACT_PREVIEW_LIMIT');
    return randomUUID().replaceAll('-', '');
  }

  private pruneExpired(): void {
    const now = Date.now();
    for (const [id, grant] of this.grants) if (grant.expiresAt < now) void this.release(id);
  }

  private async removeTempRoot(root: string): Promise<void> {
    const resolved = path.resolve(root);
    if (path.dirname(resolved) !== path.resolve(os.tmpdir()) || !path.basename(resolved).startsWith('agent-desktop-preview-')) return;
    await rm(resolved, { recursive: true, force: true }).catch(() => undefined);
  }

  private async compileTailwind(content: string): Promise<string | undefined> {
    if (!this.tailwindCompilerPath) return undefined;
    const compiler = requireRuntime(this.tailwindCompilerPath) as (value: string) => Promise<string>;
    const css = await compiler(content);
    if (typeof css !== 'string' || Buffer.byteLength(css, 'utf8') > MAX_RUNTIME_BYTES) throw new Error('ARTIFACT_PREVIEW_TAILWIND_INVALID');
    return css;
  }
}

function htmlPreviewDocument(content: string, baseUrl: string, tailwind: boolean, lucide: boolean): string {
  const withoutCsp = content.replace(/<meta\b(?=[^>]*\bhttp-equiv\s*=\s*["']?Content-Security-Policy["']?)[^>]*>/gi, '');
  const withoutRemoteScripts = withoutCsp.replace(/<script\b(?=[^>]*\bsrc\s*=)[^>]*>[\s\S]*?<\/script\s*>/gi, '');
  const bootstrap = `<base href="${baseUrl}">${tailwind ? '<link rel="stylesheet" href="runtime/tailwind.css">' : ''}<script src="runtime/echarts.min.js"></script>${lucide ? '<script src="runtime/lucide.min.js"></script>' : ''}`;
  return /<head(?:\s[^>]*)?>/i.test(withoutRemoteScripts) ? withoutRemoteScripts.replace(/<head(?:\s[^>]*)?>/i, match => `${match}${bootstrap}`) : `<!doctype html><html><head>${bootstrap}</head><body>${withoutRemoteScripts}</body></html>`;
}

function requestedRuntimes(content: string): { tailwind: boolean; lucide: boolean } {
  let tailwind = false;
  let lucide = false;
  for (const match of content.matchAll(/<script\b[^>]*\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))[^>]*>/gi)) {
    const source = (match[1] ?? match[2] ?? match[3] ?? '').toLocaleLowerCase();
    tailwind ||= source.includes('tailwind');
    lucide ||= source.includes('lucide');
  }
  return { tailwind, lucide };
}

function textResponse(content: string, contentType: string): Response {
  return new Response(content, { status: 200, headers: { 'Content-Type': contentType, 'Content-Length': String(Buffer.byteLength(content, 'utf8')), 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' } });
}

async function runtimeResponse(filePath: string): Promise<Response> {
  const runtime = await readFile(filePath);
  if (runtime.length > MAX_RUNTIME_BYTES) return new Response(null, { status: 404 });
  return new Response(runtime, { status: 200, headers: { 'Content-Type': 'text/javascript; charset=utf-8', 'Content-Length': String(runtime.length), 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' } });
}

function htmlHeaders(content: string): Record<string, string> {
  return {
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Length': String(Buffer.byteLength(content, 'utf8')),
    'Content-Security-Policy': "default-src 'none'; script-src 'unsafe-inline' agent-artifact:; connect-src 'none'; style-src 'unsafe-inline' agent-artifact: https:; img-src data: agent-artifact: https:; font-src data: agent-artifact: https:; media-src data: agent-artifact:; object-src 'none'; frame-src 'none'; form-action 'none'; base-uri agent-artifact:",
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  };
}
