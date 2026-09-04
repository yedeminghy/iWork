import { spawn, type ChildProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import { copyFile, mkdir, mkdtemp, open, rm, stat } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const CONVERSION_TIMEOUT_MS = 45_000;
const MAX_SOURCE_BYTES = 25 * 1024 * 1024;
const MAX_PDF_BYTES = 80 * 1024 * 1024;

export class ArtifactLayoutConverter {
  private readonly children = new Set<ChildProcess>();
  private disposed = false;

  async generate(sourcePath: string): Promise<{ pdfPath: string; tempRoot: string }> {
    if (this.disposed || this.children.size >= 2) throw new Error('ARTIFACT_LAYOUT_BUSY');
    const converter = trustedConverterPath();
    if (!converter) throw new Error('ARTIFACT_LAYOUT_CONVERTER_UNAVAILABLE');
    const extension = path.extname(sourcePath).toLocaleLowerCase();
    if (!['.docx', '.xlsx', '.xls', '.pptx'].includes(extension)) throw new Error('ARTIFACT_LAYOUT_UNSUPPORTED');
    const sourceStat = await stat(sourcePath).catch(() => undefined);
    if (!sourceStat?.isFile() || sourceStat.size > MAX_SOURCE_BYTES) throw new Error('ARTIFACT_PREVIEW_TOO_LARGE');
    const tempRoot = await mkdtemp(path.join(os.tmpdir(), 'agent-desktop-preview-'));
    try {
      const inputDirectory = path.join(tempRoot, 'input');
      const outputDirectory = path.join(tempRoot, 'output');
      const profileDirectory = path.join(tempRoot, 'profile');
      await Promise.all([mkdir(inputDirectory), mkdir(outputDirectory), mkdir(profileDirectory)]);
      const inputPath = path.join(inputDirectory, `source${extension}`);
      await copyFile(sourcePath, inputPath);
      const args = ['--headless', '--nologo', '--nodefault', '--nolockcheck', '--norestore', '--invisible', `-env:UserInstallation=${pathToFileURL(profileDirectory).href}`, '--convert-to', 'pdf', '--outdir', outputDirectory, inputPath];
      await this.run(converter, args, tempRoot);
      const pdfPath = path.join(outputDirectory, 'source.pdf');
      const pdfStat = await stat(pdfPath).catch(() => undefined);
      if (!pdfStat?.isFile() || pdfStat.size < 5 || pdfStat.size > MAX_PDF_BYTES) throw new Error('ARTIFACT_LAYOUT_INVALID_OUTPUT');
      const handle = await open(pdfPath, 'r');
      const signatureBytes = Buffer.alloc(5);
      try { await handle.read(signatureBytes, 0, 5, 0); } finally { await handle.close(); }
      if (signatureBytes.toString('ascii') !== '%PDF-') throw new Error('ARTIFACT_LAYOUT_INVALID_OUTPUT');
      return { pdfPath, tempRoot };
    } catch (error) {
      await rm(tempRoot, { recursive: true, force: true }).catch(() => undefined);
      throw error;
    }
  }

  dispose(): void {
    this.disposed = true;
    for (const child of this.children) child.kill('SIGKILL');
    this.children.clear();
  }

  private run(executable: string, args: string[], tempRoot: string): Promise<void> {
    return new Promise((resolve, reject) => {
      if (this.disposed) { reject(new Error('ARTIFACT_LAYOUT_BUSY')); return; }
      const environment = Object.fromEntries(Object.entries(process.env).filter(([name, value]) => value !== undefined && !/(TOKEN|SECRET|PASSWORD|API_KEY|BEARER|CREDENTIAL)/i.test(name))) as NodeJS.ProcessEnv;
      environment.TEMP = tempRoot;
      environment.TMP = tempRoot;
      const child = spawn(executable, args, { cwd: tempRoot, env: environment, windowsHide: true, stdio: 'ignore' });
      this.children.add(child);
      let timedOut = false;
      const timer = setTimeout(() => { timedOut = true; child.kill('SIGKILL'); }, CONVERSION_TIMEOUT_MS);
      const finish = () => { clearTimeout(timer); this.children.delete(child); };
      child.once('error', error => { finish(); reject(new Error(`ARTIFACT_LAYOUT_START_FAILED:${error.name}`)); });
      child.once('exit', code => { finish(); if (this.disposed) reject(new Error('ARTIFACT_LAYOUT_CANCELLED')); else if (timedOut) reject(new Error('ARTIFACT_LAYOUT_TIMEOUT')); else if (code !== 0) reject(new Error('ARTIFACT_LAYOUT_FAILED')); else resolve(); });
    });
  }
}

function trustedConverterPath(): string | undefined {
  const resourcesPath=typeof process.resourcesPath==='string'?process.resourcesPath:'';
  const candidates = process.platform === 'win32'
    ? [resourcesPath?path.join(resourcesPath, 'office', 'program', 'soffice.exe'):'', path.join(process.env.ProgramFiles ?? 'C:\\Program Files', 'LibreOffice', 'program', 'soffice.exe'), path.join(process.env['ProgramFiles(x86)'] ?? 'C:\\Program Files (x86)', 'LibreOffice', 'program', 'soffice.exe')]
    : process.platform === 'darwin'
      ? [resourcesPath?path.join(resourcesPath, 'office', 'MacOS', 'soffice'):'', '/Applications/LibreOffice.app/Contents/MacOS/soffice']
      : [resourcesPath?path.join(resourcesPath, 'office', 'program', 'soffice'):'', '/usr/bin/soffice', '/usr/bin/libreoffice'];
  return candidates.find(candidate => candidate.length>0&&existsSync(candidate));
}
