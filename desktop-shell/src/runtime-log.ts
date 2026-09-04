import { appendFileSync, existsSync, mkdirSync, renameSync, statSync, unlinkSync } from 'node:fs';
import path from 'node:path';

export type RuntimeLogSource = 'main' | 'host' | 'renderer';
export type RuntimeLogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

export type RuntimeLogConfiguration = {
  enabled: boolean;
  main: boolean;
  host: boolean;
  renderer: boolean;
  directory: string;
};

type RuntimeLogOptions = { maxFileBytes?: number; maxFiles?: number };

const DEFAULT_MAX_FILE_BYTES = 10 * 1024 * 1024;
const DEFAULT_MAX_FILES = 5;

export class RuntimeLogService {
  readonly #maxFileBytes: number;
  readonly #maxFiles: number;
  #configuration: RuntimeLogConfiguration;

  constructor(configuration: RuntimeLogConfiguration, options: RuntimeLogOptions = {}) {
    this.#configuration = validateConfiguration(configuration);
    this.#maxFileBytes = positiveInteger(options.maxFileBytes, DEFAULT_MAX_FILE_BYTES);
    this.#maxFiles = Math.max(1, positiveInteger(options.maxFiles, DEFAULT_MAX_FILES));
  }

  configure(configuration: RuntimeLogConfiguration): void {
    this.#configuration = validateConfiguration(configuration);
  }

  write(source: RuntimeLogSource, level: RuntimeLogLevel, message: string): void {
    const configuration = this.#configuration;
    if (!configuration.enabled || !configuration[source]) return;
    try {
      mkdirSync(configuration.directory, { recursive: true });
      const target = path.join(configuration.directory, `${source}.log`);
      const line = `${new Date().toISOString()} ${level} ${redactRuntimeLog(message)}\n`;
      this.rotateIfRequired(target, Buffer.byteLength(line, 'utf8'));
      appendFileSync(target, line, { encoding: 'utf8', mode: 0o600 });
    } catch {
      // Logging must never interrupt the application flow.
    }
  }

  private rotateIfRequired(target: string, incomingBytes: number): void {
    if (!existsSync(target) || statSync(target).size + incomingBytes <= this.#maxFileBytes) return;
    const oldest = rotatedPath(target, this.#maxFiles - 1);
    if (oldest && existsSync(oldest)) unlinkSync(oldest);
    for (let index = this.#maxFiles - 2; index >= 1; index--) {
      const current = rotatedPath(target, index);
      const next = rotatedPath(target, index + 1);
      if (current && next && existsSync(current)) renameSync(current, next);
    }
    if (this.#maxFiles > 1) renameSync(target, rotatedPath(target, 1)!);
    else unlinkSync(target);
  }
}

export function redactRuntimeLog(value: string): string {
  return value
    .replace(/\b(authorization|proxy-authorization|api[-_ ]?key|token|password|secret|cookie|set-cookie)\b["'\s:=]+(?:Bearer\s+)?[^\s,"']+/gi, '$1=[REDACTED]')
    .replace(/\bBearer\s+[A-Za-z0-9._~+/=-]+/gi, 'Bearer [REDACTED]')
    .replace(/[A-Za-z]:\\Users\\[^\\\r\n"']+(?:\\[^\r\n"']*)?/gi, '[PRIVATE_PATH]')
    .replace(/\/(?:Users|home)\/[^/\r\n"']+(?:\/[^\r\n"']*)?/g, '[PRIVATE_PATH]')
    .replace(/[\r\n]+/g, ' | ')
    .slice(0, 16_384);
}

function rotatedPath(target: string, index: number): string | undefined {
  if (index < 1) return undefined;
  const extension = path.extname(target);
  return `${target.slice(0, -extension.length)}.${index}${extension}`;
}

function validateConfiguration(configuration: RuntimeLogConfiguration): RuntimeLogConfiguration {
  if (!path.isAbsolute(configuration.directory)) throw new Error('LOG_SETTINGS_INVALID');
  return { ...configuration, directory: path.resolve(configuration.directory) };
}

function positiveInteger(value: number | undefined, fallback: number): number {
  return Number.isSafeInteger(value) && Number(value) > 0 ? Number(value) : fallback;
}
