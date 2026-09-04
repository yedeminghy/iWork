import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createRequire } from 'node:module';
import { createInterface } from 'node:readline';
import path from 'node:path';
import type { AgUiEvent, HostState, RunAgentInput, ThreadPage, ThreadSnapshot, ThreadSummary, TurnOperationResult, RetryPreparation, DiagnosticPreview, ProviderProfileSummary,ProviderCheckResult,McpServerDraft, McpServerSummary, ToolDescriptor, McpDiagnostic, SkillScope, SkillStatus, SkillSummary, SkillDetail, SkillResourceDescriptor, SkillResourceView, SkillRefreshResult, SkillImportManifest, SkillInstallResult, SkillInstallationInfo, ApprovalDecision, ApprovalResolution, FileRootMode, FileRootSummary } from './protocol.js';
import type { RuntimeLogLevel, RuntimeLogSource } from './runtime-log.js';

type ReadyMessage = { type: 'ready'; protocolVersion: number; port: number; pid: number; nonce: string; interruptedTurns?:number;cancelledTurns?:number };
type HostLaunch = { javaExecutable:string;hostJar:string;appHome:string;builtinSkills:string;installRoot:string;nodeModules:string;pptxRuntimeAvailable:boolean;appVersion:string;dbSchemaMax:number;workspacePath:string };
const CRASH_WINDOW_MS=10*60_000;

export class HostSupervisor {
  #process?: ChildProcessWithoutNullStreams;
  #baseUrl?: string;
  #token?: string;
  #activeSkillInspection?: AbortController;
  #state: HostState = { status: 'stopped' };
  #launch?:HostLaunch;
  #intentionalExits=new WeakSet<ChildProcessWithoutNullStreams>();
  #listeners=new Set<(state:HostState)=>void>();
  #lastRuntimeCrashAt=0;
  #runtimeRestartUsed=false;

  constructor(private readonly runtimeLog?: (source:RuntimeLogSource,level:RuntimeLogLevel,message:string)=>void) {}

