import { safeStorage } from 'electron';
import { mkdir, readdir, readFile, rename, unlink, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import path from 'node:path';

export class CredentialStore {
  constructor(private readonly appHome: string) {}

  async save(providerId: 'deepseek' | 'qwen', apiKey: string): Promise<void> {
    if (!safeStorage.isEncryptionAvailable()) throw new Error('OS credential encryption is unavailable');
    if (!/^sk-[A-Za-z0-9_-]{16,}$/.test(apiKey)) throw new Error('Invalid provider key format');
    const directory = path.join(this.appHome, 'secrets');
    await mkdir(directory, { recursive: true });
    const target = path.join(directory, `${providerId}.key`);
    const temporary = target + '.tmp';
    const encrypted = safeStorage.encryptString(apiKey).toString('base64');
    await writeFile(temporary, encrypted, { encoding: 'ascii', mode: 0o600 });
    await rename(temporary, target);
  }

  async load(providerId: 'deepseek' | 'qwen'): Promise<string | undefined> {
    if (!safeStorage.isEncryptionAvailable()) throw new Error('OS credential encryption is unavailable');
    try {
      const encrypted = await readFile(path.join(this.appHome, 'secrets', `${providerId}.key`), 'ascii');
      return safeStorage.decryptString(Buffer.from(encrypted, 'base64'));
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return undefined;
      throw error;
    }
  }

  async delete(providerId: 'deepseek' | 'qwen'): Promise<void> {
    const target = path.join(this.appHome, 'secrets', `${providerId}.key`);
    await Promise.all([target, target + '.tmp'].map(async file => {
      try { await unlink(file); }
      catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
    }));
  }

  async saveMcp(currentRef: string | undefined, bearerToken: string): Promise<string> {
    if (!safeStorage.isEncryptionAvailable()) throw new Error('OS credential encryption is unavailable');
    if (bearerToken.length < 1 || bearerToken.length > 16_384 || /[\r\n]/.test(bearerToken)) throw new Error('Invalid MCP bearer token');
    const credentialRef = currentRef && /^cred_[A-Za-z0-9-]{8,}$/.test(currentRef) ? currentRef : `cred_${randomUUID()}`;
    const directory = path.join(this.appHome, 'secrets', 'mcp');
    await mkdir(directory, { recursive: true });
    const target = path.join(directory, `${credentialRef}.key`);
    const temporary = target + '.tmp';
    const encrypted = safeStorage.encryptString(bearerToken).toString('base64');
    await writeFile(temporary, encrypted, { encoding: 'ascii', mode: 0o600 });
    await rename(temporary, target);
    return credentialRef;
  }

  async loadMcp(credentialRef: string | null | undefined): Promise<string | undefined> {
    if (!credentialRef) return undefined;
    if (!/^cred_[A-Za-z0-9-]{8,}$/.test(credentialRef) || !safeStorage.isEncryptionAvailable()) throw new Error('MCP credential is unavailable');
    try {
      const encrypted = await readFile(path.join(this.appHome, 'secrets', 'mcp', `${credentialRef}.key`), 'ascii');
      return safeStorage.decryptString(Buffer.from(encrypted, 'base64'));
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return undefined;
      throw error;
    }
  }

  async deleteMcp(credentialRef: string | null | undefined): Promise<void> {
    if (!credentialRef) return;
    if (!/^cred_[A-Za-z0-9-]{8,}$/.test(credentialRef)) throw new Error('Invalid MCP credential reference');
    const target = path.join(this.appHome, 'secrets', 'mcp', `${credentialRef}.key`);
    await Promise.all([target, target + '.tmp'].map(async file => {
      try { await unlink(file); }
      catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
    }));
  }

  /** Removes encrypted MCP credentials left orphaned by a crash between Host deletion and local cleanup. */
  async pruneMcp(activeCredentialRefs: readonly string[]): Promise<void> {
    const directory = path.join(this.appHome, 'secrets', 'mcp');
    let entries;
    try { entries = await readdir(directory, { withFileTypes: true }); }
    catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return;
      throw error;
    }
    const active = new Set(activeCredentialRefs);
    await Promise.all(entries.filter(entry => entry.isFile() && /^cred_[A-Za-z0-9-]{8,}\.key$/.test(entry.name))
      .map(entry => entry.name.slice(0, -4)).filter(credentialRef => !active.has(credentialRef))
      .map(credentialRef => this.deleteMcp(credentialRef)));
  }
}
