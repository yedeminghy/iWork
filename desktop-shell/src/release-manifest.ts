import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync, realpathSync } from 'node:fs';
import path from 'node:path';
import type { AppPaths } from './app-paths.js';

export type ReleaseManifest = {
  schemaVersion: 1;
  appId: string;
  appVersion: string;
  hostVersion: string;
  platform: 'win32';
  arch: 'x64';
  protocolMin: number;
  protocolMax: number;
  snapshotSchemaMax: number;
  dbSchemaMax: number;
  jdk: { feature: 21; vendor: string; runtimeHash: string };
  artifacts: { 'agent-host.jar': string; 'provider-catalog.json': string; 'initial-mcp-catalog.json': string; 'eula.zh-CN.txt': string; 'privacy.zh-CN.md': string; skills: string };
  sourceRevision: string;
  channel: 'internal' | 'beta' | 'production';
  unsigned: boolean;
};

export type ReleaseInfo = {
  appVersion: string;
  hostVersion: string;
  channel: 'internal' | 'beta' | 'production';
  signed: boolean;
  platform: 'win32';
  arch: 'x64';
};

export function validateReleaseManifest(paths: AppPaths, expectedVersion: string): ReleaseManifest {
  let value: unknown;
  try { value = JSON.parse(readFileSync(paths.releaseManifest, 'utf8')); }
  catch { throw new Error('RELEASE_MANIFEST_INVALID'); }
  if (!isManifest(value)) throw new Error('RELEASE_MANIFEST_UNSUPPORTED');
  if (value.appId !== 'com.candlefish.iwork' || value.appVersion !== expectedVersion || value.hostVersion !== expectedVersion ||
      value.platform !== process.platform || value.arch !== process.arch || value.protocolMin > 1 || value.protocolMax < 1 ||
      value.snapshotSchemaMax < 2 || value.dbSchemaMax !== 8 || (value.channel === 'production' && value.unsigned)) {
    throw new Error('RELEASE_MANIFEST_INVALID');
  }
  if (sha256File(paths.hostJar) !== value.artifacts['agent-host.jar'] ||
      sha256File(paths.providerCatalog) !== value.artifacts['provider-catalog.json'] ||
      sha256File(path.join(paths.installRoot, 'initial-mcp-catalog.json')) !== value.artifacts['initial-mcp-catalog.json'] ||
      sha256File(path.join(paths.installRoot, 'legal', 'EULA_zh-CN.txt')) !== value.artifacts['eula.zh-CN.txt'] ||
      sha256File(path.join(paths.installRoot, 'legal', 'PRIVACY_zh-CN.md')) !== value.artifacts['privacy.zh-CN.md'] ||
      canonicalTreeHash(paths.builtinSkills) !== value.artifacts.skills ||
      canonicalTreeHash(path.dirname(path.dirname(paths.javaExecutable))) !== value.jdk.runtimeHash) {
    throw new Error('INSTALL_RESOURCE_TAMPERED');
  }
  return value;
}

export function developmentReleaseManifest(version: string): ReleaseManifest {
  return {
    schemaVersion: 1, appId: 'com.candlefish.iwork', appVersion: version, hostVersion: version,
    platform: 'win32', arch: 'x64', protocolMin: 1, protocolMax: 1, snapshotSchemaMax: 2, dbSchemaMax: 8,
    jdk: { feature: 21, vendor: 'development', runtimeHash: 'sha256:development' },
    artifacts: { 'agent-host.jar': 'sha256:development', 'provider-catalog.json': 'sha256:development', 'initial-mcp-catalog.json': 'sha256:development', 'eula.zh-CN.txt': 'sha256:development', 'privacy.zh-CN.md': 'sha256:development', skills: 'sha256:development' },
    sourceRevision: 'development', channel: 'internal', unsigned: true
  };
}

export function releaseInfo(manifest: ReleaseManifest): ReleaseInfo {
  return { appVersion: manifest.appVersion, hostVersion: manifest.hostVersion, channel: manifest.channel,
    signed: !manifest.unsigned, platform: manifest.platform, arch: manifest.arch };
}

export function sha256File(file: string): string {
  return `sha256:${createHash('sha256').update(readFileSync(file)).digest('hex')}`;
}

export function canonicalTreeHash(root: string): string {
  const canonicalRoot = realpathSync.native(root);
  const hash = createHash('sha256');
  const visit = (directory: string): void => {
    for (const name of readdirSync(directory).sort((left, right) => left.localeCompare(right, 'en'))) {
      const entry = path.join(directory, name);
      const stat = lstatSync(entry);
      if (stat.isSymbolicLink() || (!stat.isDirectory() && !stat.isFile())) throw new Error('INSTALL_RESOURCE_TAMPERED');
      const relative = path.relative(canonicalRoot, entry).split(path.sep).join('/');
      hash.update(stat.isDirectory() ? `D\0${relative}\0` : `F\0${relative}\0`);
      if (stat.isDirectory()) visit(entry); else hash.update(readFileSync(entry));
    }
  };
  visit(canonicalRoot);
  return `sha256:${hash.digest('hex')}`;
}

function isManifest(value: unknown): value is ReleaseManifest {
  if (!value || typeof value !== 'object') return false;
  const item = value as Partial<ReleaseManifest>;
  return item.schemaVersion === 1 && typeof item.appId === 'string' && typeof item.appVersion === 'string' &&
    typeof item.hostVersion === 'string' && item.platform === 'win32' && item.arch === 'x64' &&
    Number.isInteger(item.protocolMin) && Number.isInteger(item.protocolMax) && Number.isInteger(item.snapshotSchemaMax) &&
    Number.isInteger(item.dbSchemaMax) && (item.channel === 'internal' || item.channel === 'beta' || item.channel === 'production') &&
    typeof item.unsigned === 'boolean' && typeof item.sourceRevision === 'string' && Boolean(item.jdk) &&
    item.jdk?.feature === 21 && typeof item.jdk.vendor === 'string' && /^sha256:[0-9a-f]{64}$/.test(item.jdk.runtimeHash) &&
    Boolean(item.artifacts) && /^sha256:[0-9a-f]{64}$/.test(item.artifacts?.['agent-host.jar'] ?? '') &&
    /^sha256:[0-9a-f]{64}$/.test(item.artifacts?.['provider-catalog.json'] ?? '') &&
    /^sha256:[0-9a-f]{64}$/.test(item.artifacts?.['initial-mcp-catalog.json'] ?? '') &&
    /^sha256:[0-9a-f]{64}$/.test(item.artifacts?.['eula.zh-CN.txt'] ?? '') &&
    /^sha256:[0-9a-f]{64}$/.test(item.artifacts?.['privacy.zh-CN.md'] ?? '') &&
    /^sha256:[0-9a-f]{64}$/.test(item.artifacts?.skills ?? '');
}
