import { accessSync, closeSync, constants, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, realpathSync, renameSync, writeFileSync } from 'node:fs';
import path from 'node:path';

export type RuntimeLogSettings = { enabled: boolean; main: boolean; host: boolean; renderer: boolean; directory: string };
export type DesktopSettings = { schemaVersion: 2; defaultWorkspacePath: string; maxIterations: number; runtimeLogs: RuntimeLogSettings };

export class SettingsStore {
  readonly #file: string;
  readonly #fallbackWorkspace: string;

  constructor(appHome: string) {
    this.#file = path.join(appHome, 'config', 'settings.json');
    this.#fallbackWorkspace = path.join(appHome, 'workspace');
  }

  load(): DesktopSettings {
    const fallback: DesktopSettings = { schemaVersion: 2, defaultWorkspacePath: this.#fallbackWorkspace, maxIterations: 10,
      runtimeLogs: { enabled: false, main: true, host: true, renderer: true, directory: path.join(path.dirname(this.#fallbackWorkspace), 'logs') } };
    if (!existsSync(this.#file)) return fallback;
    try {
      const value = JSON.parse(readFileSync(this.#file, 'utf8')) as Partial<DesktopSettings>;
      const maxIterations = Number.isSafeInteger(value.maxIterations) && Number(value.maxIterations) >= 1 && Number(value.maxIterations) <= 50
        ? Number(value.maxIterations) : fallback.maxIterations;
      const defaultWorkspacePath = typeof value.defaultWorkspacePath === 'string' && path.isAbsolute(value.defaultWorkspacePath)
        ? value.defaultWorkspacePath : fallback.defaultWorkspacePath;
      const rawLogs = typeof value.runtimeLogs === 'object' && value.runtimeLogs !== null ? value.runtimeLogs as Partial<RuntimeLogSettings> : {};
      const runtimeLogs: RuntimeLogSettings = {
        enabled: typeof rawLogs.enabled === 'boolean' ? rawLogs.enabled : fallback.runtimeLogs.enabled,
        main: typeof rawLogs.main === 'boolean' ? rawLogs.main : fallback.runtimeLogs.main,
        host: typeof rawLogs.host === 'boolean' ? rawLogs.host : fallback.runtimeLogs.host,
        renderer: typeof rawLogs.renderer === 'boolean' ? rawLogs.renderer : fallback.runtimeLogs.renderer,
        directory: typeof rawLogs.directory === 'string' && path.isAbsolute(rawLogs.directory) ? rawLogs.directory : fallback.runtimeLogs.directory
      };
      return { schemaVersion: 2, defaultWorkspacePath, maxIterations, runtimeLogs };
    } catch { return fallback; }
  }

  update(patch: Partial<Pick<DesktopSettings, 'defaultWorkspacePath' | 'maxIterations' | 'runtimeLogs'>>): DesktopSettings {
    const current = this.load();
    const next: DesktopSettings = { ...current, ...patch, schemaVersion: 2 };
    if (!path.isAbsolute(next.defaultWorkspacePath) || !Number.isSafeInteger(next.maxIterations) || next.maxIterations < 1 || next.maxIterations > 50 ||
      !validRuntimeLogs(next.runtimeLogs))
      throw new Error('SETTINGS_INVALID');
    mkdirSync(path.dirname(this.#file), { recursive: true });
    const temporary = `${this.#file}.${process.pid}.tmp`;
    writeFileSync(temporary, `${JSON.stringify(next, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
    const descriptor = openSync(temporary, 'r+');
    try { fsyncSync(descriptor); } finally { closeSync(descriptor); }
    renameSync(temporary, this.#file);
    return next;
  }

  selectWorkspace(selectedPath: string): DesktopSettings {
    const canonical=realpathSync.native(selectedPath);accessSync(canonical,constants.R_OK|constants.W_OK);
    return this.update({ defaultWorkspacePath: canonical });
  }

  selectLogDirectory(selectedPath: string): DesktopSettings {
    const canonical=realpathSync.native(selectedPath);accessSync(canonical,constants.R_OK|constants.W_OK);
    return this.update({ runtimeLogs: { ...this.load().runtimeLogs, directory: canonical } });
  }
}

export function maskedWorkspace(value: string): string {
  const parsed = path.parse(value); const parts = value.slice(parsed.root.length).split(path.sep).filter(Boolean);
  return parts.length <= 2 ? `${parsed.root}${parts.join(path.sep)}` : `${parsed.root}…${path.sep}${parts.slice(-2).join(path.sep)}`;
}

function validRuntimeLogs(value: RuntimeLogSettings): boolean {
  return typeof value.enabled === 'boolean' && typeof value.main === 'boolean' && typeof value.host === 'boolean' &&
    typeof value.renderer === 'boolean' && typeof value.directory === 'string' && path.isAbsolute(value.directory);
}
