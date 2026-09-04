import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync, realpathSync } from 'node:fs';
import path from 'node:path';
import { extractFile, listPackage } from '@electron/asar';
import { validateReleaseManifest } from '../desktop-shell/dist/release-manifest.js';

const root = path.resolve(import.meta.dirname, '..');
const unpacked = path.resolve(process.argv[2] ?? path.join(root, 'dist-release', 'win-unpacked'));
const expectedChannel = process.argv[3] ?? 'internal';
const resources = path.join(unpacked, 'resources');
const asar = path.join(resources, 'app.asar');
const manifestFile = path.join(resources, 'release-manifest.json');
const packageJson = JSON.parse(extractFile(asar, 'package.json').toString('utf8'));

const manifest = validateReleaseManifest({
  packaged: true,
  installRoot: resources,
  rendererIndex: path.join(asar, 'desktop-ui', 'dist', 'index.html'),
  hostJar: path.join(resources, 'agent-host.jar'),
  javaExecutable: path.join(resources, 'jre', 'bin', 'javaw.exe'),
  builtinSkills: path.join(resources, 'skills'),
  providerCatalog: path.join(resources, 'provider-catalog.json'),
  releaseManifest: manifestFile,
  appHome: path.join(unpacked, '.unused-app-home')
}, packageJson.version);
assert.equal(manifest.channel, expectedChannel, 'release channel mismatch');
assert.equal(manifest.unsigned, expectedChannel !== 'production', 'release signature declaration mismatch');

const expectedResources = new Set([
  'agent-host.jar', 'app.asar', 'initial-mcp-catalog.json', 'jre', 'legal', 'provider-catalog.json', 'release-manifest.json', 'skills', 'THIRD_PARTY_NOTICES.txt'
]);
for (const name of readdirSync(resources)) assert.ok(expectedResources.has(name), `unexpected resource: ${name}`);

const forbiddenPath = /(^|[\\/])(?:src|test|tests|fixtures?|docs?|node_modules|\.git)([\\/]|$)|\.map$|(^|[\\/])\.env(?:\.|$)/i;
const forbiddenTools = new Set(['javac.exe', 'jar.exe', 'jlink.exe', 'jdeps.exe', 'jshell.exe']);
let unpackedFiles = 0;
walk(unpacked, entry => {
  const relative = path.relative(unpacked, entry).split(path.sep).join('/');
  assert.ok(!forbiddenPath.test(relative), `forbidden unpacked path: ${relative}`);
  assert.ok(!forbiddenTools.has(path.basename(entry).toLowerCase()), `JDK developer tool shipped: ${relative}`);
  assert.notEqual(path.basename(entry).toLowerCase(), 'elevate.exe', 'elevation helper shipped');
  unpackedFiles++;
});

const asarEntries = listPackage(asar);
for (const entry of asarEntries) assert.ok(!forbiddenPath.test(entry), `forbidden ASAR path: ${entry}`);
assert.ok(asarEntries.includes('\\desktop-shell\\dist\\main.js'));
assert.ok(asarEntries.includes('\\desktop-ui\\dist\\index.html'));
assert.ok(asarEntries.includes('\\desktop-shell\\dist\\runtime\\pptxgenjs\\index.cjs'));
assert.ok(asarEntries.includes('\\desktop-shell\\dist\\runtime\\pptxgenjs\\package.json'));

const privatePatterns = [
  /[A-Za-z]:\\Users\\[^\\\r\n"']+/i,
  /[A-Za-z]:\\05\.Project\\/i,
  /Bearer\s+[A-Za-z0-9._~+\/-]{20,}/i,
  /(?:api[-_ ]?key|authorization|cookie)["'\s:=]+(?:sk-|eyJ)[A-Za-z0-9._-]{12,}/i
];
const textExtensions = new Set(['.js', '.cjs', '.css', '.html', '.json']);
for (const entry of asarEntries) {
  if (!textExtensions.has(path.extname(entry).toLowerCase())) continue;
  const content = extractFile(asar, entry.replace(/^\\/, '')).toString('utf8');
  for (const pattern of privatePatterns) assert.ok(!pattern.test(content), `private content in ASAR entry: ${entry}`);
}
for (const file of [manifestFile, path.join(resources, 'initial-mcp-catalog.json'), path.join(resources, 'legal', 'EULA_zh-CN.txt'), path.join(resources, 'legal', 'PRIVACY_zh-CN.md'), path.join(resources, 'THIRD_PARTY_NOTICES.txt')]) {
  const content = readFileSync(file, 'utf8');
  for (const pattern of privatePatterns) assert.ok(!pattern.test(content), `private content in resource: ${path.basename(file)}`);
}

process.stdout.write(JSON.stringify({
  result: 'passed', version: manifest.appVersion, channel: manifest.channel,
  sourceRevision: manifest.sourceRevision, unpackedFiles, asarEntries: asarEntries.length,
  manifestSha256: sha256(manifestFile)
}) + '\n');

function walk(directory, onFile) {
  const canonicalRoot = realpathSync.native(directory);
  const visit = current => {
    for (const name of readdirSync(current)) {
      const entry = path.join(current, name);
      const stat = lstatSync(entry);
      assert.ok(!stat.isSymbolicLink(), `symlink in package: ${path.relative(canonicalRoot, entry)}`);
      if (stat.isDirectory()) visit(entry);
      else if (stat.isFile()) onFile(entry);
      else assert.fail(`unsupported package entry: ${path.relative(canonicalRoot, entry)}`);
    }
  };
  visit(canonicalRoot);
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}