  get state(): HostState { return this.#state; }

  processIdForWeek7Smoke():number{
    if(process.env.AGENT_DESKTOP_W7_SMOKE!=='1')throw new Error('Week 7 smoke access is disabled');
    const pid=this.#process?.pid;if(!pid)throw new Error('Host process is unavailable');return pid;
  }

  onState(listener:(state:HostState)=>void):()=>void{this.#listeners.add(listener);return()=>this.#listeners.delete(listener);}

  async start(javaExecutable: string, hostJar: string, appHome: string, builtinSkills: string,
              installRoot=builtinSkills,nodeModules=path.join(installRoot,'app.asar','desktop-shell','dist','runtime'),appVersion='0.1.0',dbSchemaMax=8,workspacePath=path.join(appHome,'workspace')): Promise<void> {
    if (this.#process) throw new Error('Host is already running');
    const pptxRuntimeAvailable=probeNodeModule(nodeModules,'pptxgenjs');
    if(!pptxRuntimeAvailable)this.emitLog('main','WARN','PPTX runtime preflight failed; the pptx Skill will remain unavailable.');
    this.#launch={javaExecutable,hostJar,appHome,builtinSkills,installRoot,nodeModules,pptxRuntimeAvailable,appVersion,dbSchemaMax,workspacePath};
    this.updateState({ status: 'starting' });
    let lastError: unknown;
    for (let attempt = 1; attempt <= 2; attempt++) {
      try {
        await this.startOnce(this.#launch,hostReadyTimeoutMs());
        this.updateState({ status: 'ready' });
        return;
      } catch (error) {
        lastError = error;
        const child = this.#process;
        if (child) await this.terminateChild(child);
        if (attempt === 1) {
          this.updateState({ status: 'starting' });
          this.emitLog('main','WARN','Initial Host start failed; retrying once.');
        }
      }
    }
    this.updateState({ status: 'failed', diagnosticCode: 'HOST_START_FAILED',diagnosticId:diagnosticId() });
    throw lastError;
  }

  private async startOnce(launch:HostLaunch,timeoutMs:number): Promise<void> {
    const token = randomBytes(32).toString('base64url');
    const fixtureFlag = process.env.AGENT_DESKTOP_TEST_FIXTURES === '1' ? 'true' : 'false';
    const child = spawn(launch.javaExecutable, ['-XX:TieredStopAtLevel=1',`-Dagentdesktop.test-fixtures=${fixtureFlag}`,
      `-Dagentdesktop.builtin-skills=${launch.builtinSkills}`,`-Dagentdesktop.install-dir=${launch.installRoot}`,
      `-Dagentdesktop.node-executable=${process.env.AGENT_DESKTOP_NODE ?? process.execPath}`,
      `-Dagentdesktop.node-electron=${process.env.AGENT_DESKTOP_NODE ? 'false' : 'true'}`,
      `-Dagentdesktop.node-modules=${launch.nodeModules}`,
      `-Dagentdesktop.pptx-runtime-available=${launch.pptxRuntimeAvailable}`,
      ...(process.env.AGENT_DESKTOP_PYTHON ? [`-Dagentdesktop.python-executable=${process.env.AGENT_DESKTOP_PYTHON}`] : []),
      `-Dagentdesktop.app-version=${launch.appVersion}`,`-Dagentdesktop.db-schema-max=${launch.dbSchemaMax}`,
      `-Dagentdesktop.workspace=${launch.workspacePath}`,'-jar',launch.hostJar], {
      stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true, shell: false
    });
    this.#process = child;
    this.#token = token;
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', data => {
      const message=redact(String(data));
      process.stderr.write(`[agent-host] ${message}`);
      this.runtimeLog?.('host',hostLogLevel(message),message.trimEnd());
    });
    child.once('exit', code => {
      if (this.#process !== child) return;
      this.#process = undefined;
      this.#baseUrl = undefined;
      this.#token = undefined;
      if(this.#intentionalExits.has(child)||this.#state.status==='stopped'||this.#state.status==='stopping')return;
      if(this.#state.status==='ready')void this.recoverRuntimeCrash(code);
      else if(this.#state.status!=='restarting')this.updateState({status:'failed',diagnosticCode:`HOST_EXIT_${code??'UNKNOWN'}`,diagnosticId:diagnosticId()});
    });

    child.stdin.write(JSON.stringify({ type: 'bootstrap', protocolVersion: 1, sessionToken: token, appHome:launch.appHome }) + '\n');
    // Cold Spring/Flyway starts can be slow on Windows machines with real-time scanning.
    // Keep the wait bounded, but allow acceptance runs to raise it without changing code.
    const ready = await readReady(child, timeoutMs);
    if (ready.protocolVersion !== 1 || ready.port < 1 || ready.port > 65535||!ready.nonce?.match(/^[A-Za-z0-9_-]{20,64}$/)) throw new Error('Protocol mismatch');
    this.#baseUrl = `http://127.0.0.1:${ready.port}`;
    const response = await this.request('/internal/health');
    if (!response.ok) throw new Error(`Health check failed: ${response.status}`);
    const health = await response.json() as { status?: string; protocolVersion?: number };
    if (health.status !== 'READY' || health.protocolVersion !== 1) throw new Error('Invalid health response');
  }

  private async recoverRuntimeCrash(code:number|null):Promise<void>{
    const launch=this.#launch;if(!launch)return;
    const now=Date.now();
    if(this.#runtimeRestartUsed&&now-this.#lastRuntimeCrashAt<CRASH_WINDOW_MS){
      this.updateState({status:'crash-loop',diagnosticCode:'HOST_CRASH_LOOP',diagnosticId:diagnosticId()});return;
    }
    this.#runtimeRestartUsed=true;this.#lastRuntimeCrashAt=now;
    this.updateState({status:'restarting',diagnosticCode:`HOST_EXIT_${code??'UNKNOWN'}`,diagnosticId:diagnosticId()});
    await new Promise(resolve=>setTimeout(resolve,500));
    if(this.#state.status!=='restarting')return;
    try{await this.startOnce(launch,hostRecoveryTimeoutMs());this.updateState({status:'ready'});}
    catch{const child=this.#process;if(child)await this.terminateChild(child);this.updateState({status:'crash-loop',diagnosticCode:'HOST_CRASH_LOOP',diagnosticId:diagnosticId()});}
  }

  private updateState(state:HostState):void{
    this.#state=state;
    for(const listener of this.#listeners)try{listener(state);}catch{
      this.emitLog('main','ERROR',`HOST_STATE_LISTENER_FAILED ${diagnosticId()}`);
    }
  }

  async run(input: RunAgentInput, onEvent: (event: AgUiEvent) => void): Promise<void> {
    const response = await this.request('/agui/run', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input)
    });
    if (!response.ok || !response.body) throw new Error(`Agent run failed: ${response.status}`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let pending = '';
    for (;;) {
      const { done, value } = await reader.read();
      pending += decoder.decode(value, { stream: !done });
      const frames = pending.split(/\r?\n\r?\n/);
      pending = frames.pop() ?? '';
      for (const frame of frames) {
        const data = frame.split(/\r?\n/).filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart()).join('\n');
        if (data) onEvent(JSON.parse(data) as AgUiEvent);
      }
      if (done) break;
    }
  }

  async cancel(turnId: string): Promise<TurnOperationResult> {
    const response = await this.request(`/api/v1/turns/${encodeURIComponent(turnId)}:cancel`, { method: 'POST' });
    if (!response.ok) throw new Error(`Cancel failed: ${response.status}`);
    return await response.json() as TurnOperationResult;
  }

  async steer(turnId:string,message:string):Promise<void>{const response=await this.request(`/api/v1/turns/${encodeURIComponent(turnId)}:steer`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message})});if(!response.ok)throw await providerError(response);}
  async prepareRetry(turnId:string):Promise<RetryPreparation>{const response=await this.request(`/api/v1/turns/${encodeURIComponent(turnId)}:retry`,{method:'POST'});if(!response.ok)throw await providerError(response);return await response.json() as RetryPreparation;}
  async previewDiagnostics():Promise<DiagnosticPreview>{const response=await this.request('/api/v1/diagnostics/preview');if(!response.ok)throw new Error(`Diagnostic preview failed: ${response.status}`);return await response.json() as DiagnosticPreview;}

  async listProviderProfiles():Promise<ProviderProfileSummary[]>{const r=await this.request('/api/v1/providers');if(!r.ok)throw new Error(`Provider list failed: ${r.status}`);return await r.json() as ProviderProfileSummary[];}
  async createProviderSecret(providerId:'deepseek'|'qwen',apiKey:string):Promise<{secretSessionId:string;expiresAt:string}>{const r=await this.request('/api/v1/provider-secret-sessions',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({providerId,apiKey})});if(!r.ok)throw await providerError(r);return await r.json() as {secretSessionId:string;expiresAt:string};}
  async checkProvider(profileId:string,secretSessionId:string):Promise<ProviderCheckResult>{const r=await this.request(`/api/v1/providers/${encodeURIComponent(profileId)}:check`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({secretSessionId})});if(!r.ok)throw await providerError(r);return await r.json() as ProviderCheckResult;}
  async selectThreadProfile(threadId:string,profileId:string):Promise<ThreadSummary>{const r=await this.request(`/api/v1/threads/${encodeURIComponent(threadId)}/profile`,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({profileId})});if(!r.ok)throw await providerError(r);return await r.json() as ThreadSummary;}

