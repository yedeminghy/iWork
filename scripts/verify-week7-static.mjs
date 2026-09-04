import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { resolveAppPaths } from '../desktop-shell/dist/app-paths.js';
import { canonicalTreeHash, sha256File, validateReleaseManifest } from '../desktop-shell/dist/release-manifest.js';

const temporary = mkdtempSync(path.join(os.tmpdir(), 'agent-week7-static-'));
try {
  const resources = path.join(temporary, 'resources');
  const appPath = path.join(resources, 'app.asar');
  const jre = path.join(resources, 'jre');
  const skills = path.join(resources, 'skills');
  const host = path.join(resources, 'agent-host.jar');
  const providerCatalog = path.join(resources, 'provider-catalog.json');
  const initialMcpCatalog = path.join(resources, 'initial-mcp-catalog.json');
  const eula = path.join(resources, 'legal', 'EULA_zh-CN.txt');
  const privacy = path.join(resources, 'legal', 'PRIVACY_zh-CN.md');
  const manifestFile = path.join(resources, 'release-manifest.json');
  mkdirSync(path.join(jre, 'bin'), { recursive: true });
  mkdirSync(path.join(skills, 'builtin'), { recursive: true });
  mkdirSync(path.join(appPath, 'desktop-ui', 'dist'), { recursive: true });
  mkdirSync(path.dirname(privacy), { recursive: true });
  writeFileSync(path.join(jre, 'bin', 'javaw.exe'), 'java');
  writeFileSync(host, 'host');
  writeFileSync(providerCatalog, '{"schemaVersion":1,"profiles":[]}');
  writeFileSync(initialMcpCatalog, '{"schemaVersion":1,"servers":[]}');
  writeFileSync(eula, 'eula');
  writeFileSync(privacy, 'privacy');
  writeFileSync(path.join(skills, 'builtin', 'SKILL.md'), 'skill');
  writeFileSync(path.join(appPath, 'desktop-ui', 'dist', 'index.html'), 'ui');
  writeFileSync(manifestFile, '{}');

  const paths = resolveAppPaths({
    packaged: true, appPath, resourcesPath: resources, moduleDirectory: temporary,
    appHome: path.join(temporary, 'home'), smokeMode: false,
    environment: {
      AGENT_DESKTOP_JAVA: 'C:\\untrusted.exe',
      AGENT_DESKTOP_HOST_JAR: 'C:\\untrusted.jar',
      AGENT_DESKTOP_BUILTIN_SKILLS: 'C:\\untrusted-skills'
    }
  });
  assert.equal(paths.hostJar, host);
  assert.equal(paths.javaExecutable, path.join(jre, 'bin', 'javaw.exe'));
  assert.equal(paths.builtinSkills, skills);
  assert.equal(paths.providerCatalog, providerCatalog);

  const validManifest = () => ({
    schemaVersion: 1,
    appId: 'com.candlefish.iwork',
    appVersion: '0.1.0',
    hostVersion: '0.1.0',
    platform: 'win32',
    arch: 'x64',
    protocolMin: 1,
    protocolMax: 1,
    snapshotSchemaMax: 2,
    dbSchemaMax: 8,
    jdk: { feature: 21, vendor: 'fixture', runtimeHash: canonicalTreeHash(jre) },
    artifacts: { 'agent-host.jar': sha256File(host), 'provider-catalog.json': sha256File(providerCatalog),
      'initial-mcp-catalog.json': sha256File(initialMcpCatalog), 'eula.zh-CN.txt': sha256File(eula),
      'privacy.zh-CN.md': sha256File(privacy), skills: canonicalTreeHash(skills) },
    sourceRevision: `source-sha256:${'a'.repeat(64)}`,
    channel: 'internal',
    unsigned: true
  });
  const writeManifest = value => writeFileSync(manifestFile, `${JSON.stringify(value)}\n`);

  writeManifest(validManifest());
  assert.equal(validateReleaseManifest(paths, '0.1.0').channel, 'internal');

  writeManifest({ ...validManifest(), channel: 'beta' });
  assert.equal(validateReleaseManifest(paths, '0.1.0').channel, 'beta');

  writeFileSync(host, 'tampered-host');
  assert.throws(() => validateReleaseManifest(paths, '0.1.0'), /INSTALL_RESOURCE_TAMPERED/);
  writeFileSync(host, 'host');

  writeManifest(validManifest());
  writeFileSync(providerCatalog, '{"tampered":true}');
  assert.throws(() => validateReleaseManifest(paths, '0.1.0'), /INSTALL_RESOURCE_TAMPERED/);
  writeFileSync(providerCatalog, '{"schemaVersion":1,"profiles":[]}');

  writeManifest(validManifest());
  writeFileSync(initialMcpCatalog, '{"tampered":true}');
  assert.throws(() => validateReleaseManifest(paths, '0.1.0'), /INSTALL_RESOURCE_TAMPERED/);
  writeFileSync(initialMcpCatalog, '{"schemaVersion":1,"servers":[]}');

  writeManifest(validManifest());
  writeFileSync(eula, 'tampered-eula');
  assert.throws(() => validateReleaseManifest(paths, '0.1.0'), /INSTALL_RESOURCE_TAMPERED/);
  writeFileSync(eula, 'eula');

  writeManifest(validManifest());
  writeFileSync(privacy, 'tampered-privacy');
  assert.throws(() => validateReleaseManifest(paths, '0.1.0'), /INSTALL_RESOURCE_TAMPERED/);
  writeFileSync(privacy, 'privacy');

  writeManifest(validManifest());
  writeFileSync(path.join(skills, 'builtin', 'SKILL.md'), 'tampered-skill');
  assert.throws(() => validateReleaseManifest(paths, '0.1.0'), /INSTALL_RESOURCE_TAMPERED/);
  writeFileSync(path.join(skills, 'builtin', 'SKILL.md'), 'skill');

  writeManifest(validManifest());
  writeFileSync(path.join(jre, 'bin', 'javaw.exe'), 'tampered-java');
  assert.throws(() => validateReleaseManifest(paths, '0.1.0'), /INSTALL_RESOURCE_TAMPERED/);
  writeFileSync(path.join(jre, 'bin', 'javaw.exe'), 'java');

  writeFileSync(manifestFile, '{');
  assert.throws(() => validateReleaseManifest(paths, '0.1.0'), /RELEASE_MANIFEST_INVALID/);
  writeManifest({ ...validManifest(), schemaVersion: 2 });
  assert.throws(() => validateReleaseManifest(paths, '0.1.0'), /RELEASE_MANIFEST_UNSUPPORTED/);
  writeManifest({ ...validManifest(), channel: 'production', unsigned: true });
  assert.throws(() => validateReleaseManifest(paths, '0.1.0'), /RELEASE_MANIFEST_INVALID/);
  writeManifest(validManifest());
  assert.throws(() => validateReleaseManifest(paths, '0.1.1'), /RELEASE_MANIFEST_INVALID/);

  process.stdout.write(JSON.stringify({ result: 'passed', checks: [
    'packaged-overrides-ignored', 'manifest-valid', 'host-tamper', 'provider-catalog-tamper', 'skills-tamper', 'jre-tamper',
    'initial-mcp-tamper', 'eula-tamper', 'privacy-tamper', 'malformed-manifest', 'unknown-manifest-schema', 'unsigned-production', 'version-mismatch'
  ] }) + '\n');
} finally {
  rmSync(temporary, { recursive: true, force: true });
}
