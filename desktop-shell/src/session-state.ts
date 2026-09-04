import { randomUUID } from 'node:crypto';
import { closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import path from 'node:path';

export type DesktopSessionStatus = 'STARTING' | 'READY' | 'RESTARTING' | 'CLEAN_EXIT';
type DesktopSession = { schemaVersion: 1; sessionId: string; appVersion: string; pid: number; startedAt: string; status: DesktopSessionStatus };

export class SessionStateStore {
  readonly recoveredFromUncleanExit: boolean;
  readonly sessionId = randomUUID();
  #startedAt = new Date().toISOString();
  #file: string;
  constructor(appHome: string, private readonly appVersion: string) {
    const directory = path.join(appHome, 'run');
    mkdirSync(directory, { recursive: true });
    this.#file = path.join(directory, 'desktop-session.json');
    this.recoveredFromUncleanExit = this.previousWasUnclean();
  }
  write(status: DesktopSessionStatus): void {
    const value: DesktopSession = { schemaVersion: 1, sessionId: this.sessionId, appVersion: this.appVersion,
      pid: process.pid, startedAt: this.#startedAt, status };
    const temporary = `${this.#file}.${process.pid}.tmp`;
    writeFileSync(temporary, `${JSON.stringify(value)}\n`, { encoding: 'utf8', mode: 0o600 });
    const descriptor = openSync(temporary, 'r+');
    try { fsyncSync(descriptor); } finally { closeSync(descriptor); }
    renameSync(temporary, this.#file);
  }
  private previousWasUnclean(): boolean {
    if (!existsSync(this.#file)) return false;
    try {
      const value = JSON.parse(readFileSync(this.#file, 'utf8')) as { status?: unknown };
      return value.status === 'STARTING' || value.status === 'READY' || value.status === 'RESTARTING';
    } catch { return true; }
  }
}