  async listThreads(cursor?: string, limit = 50, status: 'ACTIVE' | 'ARCHIVED' = 'ACTIVE'): Promise<ThreadPage> {
    const query = new URLSearchParams({ status, limit: String(Math.max(1, Math.min(limit, 100))) });
    if (cursor) query.set('cursor', cursor);
    const response = await this.request(`/api/v1/threads?${query.toString()}`);
    if (!response.ok) throw new Error(`Thread list failed: ${response.status}`);
    return await response.json() as ThreadPage;
  }
  async snapshot(threadId: string): Promise<ThreadSnapshot> {
    const response = await this.request(`/api/v1/threads/${encodeURIComponent(threadId)}/snapshot`);
    if (!response.ok) throw new Error(`Snapshot failed: ${response.status}`);
    const snapshot=await response.json() as ThreadSnapshot;
    if(snapshot.schemaVersion>2)throw new Error('SNAPSHOT_SCHEMA_UNSUPPORTED');
    return snapshot;
  }
  async renameThread(threadId: string, title: string): Promise<ThreadSummary> {
    const response = await this.request(`/api/v1/threads/${encodeURIComponent(threadId)}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title }) });
    if (!response.ok) throw new Error(`Rename failed: ${response.status}`);
    return await response.json() as ThreadSummary;
  }
  async archiveThread(threadId: string): Promise<void> {
    const response = await this.request(`/api/v1/threads/${encodeURIComponent(threadId)}`, { method: 'DELETE' });
    if (!response.ok) throw new Error(`Archive failed: ${response.status}`);
  }
  async restoreThread(threadId: string): Promise<ThreadSummary> {
    const response = await this.request(`/api/v1/threads/${encodeURIComponent(threadId)}:restore`, { method: 'POST' });
    if (!response.ok) throw new Error(`Restore failed: ${response.status}`);
    return await response.json() as ThreadSummary;
  }

  async items(threadId: string): Promise<unknown[]> {
    const response = await this.request(`/api/v1/threads/${encodeURIComponent(threadId)}/items`);
    if (!response.ok) throw new Error(`Snapshot failed: ${response.status}`);
    return await response.json() as unknown[];
  }

  async listMcpServers():Promise<{items:McpServerSummary[]}>{const r=await this.request('/api/v1/mcp/servers');if(!r.ok)throw new Error(`MCP list failed: ${r.status}`);return await r.json() as {items:McpServerSummary[]};}
  async saveMcpServer(input:McpServerDraft):Promise<McpServerSummary>{const p=input.id?`/api/v1/mcp/servers/${encodeURIComponent(input.id)}`:'/api/v1/mcp/servers';const r=await this.request(p,{method:input.id?'PATCH':'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(input)});if(!r.ok)throw new Error(`MCP save failed: ${r.status}`);return await r.json() as McpServerSummary;}
  async connectMcpServer(id:string,bearerToken?:string):Promise<McpServerSummary>{const r=await this.request(`/api/v1/mcp/servers/${encodeURIComponent(id)}:connect`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({bearerToken})});if(!r.ok){let code='MCP_CONNECT_FAILED';try{const body=await r.json() as {code?:string};if(body.code)code=body.code;}catch{}throw new Error(`MCP connect failed: ${r.status} ${code}`);}return await r.json() as McpServerSummary;}
  async disconnectMcpServer(id:string):Promise<void>{const r=await this.request(`/api/v1/mcp/servers/${encodeURIComponent(id)}:disconnect`,{method:'POST'});if(!r.ok)throw new Error(`MCP disconnect failed: ${r.status}`);}
  async deleteMcpServer(id:string):Promise<void>{const r=await this.request(`/api/v1/mcp/servers/${encodeURIComponent(id)}`,{method:'DELETE'});if(!r.ok)throw new Error(`MCP delete failed: ${r.status}`);}
  async listMcpTools(id:string):Promise<ToolDescriptor[]>{const r=await this.request(`/api/v1/mcp/servers/${encodeURIComponent(id)}/tools`);if(!r.ok)throw new Error(`MCP tools failed: ${r.status}`);return await r.json() as ToolDescriptor[];}
  async mcpDiagnostic(id:string):Promise<McpDiagnostic>{const r=await this.request(`/api/v1/mcp/servers/${encodeURIComponent(id)}/diagnostic`);if(!r.ok)throw new Error(`MCP diagnostic failed: ${r.status}`);return await r.json() as McpDiagnostic;}
  async listSkills(scope?:SkillScope,status?:SkillStatus):Promise<{items:SkillSummary[];generation:number}>{const q=new URLSearchParams();if(scope)q.set('scope',scope);if(status)q.set('status',status);const r=await this.request(`/api/v1/skills${q.size?`?${q}`:''}`);if(!r.ok)throw new Error(`Skill list failed: ${r.status}`);return await r.json() as {items:SkillSummary[];generation:number};}
  async getSkill(id:string):Promise<SkillDetail>{const r=await this.request(`/api/v1/skills/${encodeURIComponent(id)}`);if(!r.ok)throw new Error(`Skill detail failed: ${r.status}`);return await r.json() as SkillDetail;}
  async listSkillResources(id:string):Promise<SkillResourceDescriptor[]>{const r=await this.request(`/api/v1/skills/${encodeURIComponent(id)}/resources`);if(!r.ok)throw new Error(`Skill resources failed: ${r.status}`);return await r.json() as SkillResourceDescriptor[];}
  async readSkillResource(skillId:string,resourceId:string):Promise<SkillResourceView>{const r=await this.request(`/api/v1/skills/${encodeURIComponent(skillId)}/resources/${encodeURIComponent(resourceId)}`);if(!r.ok)throw new Error(`Skill resource failed: ${r.status}`);return await r.json() as SkillResourceView;}
  async refreshSkills(scope?:SkillScope):Promise<SkillRefreshResult>{const r=await this.request('/api/v1/skills:refresh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({scope})});if(!r.ok)throw new Error(`Skill refresh failed: ${r.status}`);return await r.json() as SkillRefreshResult;}
  async setSkillEnabled(id:string,enabled:boolean):Promise<SkillSummary>{const r=await this.request(`/api/v1/skills/${encodeURIComponent(id)}`,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({enabled})});if(!r.ok)throw new Error(`Skill update failed: ${r.status}`);return await r.json() as SkillSummary;}
  async inspectLocalSkill(selectedPath:string):Promise<SkillImportManifest>{return await this.runSkillInspection('/api/v1/skill-imports:inspect-local',{selectedPath});}
  async inspectGitHubSkill(input:{repositoryUrl:string;ref?:string;subdirectory?:string}):Promise<SkillImportManifest>{return await this.runSkillInspection('/api/v1/skill-imports:inspect-github',input);}
  cancelActiveSkillInspection():void{this.#activeSkillInspection?.abort();}
  async commitSkillImport(input:{sessionId:string;action:'INSTALL'|'REPLACE'|'NO_OP';expectedExistingContentHash?:string}):Promise<SkillInstallResult>{const r=await this.request(`/api/v1/skill-imports/${encodeURIComponent(input.sessionId)}:commit`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:input.action,expectedExistingContentHash:input.expectedExistingContentHash})});if(!r.ok)throw await skillImportError(r);return await r.json() as SkillInstallResult;}
  async cancelSkillImport(sessionId:string):Promise<void>{const r=await this.request(`/api/v1/skill-imports/${encodeURIComponent(sessionId)}`,{method:'DELETE'});if(!r.ok)throw await skillImportError(r);}
  async removeInstalledSkill(skillId:string):Promise<SkillInstallResult>{const r=await this.request(`/api/v1/skills/${encodeURIComponent(skillId)}/installation`,{method:'DELETE'});if(!r.ok)throw await skillImportError(r);return await r.json() as SkillInstallResult;}
  async rollbackInstalledSkill(skillId:string):Promise<SkillInstallResult>{const r=await this.request(`/api/v1/skills/${encodeURIComponent(skillId)}/installation:rollback`,{method:'POST'});if(!r.ok)throw await skillImportError(r);return await r.json() as SkillInstallResult;}
  async getSkillInstallation(skillId:string):Promise<SkillInstallationInfo>{const r=await this.request(`/api/v1/skills/${encodeURIComponent(skillId)}/installation`);if(!r.ok)throw await skillImportError(r);return await r.json() as SkillInstallationInfo;}
  async resolveApproval(approvalId:string,decision:ApprovalDecision,expectedRequestFingerprint:string):Promise<ApprovalResolution>{const r=await this.request(`/api/v1/approvals/${encodeURIComponent(approvalId)}:resolve`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({decision,expectedRequestFingerprint})});if(!r.ok)throw await permissionError(r);return await r.json() as ApprovalResolution;}
  async listFileRoots(workspaceId:string):Promise<FileRootSummary[]>{const r=await this.request(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/file-roots`);if(!r.ok)throw await permissionError(r);return await r.json() as FileRootSummary[];}
  async addFileRoot(workspaceId:string,selectedPath:string,mode:FileRootMode):Promise<FileRootSummary>{const r=await this.request(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/file-roots`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({selectedPath,mode})});if(!r.ok)throw await permissionError(r);return await r.json() as FileRootSummary;}
  async updateFileRoot(input:{rootId:string;mode:FileRootMode;enabled:boolean;expectedVersion:number}):Promise<FileRootSummary>{const r=await this.request(`/api/v1/file-roots/${encodeURIComponent(input.rootId)}`,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({mode:input.mode,enabled:input.enabled,expectedVersion:input.expectedVersion})});if(!r.ok)throw await permissionError(r);return await r.json() as FileRootSummary;}
  async removeFileRoot(rootId:string,expectedVersion:number):Promise<void>{const r=await this.request(`/api/v1/file-roots/${encodeURIComponent(rootId)}?expectedVersion=${expectedVersion}`,{method:'DELETE'});if(!r.ok)throw await permissionError(r);}

  async stop(): Promise<void> {
    const child = this.#process;
    if (!child){this.updateState({status:'stopped'});return;}
    this.updateState({ status: 'stopping' });
    this.#intentionalExits.add(child);
    // The Host can close its HTTP server before the shutdown response completes.
    // Bound that request so process cleanup always proceeds promptly.
    try {
      await Promise.race([
        this.request('/internal/shutdown', { method: 'POST', signal: AbortSignal.timeout(2_000) }).then(() => undefined),
        new Promise<void>(resolve => setTimeout(resolve, 2_000))
      ]);
    } catch { /* stop below */ }
    if (child.exitCode === null) {
      await Promise.race([
        new Promise<void>(resolve => child.once('exit', () => resolve())),
        new Promise<void>(resolve => setTimeout(resolve, 5_000))
      ]);
    }
    if (this.#process === child) {
      if (child.exitCode === null) child.kill();
      this.#process = undefined;
      this.#baseUrl = undefined;
      this.#token = undefined;
    }
    this.updateState({status:'stopped'});
  }

  /** Test-only abnormal termination used to verify startup reconciliation. */
  async abortForSmoke(): Promise<void> {
    const child = this.#process;
    if (!child) return;
    this.#intentionalExits.add(child);
    child.kill();
    await Promise.race([
      new Promise<void>(resolve => child.once('exit', () => resolve())),
      new Promise<void>(resolve => setTimeout(resolve, 5_000))
    ]);
    if (this.#process === child) {
      this.#process = undefined;
      this.#baseUrl = undefined;
      this.#token = undefined;
      this.updateState({ status: 'failed', diagnosticCode: 'HOST_ABORTED_FOR_SMOKE',diagnosticId:diagnosticId() });
    }
  }

  /** Test-only unexpected runtime crash; unlike abortForSmoke this exercises automatic recovery. */
  async crashForWeek7Smoke():Promise<void>{
    if(process.env.AGENT_DESKTOP_W7_SMOKE!=='1')throw new Error('Week 7 crash injection is disabled');
    const child=this.#process;if(!child||this.#state.status!=='ready')throw new Error('Host is not ready for crash injection');
    child.kill();
    await Promise.race([new Promise<void>(resolve=>child.once('exit',()=>resolve())),new Promise<void>((_,reject)=>setTimeout(()=>reject(new Error('Host crash injection timed out')),5_000))]);
  }

  private request(path: string, init: RequestInit = {}): Promise<Response> {
    if (!this.#baseUrl || !this.#token) throw new Error('Host is not ready');
    const headers = new Headers(init.headers);
    headers.set('Authorization', `Bearer ${this.#token}`);
    headers.set('X-App-Protocol', '1');
    return fetch(this.#baseUrl + path, { ...init, headers });
  }

  private async runSkillInspection(path:string,body:unknown):Promise<SkillImportManifest>{
    const controller=new AbortController();this.#activeSkillInspection?.abort();this.#activeSkillInspection=controller;
    try{const response=await this.request(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),signal:controller.signal});if(!response.ok)throw await skillImportError(response);return await response.json() as SkillImportManifest;}
    finally{if(this.#activeSkillInspection===controller)this.#activeSkillInspection=undefined;}
  }

  private async terminateChild(child: ChildProcessWithoutNullStreams): Promise<void> {
    this.#intentionalExits.add(child);
    if (child.exitCode === null) {
      child.kill();
      await Promise.race([
        new Promise<void>(resolve => child.once('exit', () => resolve())),
        new Promise<void>(resolve => setTimeout(resolve, 2_000))
      ]);
    }
    if (this.#process === child) this.#process = undefined;
    this.#baseUrl = undefined;
    this.#token = undefined;
  }

  private emitLog(source:RuntimeLogSource,level:RuntimeLogLevel,message:string):void{
    process.stderr.write(`[${source}] ${message}\n`);
    this.runtimeLog?.(source,level,message);
  }
}

function diagnosticId():string{return `diag_${randomBytes(9).toString('base64url')}`;}

export function probeNodeModule(nodeModules:string,moduleName:string):boolean{
  try{
    if(!moduleName.match(/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/))return false;
    const root=path.resolve(nodeModules);
    const candidate=path.resolve(root,moduleName);
    const candidateRelative=path.relative(root,candidate);
    if(candidateRelative.startsWith('..')||path.isAbsolute(candidateRelative))return false;
    const require=createRequire(path.join(root,'agent-desktop-runtime-probe.cjs'));
    // The managed runtime is a NODE_PATH root, not a directory named node_modules.
    // Resolve the exact package directory so the probe cannot walk upward into a
    // developer or user installation and then report a false result.
    const resolved=require.resolve(candidate);
    const relative=path.relative(root,path.resolve(resolved));
    if(relative.startsWith('..')||path.isAbsolute(relative))return false;
    const loaded=require(candidate) as unknown;
    return typeof loaded==='function'||(typeof loaded==='object'&&loaded!==null);
  }catch{return false;}
}

function readReady(child: ChildProcessWithoutNullStreams, timeoutMs: number): Promise<ReadyMessage> {
  return new Promise((resolve, reject) => {
    const lines = createInterface({ input: child.stdout });
    const timeout = setTimeout(() => { lines.close(); reject(new Error('Host ready timeout')); }, timeoutMs);
    let ignored=0;
    lines.on('line', line => {
      try {
        const message = JSON.parse(line) as ReadyMessage;
        if (message.type !== 'ready') throw new Error('Invalid ready message');
        clearTimeout(timeout);
        lines.close();
        resolve(message);
      } catch {
        ignored++;
        if(ignored>20){clearTimeout(timeout);lines.close();reject(new Error('Host emitted too many invalid ready lines'));}
      }
    });
    child.once('exit', code => { clearTimeout(timeout); reject(new Error(`Host exited before ready: ${code}`)); });
  });
}

function redact(value: string): string {
  return value.replace(/(authorization|api[-_ ]?key|token)["'\s:=]+[^\s,"']+/gi, '$1=[REDACTED]')
    .replace(/[A-Za-z]:\\[^\r\n"']+/g,'[PRIVATE_PATH]')
    .replace(/\/(?:Users|home)\/[^\r\n"']+/g,'[PRIVATE_PATH]');
}

function hostLogLevel(value:string):RuntimeLogLevel{
  if(/\bERROR\b/i.test(value))return 'ERROR';
  if(/\bWARN(?:ING)?\b/i.test(value))return 'WARN';
  if(/\bDEBUG\b/i.test(value))return 'DEBUG';
  return 'INFO';
}

async function skillImportError(response:Response):Promise<Error>{let code='SKILL_IMPORT_FAILED';try{const body=await response.json() as {code?:string;detailCode?:string};code=body.detailCode?`${body.code??code}:${body.detailCode}`:body.code??code;}catch{}return new Error(code);}
async function permissionError(response:Response):Promise<Error>{let code='PERMISSION_REQUEST_FAILED';try{const body=await response.json() as {code?:string};code=body.code??code;}catch{}return new Error(code);}
async function providerError(response:Response):Promise<Error>{let code='PROVIDER_REQUEST_FAILED';try{const body=await response.json() as {code?:string};code=body.code??code;}catch{}return new Error(code);}

function hostReadyTimeoutMs(): number {
  const configured = Number(process.env.AGENT_DESKTOP_HOST_READY_TIMEOUT_MS ?? '150000');
  if (!Number.isFinite(configured)) return 150_000;
  return Math.max(10_000, Math.min(Math.trunc(configured), 300_000));
}

export function hostRecoveryTimeoutMs(): number {
  const configured = Number(process.env.AGENT_DESKTOP_HOST_READY_TIMEOUT_MS ?? '30000');
  if (!Number.isFinite(configured)) return 30_000;
  return Math.max(10_000, Math.min(Math.trunc(configured), 180_000));
}
