import { app, BrowserWindow, protocol } from 'electron';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { ARTIFACT_RESOURCE_SCHEME, ArtifactResourceProtocol } from '../desktop-shell/dist/artifact-resource-protocol.js';

const sourcePath = process.argv.slice(1).find(value => /\.html?$/i.test(value));
const resultPath = process.argv.slice(1).find(value => /\.json$/i.test(value));
if (!sourcePath) throw new Error('Usage: electron scripts/verify-html-preview.mjs <html-file>');

protocol.registerSchemesAsPrivileged([{ scheme: ARTIFACT_RESOURCE_SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true } }]);
app.commandLine.appendSwitch('disable-gpu');
void app.whenReady().then(run).catch(async error => {
  if (resultPath) await writeFile(resultPath, JSON.stringify({ result: 'failed', error: String(error) }), 'utf8');
  app.exit(1);
});

async function run() {
  const resources = new ArtifactResourceProtocol(
    path.resolve('desktop-shell/dist/runtime/echarts/echarts.min.js'),
    path.resolve('desktop-shell/dist/runtime/tailwind/index.cjs'),
    path.resolve('desktop-shell/dist/runtime/lucide/lucide.min.js')
  );
  resources.register();
  const content = await readFile(sourcePath, 'utf8');
  const preview = await resources.grantHtml(path.dirname(sourcePath), content);
  const window = new BrowserWindow({ show: false, webPreferences: { contextIsolation: true, sandbox: true, nodeIntegration: false, webSecurity: true } });

  let exitCode = 0;
  try {
  const parent = `<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; frame-src ${ARTIFACT_RESOURCE_SCHEME}:"><iframe sandbox="allow-scripts" src="${preview.url}"></iframe>`;
  await window.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(parent)}`);
  const deadline = Date.now() + 20_000;
  let frame;
  while (Date.now() < deadline) {
    frame = window.webContents.mainFrame.frames.find(candidate => candidate.url === preview.url);
    if (frame) break;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  if (!frame) throw new Error('HTML preview frame did not load');
  const result = await Promise.race([frame.executeJavaScript(`new Promise(resolve => {
    const deadline = Date.now() + 10000;
    const inspect = () => {
      const rows = document.querySelectorAll('table tbody tr').length;
      const chart = document.querySelector('#chart, [id^="chart"]');
      const rendered = Boolean(chart?.querySelector('canvas, svg'));
      const utilityElement = document.querySelector('.flex');
      const tailwindStyled = !utilityElement || getComputedStyle(utilityElement).display === 'flex';
      const lucideRequested = Boolean(document.querySelector('[data-lucide]'));
      const lucideRendered = !lucideRequested || Boolean(document.querySelector('svg.lucide'));
      if ((rows > 0 && rendered && tailwindStyled && lucideRendered) || Date.now() >= deadline) resolve({ rows, rendered, tailwindStyled, lucideRendered, echarts: typeof window.echarts, chartChildren: chart?.childElementCount ?? -1 });
      else setTimeout(inspect, 50);
    };
    inspect();
  })`, true), new Promise((_, reject) => setTimeout(() => reject(new Error(`HTML preview inspection timed out; frames=${JSON.stringify(window.webContents.mainFrame.frames.map(item => item.url))}`)), 15_000))]);
  if (result.rows < 1 || !result.rendered || !result.tailwindStyled || !result.lucideRendered || result.echarts !== 'object') throw new Error(`HTML preview assertions failed: ${JSON.stringify(result)}`);
  const report = { result: 'passed', ...result };
  if (resultPath) await writeFile(resultPath, JSON.stringify(report), 'utf8');
  console.log(JSON.stringify(report));
  } catch (error) {
    exitCode = 1;
    if (resultPath) await writeFile(resultPath, JSON.stringify({ result: 'failed', error: String(error) }), 'utf8');
    console.error(error);
  } finally {
    await resources.release(preview.previewId);
    window.destroy();
    app.exit(exitCode);
  }
}
