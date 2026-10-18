import { app, BrowserWindow, dialog, ipcMain, protocol, shell } from 'electron';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, realpathSync, statSync } from 'node:fs';
import { appendFileSync, readFileSync } from 'node:fs';
import { copyFile, readFile, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { format } from 'node:util';
import { CredentialStore } from './credential-store.js';
import { HostSupervisor, hostRecoveryTimeoutMs } from './host-supervisor.js';
import { resolveAppPaths, type AppPaths } from './app-paths.js';
import { developmentReleaseManifest, releaseInfo, validateReleaseManifest, type ReleaseManifest } from './release-manifest.js';
import { SessionStateStore } from './session-state.js';
import { SettingsStore, maskedWorkspace } from './settings-store.js';
import { previewArtifact } from './artifact-preview.js';
import { ArtifactLayoutConverter } from './artifact-layout-preview.js';
import { ARTIFACT_RESOURCE_SCHEME, ArtifactResourceProtocol } from './artifact-resource-protocol.js';
import type { AgUiEvent, RecoveryState, RunAgentInput } from './protocol.js';
import { RuntimeLogService, type RuntimeLogLevel } from './runtime-log.js';

let runtimeLogs:RuntimeLogService|undefined;
const supervisor = new HostSupervisor((source,level,message)=>runtimeLogs?.write(source,level,message));
const artifactResources = new ArtifactResourceProtocol(
  path.join(import.meta.dirname, 'runtime', 'echarts', 'echarts.min.js'),
  path.join(import.meta.dirname, 'runtime', 'tailwind', 'index.cjs'),
  path.join(import.meta.dirname, 'runtime', 'lucide', 'lucide.min.js')
);
const artifactLayoutConverter = new ArtifactLayoutConverter();
protocol.registerSchemesAsPrivileged([{ scheme: ARTIFACT_RESOURCE_SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true } }]);
const smokeMode = process.env.AGENT_DESKTOP_SMOKE === '1';
const rendererSmokeMode = process.env.AGENT_DESKTOP_W4_RENDERER_SMOKE === '1'||process.env.AGENT_DESKTOP_W7_RENDERER_SMOKE==='1';
const configuredAppHome = (!app.isPackaged || smokeMode) && process.env.AGENT_DESKTOP_APP_HOME
  ? process.env.AGENT_DESKTOP_APP_HOME : path.join(os.homedir(), '.agent-desktop');
// Keep Chromium's cache and encrypted-store probes inside the same local app home.
// This also makes isolated restart verification independent from the user's profile.
app.setPath('userData', path.join(configuredAppHome, 'electron'));
if (smokeMode) {
  app.disableHardwareAcceleration();
  app.commandLine.appendSwitch('disable-gpu');
  app.commandLine.appendSwitch('disable-gpu-compositing');
  app.commandLine.appendSwitch('in-process-gpu');
  app.commandLine.appendSwitch('use-angle', 'swiftshader');
  app.commandLine.appendSwitch('disable-features', 'Vulkan');
}
let mainWindow: BrowserWindow | undefined;
let credentialStore: CredentialStore;
let appPaths:AppPaths;
let releaseManifest:ReleaseManifest;
let sessionState:SessionStateStore;
let settingsStore:SettingsStore;
let activeWorkspacePath='';
let rendererCrashCount=0;
let recreatingRenderer=false;
let quitting=false;
let recoveredFromUncleanExit=false;
let previousHostStatus='stopped';

const singleInstance=app.requestSingleInstanceLock();
if(!singleInstance)app.quit();
else app.on('second-instance',()=>{const window=liveMainWindow();if(!window)return;if(window.isMinimized())window.restore();window.show();window.focus();});

supervisor.onState(state=>{
  const recovered=previousHostStatus==='restarting'&&state.status==='ready';previousHostStatus=state.status;
  if(state.status==='restarting')sessionState?.write('RESTARTING');
  if(state.status==='ready')sessionState?.write('READY');
  liveMainWindow()?.webContents.send('host:state',state);
  if(recovered&&credentialStore)void restoreMcpConnections().catch(()=>undefined);
});

if(singleInstance)app.whenReady().then(async () => {
  smokeLog('app-ready');
  try {
    appPaths=resolveAppPaths({packaged:app.isPackaged,appPath:app.getAppPath(),resourcesPath:process.resourcesPath,
      moduleDirectory:import.meta.dirname,appHome:configuredAppHome,smokeMode,environment:process.env});
    releaseManifest=appPaths.packaged?validateReleaseManifest(appPaths,app.getVersion()):developmentReleaseManifest(app.getVersion());
    sessionState=new SessionStateStore(appPaths.appHome,releaseManifest.appVersion);
    recoveredFromUncleanExit=sessionState.recoveredFromUncleanExit;sessionState.write('STARTING');
    settingsStore=new SettingsStore(appPaths.appHome);const settings=settingsStore.load();
    runtimeLogs=new RuntimeLogService(settings.runtimeLogs);installMainLogCapture();
    activeWorkspacePath=path.resolve(settings.defaultWorkspacePath);mkdirSync(activeWorkspacePath,{recursive:true});activeWorkspacePath=realpathSync.native(activeWorkspacePath);
    credentialStore = new CredentialStore(appPaths.appHome);
    artifactResources.register();
    registerIpc();
    if (!smokeMode || rendererSmokeMode) mainWindow = createWindow();
    smokeLog('host-starting');
    await supervisor.start(appPaths.javaExecutable,appPaths.hostJar,appPaths.appHome,appPaths.builtinSkills,
      appPaths.installRoot,appPaths.nodeModules,releaseManifest.appVersion,releaseManifest.dbSchemaMax,activeWorkspacePath);
    smokeLog('host-ready');
    liveMainWindow()?.webContents.send('host:state', supervisor.state);
    const activeMcpCredentialRefs = (await supervisor.listMcpServers()).items
      .map(server => server.credentialRef).filter((value): value is string => Boolean(value));
    await credentialStore.pruneMcp(activeMcpCredentialRefs);
    await restoreMcpConnections();
    if (smokeMode) await runDesktopSmoke();
  } catch (error) {
    smokeLog(`failed: ${error instanceof Error ? error.stack ?? error.message : String(error)}`);
    liveMainWindow()?.webContents.send('host:state', supervisor.state);
    if (smokeMode) {
      console.error('Desktop smoke verification failed', error);
      await supervisor.stop();
      exitSmoke(1);
    }else if(!liveMainWindow()){mainWindow=createFailureWindow(stableErrorCode(error),diagnosticId());}
    }
});

app.on('window-all-closed', () => {if(!recreatingRenderer)app.quit();});
app.on('before-quit', event => {
  if (!quitting&&['ready','restarting','starting','crash-loop','failed'].includes(supervisor.state.status)) {
    event.preventDefault();
    quitting=true;
    artifactLayoutConverter.dispose();
    void Promise.all([supervisor.stop(),artifactResources.dispose()]).finally(() => {try{sessionState?.write('CLEAN_EXIT');}finally{app.exit(0);}});
  }
});

function createWindow(): BrowserWindow {
  const devUrl = (!app.isPackaged||smokeMode)?process.env.AGENT_DESKTOP_DEV_URL:undefined;
  const rendererFile = appPaths.rendererIndex;
  const window = new BrowserWindow({
    show: !smokeMode,
    width: 1180, height: 780, minWidth: 760, minHeight: 560,
    webPreferences: {
      preload: path.join(import.meta.dirname, 'preload.cjs'),
      contextIsolation: true, sandbox: true, nodeIntegration: false,
      webSecurity: true, allowRunningInsecureContent: false
    }
  });
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('console-message',details=>{
    const levels:Record<string,RuntimeLogLevel>={debug:'DEBUG',info:'INFO',warning:'WARN',error:'ERROR'};
    runtimeLogs?.write('renderer',levels[details.level]??'INFO',`${details.sourceId}:${details.lineNumber} ${details.message}`);
  });
  if (smokeMode) {
    window.webContents.on('did-fail-load', (_event, code, description, url) =>
      console.error('Renderer load failed', { code, description, url }));
    window.webContents.on('render-process-gone', (_event, details) =>
      console.error('Renderer process exited', details));
  }
  window.webContents.on('render-process-gone',()=>handleRendererGone(window));
  window.webContents.on('will-navigate', (event, url) => {
    const allowed = devUrl ? url.startsWith(devUrl) : url.startsWith('file:');
    if (!allowed) event.preventDefault();
  });
  if (devUrl && /^http:\/\/127\.0\.0\.1:\d+$/.test(devUrl)) {
    void window.loadURL(devUrl).then(() => smokeLog('renderer-load-resolved'))
      .catch(error => smokeLog(`renderer-load-rejected: ${String(error)}`));
  } else {
    void window.loadFile(rendererFile).then(() => smokeLog('renderer-load-resolved'))
      .catch(error => smokeLog(`renderer-load-rejected: ${String(error)}`));
  }
  return window;
}

function handleRendererGone(window:BrowserWindow):void{
  if(quitting||window!==mainWindow)return;
  rendererCrashCount++;
  recreatingRenderer=true;
  const id=diagnosticId();
  writeProcessLog('renderer','ERROR',`RENDERER_GONE ${id}`);
  mainWindow=undefined;
  if(!window.isDestroyed())window.destroy();
  setTimeout(()=>{
    mainWindow=rendererCrashCount<=1?createWindow():createFailureWindow('RENDERER_CRASH_LOOP',id);
    recreatingRenderer=false;
  },250);
}

function createFailureWindow(code:string,id:string):BrowserWindow{
  const window=new BrowserWindow({width:760,height:480,webPreferences:{contextIsolation:true,sandbox:true,nodeIntegration:false}});
  const html=`<!doctype html><meta charset="utf-8"><title>iWork</title><style>body{font:16px Segoe UI,sans-serif;padding:48px;color:#243238}code{display:block;margin:18px 0;padding:12px;background:#eef2f3}</style><h1>应用无法安全启动</h1><p>请重启应用；如果问题持续，请复制以下诊断信息。</p><code>${code} · ${id}</code>`;
  void window.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`);return window;
}

function liveMainWindow():BrowserWindow|undefined{
  const window=mainWindow;
  return window&&!window.isDestroyed()&&!window.webContents.isDestroyed()?window:undefined;
}

function registerIpc(): void {
  ipcMain.handle('host:get-state', event => { assertSender(event.sender); return supervisor.state; });
  ipcMain.handle('release:get-info',event=>{assertSender(event.sender);return releaseInfo(releaseManifest);});
  ipcMain.handle('recovery:get-state',event=>{assertSender(event.sender);return recoveryState();});
  ipcMain.handle('settings:get',event=>{assertSender(event.sender);return publicSettings();});
  ipcMain.handle('settings:set-max-iterations',(event,value:unknown)=>{assertSender(event.sender);if(!Number.isSafeInteger(value)||Number(value)<1||Number(value)>50)throw new Error('SETTINGS_INVALID');settingsStore.update({maxIterations:Number(value)});return publicSettings();});
  ipcMain.handle('settings:select-workspace',async event=>{assertSender(event.sender);const window=liveMainWindow();if(!window)throw new Error('Window unavailable');const selected=await dialog.showOpenDialog(window,{title:'选择默认工作区',defaultPath:activeWorkspacePath,properties:['openDirectory','createDirectory','dontAddToRecent']});if(selected.canceled||selected.filePaths.length===0)return {cancelled:true as const};settingsStore.selectWorkspace(selected.filePaths[0]);return publicSettings();});
  ipcMain.handle('settings:set-runtime-logs',(event,raw:unknown)=>{assertSender(event.sender);if(!isRecord(raw)||typeof raw.enabled!=='boolean'||typeof raw.main!=='boolean'||typeof raw.host!=='boolean'||typeof raw.renderer!=='boolean')throw new Error('SETTINGS_INVALID');const current=settingsStore.load();const updated=settingsStore.update({runtimeLogs:{...current.runtimeLogs,enabled:raw.enabled,main:raw.main,host:raw.host,renderer:raw.renderer}});runtimeLogs?.configure(updated.runtimeLogs);return publicSettings();});
  ipcMain.handle('settings:select-log-directory',async event=>{assertSender(event.sender);const window=liveMainWindow();if(!window)throw new Error('Window unavailable');const current=settingsStore.load();const selected=await dialog.showOpenDialog(window,{title:'选择运行日志目录',defaultPath:current.runtimeLogs.directory,properties:['openDirectory','createDirectory','dontAddToRecent']});if(selected.canceled||selected.filePaths.length===0)return {cancelled:true as const};const updated=settingsStore.selectLogDirectory(selected.filePaths[0]);runtimeLogs?.configure(updated.runtimeLogs);return publicSettings();});
  ipcMain.handle('settings:open-log-directory',async event=>{assertSender(event.sender);const directory=settingsStore.load().runtimeLogs.directory;mkdirSync(directory,{recursive:true});const error=await shell.openPath(directory);if(error)throw new Error('LOG_DIRECTORY_OPEN_FAILED');});
  ipcMain.handle('artifact:preview',async(event,raw:unknown)=>{assertSender(event.sender);const file=resolveArtifact(raw);if(file.byteSize>25*1024*1024)throw new Error('ARTIFACT_PREVIEW_TOO_LARGE');const bytes=await readFile(file.path);if(bytes.length!==file.byteSize)throw new Error('ARTIFACT_CHANGED');const preview=previewArtifact(file.fileName,bytes);const resourceGrant=preview.kind==='html'?await artifactResources.grantHtml(path.dirname(file.path),preview.content):undefined;return {...preview,...resourceGrant,fileName:file.fileName,byteSize:bytes.length};});
  ipcMain.handle('artifact:layout-preview',async(event,raw:unknown)=>{assertSender(event.sender);const file=resolveArtifact(raw);if(file.byteSize>25*1024*1024)throw new Error('ARTIFACT_PREVIEW_TOO_LARGE');const result=await artifactLayoutConverter.generate(file.path);return artifactResources.grantFile(result.pdfPath,result.tempRoot);});
  ipcMain.handle('artifact:release-preview',async(event,previewId:unknown)=>{assertSender(event.sender);if(typeof previewId!=='string'||!/^[0-9a-f]{32}$/.test(previewId))throw new Error('ARTIFACT_PREVIEW_INVALID');await artifactResources.release(previewId);});
  ipcMain.handle('artifact:open-default',async(event,raw:unknown)=>{assertSender(event.sender);const file=resolveArtifact(raw);const error=await shell.openPath(file.path);if(error)throw new Error('ARTIFACT_OPEN_FAILED');});
  ipcMain.handle('artifact:show-in-folder',(event,raw:unknown)=>{assertSender(event.sender);shell.showItemInFolder(resolveArtifact(raw).path);});
  ipcMain.handle('artifact:save-as',async(event,raw:unknown)=>{assertSender(event.sender);const file=resolveArtifact(raw);const choice=await dialog.showSaveDialog({title:'另存为',defaultPath:path.join(app.getPath('documents'),file.fileName)});if(choice.canceled||!choice.filePath)return {saved:false};await copyFile(file.path,choice.filePath);return {saved:true};});
  ipcMain.handle('diagnostic:preview',async event=>{assertSender(event.sender);return await supervisor.previewDiagnostics();});
  ipcMain.handle('diagnostic:export',async event=>{
    assertSender(event.sender);
    const preview=await supervisor.previewDiagnostics();
    const choice=await dialog.showSaveDialog({title:'导出脱敏诊断包',defaultPath:path.join(app.getPath('documents'),`agent-desktop-diagnostic-${new Date().toISOString().slice(0,10)}.json`),filters:[{name:'JSON',extensions:['json']}]});
    if(choice.canceled||!choice.filePath)return {exported:false};
    const bytes=Buffer.from(JSON.stringify(preview,null,2),'utf8');
    await writeFile(choice.filePath,bytes,{flag:'wx'}).catch(async error=>{if((error as NodeJS.ErrnoException).code==='EEXIST')await writeFile(choice.filePath,bytes);else throw error;});
    return {exported:true,sha256:createHash('sha256').update(bytes).digest('hex')};
  });
  ipcMain.handle('agent:start-turn', async (event, raw: unknown) => {
    assertSender(event.sender);
    const { threadId, message, profileId,explicitSkillIds } = parseStartInput(raw);
    if (supervisor.state.status !== 'ready') throw new Error('Host is not ready');
    const resolvedThreadId = threadId ?? newId('thr');
    const turnId = newId('trn');
    let modelProfileId=profileId??'deepseek-v4-pro';
    if(threadId)modelProfileId=(await supervisor.snapshot(threadId)).thread.modelProfileId;
    let secretSessionId:string|undefined;
    if(process.env.AGENT_DESKTOP_TEST_FIXTURES!=='1'){
      const profiles=await supervisor.listProviderProfiles();const profile=profiles.find(item=>item.id===modelProfileId);
      if(!profile)throw new Error('PROFILE_NOT_AVAILABLE');
      const key=await credentialStore.load(profile.providerId);if(!key)throw new Error('PROVIDER_KEY_MISSING');
      secretSessionId=(await supervisor.createProviderSecret(profile.providerId,key)).secretSessionId;
    }
    const input: RunAgentInput = {
      threadId: resolvedThreadId, runId: newId('run'),
      messages: [{ id: newId('msg'), role: 'user', content: message }],
      context: { turnId, workspaceId: 'ws_default', modelProfileId:process.env.AGENT_DESKTOP_TEST_FIXTURES==='1'?'mock':modelProfileId,maxIterations:settingsStore.load().maxIterations }, explicitSkillIds,secretSessionId
    };
    void supervisor.run(input, value => {if(!event.sender.isDestroyed())event.sender.send('agent:event', value);}).catch(() => {
      const error: AgUiEvent = {
        type: 'RUN_ERROR', runId: input.runId, threadId: resolvedThreadId, turnId,
        sequence: Number.MAX_SAFE_INTEGER, timestamp: new Date().toISOString(),
        value: { code: 'HOST_REQUEST_FAILED', message: 'The local agent request failed' }
      };
      if (!event.sender.isDestroyed()) event.sender.send('agent:event', error);
    });
    return { threadId: resolvedThreadId, turnId };
  });
  ipcMain.handle('agent:cancel-turn', async (event, turnId: unknown) => {
    assertSender(event.sender); assertId(turnId, 'trn'); return await supervisor.cancel(turnId);
  });
  ipcMain.handle('agent:steer-turn',async(event,raw:unknown)=>{
    assertSender(event.sender);
    if(!isRecord(raw)||typeof raw.message!=='string'||raw.message.trim().length<1||Buffer.byteLength(raw.message.trim(),'utf8')>4096)throw new Error('Invalid steering input');
    assertId(raw.turnId,'trn');
    await supervisor.steer(raw.turnId,raw.message.trim());
  });
  ipcMain.handle('agent:retry-turn',async(event,rawTurnId:unknown)=>{
    assertSender(event.sender);assertId(rawTurnId,'trn');
    if(supervisor.state.status!=='ready')throw new Error('Host is not ready');
    const retry=await supervisor.prepareRetry(rawTurnId);
    const turnId=newId('trn');
    let modelProfileId=retry.modelProfileId;
    let secretSessionId:string|undefined;
    if(process.env.AGENT_DESKTOP_TEST_FIXTURES==='1')modelProfileId='mock';
    else{
      const profile=(await supervisor.listProviderProfiles()).find(item=>item.id===modelProfileId);
      if(!profile)throw new Error('PROFILE_NOT_AVAILABLE');
      const key=await credentialStore.load(profile.providerId);if(!key)throw new Error('PROVIDER_KEY_MISSING');
      secretSessionId=(await supervisor.createProviderSecret(profile.providerId,key)).secretSessionId;
    }
    const input:RunAgentInput={threadId:retry.threadId,runId:newId('run'),messages:[{id:newId('msg'),role:'user',content:retry.message}],context:{turnId,workspaceId:'ws_default',modelProfileId,maxIterations:settingsStore.load().maxIterations},explicitSkillIds:[],secretSessionId,retryOfTurnId:rawTurnId};
    void supervisor.run(input,value=>{if(!event.sender.isDestroyed())event.sender.send('agent:event',value);}).catch(()=>{
      const error:AgUiEvent={type:'RUN_ERROR',runId:input.runId,threadId:retry.threadId,turnId,sequence:Number.MAX_SAFE_INTEGER,timestamp:new Date().toISOString(),value:{code:'HOST_REQUEST_FAILED',message:'The local agent retry failed'}};
      if(!event.sender.isDestroyed())event.sender.send('agent:event',error);
    });
    return {threadId:retry.threadId,turnId,message:retry.message};
  });
  ipcMain.handle('agent:list-threads', async (event, raw: unknown) => {
    assertSender(event.sender);
    const input = raw === undefined ? {} : raw;
    if (!isRecord(input) || (input.cursor !== undefined && typeof input.cursor !== 'string') ||
      (input.limit !== undefined && (!Number.isInteger(input.limit) || Number(input.limit) < 1 || Number(input.limit) > 100)) ||
      (input.status !== undefined && input.status !== 'ACTIVE' && input.status !== 'ARCHIVED')) throw new Error('Invalid thread query');
    return await supervisor.listThreads(input.cursor as string | undefined, input.limit as number | undefined, input.status as 'ACTIVE' | 'ARCHIVED' | undefined);
  });
  ipcMain.handle('agent:get-snapshot', async (event, threadId: unknown) => {
    assertSender(event.sender); assertId(threadId, 'thr'); return await supervisor.snapshot(threadId);
  });
  ipcMain.handle('agent:rename-thread', async (event, raw: unknown) => {
    assertSender(event.sender);
    if (!isRecord(raw) || typeof raw.threadId !== 'string' || typeof raw.title !== 'string') throw new Error('Invalid rename request');
    assertId(raw.threadId, 'thr'); if (raw.title.trim().length < 1 || Array.from(raw.title).length > 120) throw new Error('Invalid thread title');
    return await supervisor.renameThread(raw.threadId, raw.title.trim());
  });
  ipcMain.handle('agent:archive-thread', async (event, threadId: unknown) => {
    assertSender(event.sender); assertId(threadId, 'thr'); await supervisor.archiveThread(threadId);
  });
  ipcMain.handle('agent:restore-thread', async (event, threadId: unknown) => {
    assertSender(event.sender); assertId(threadId, 'thr'); return await supervisor.restoreThread(threadId);
  });
  ipcMain.handle('agent:get-items', async (event, threadId: unknown) => {
    assertSender(event.sender); assertId(threadId, 'thr'); return await supervisor.items(threadId);
  });
  ipcMain.handle('provider:save-key', async (event, raw: unknown) => {
    assertSender(event.sender);
    if (!isRecord(raw) || (raw.providerId !== 'deepseek' && raw.providerId !== 'qwen') || typeof raw.apiKey !== 'string')
      throw new Error('Invalid provider credential request');
    await credentialStore.save(raw.providerId, raw.apiKey);
  });
  ipcMain.handle('provider:delete-key',async(event,providerId:unknown)=>{assertSender(event.sender);if(providerId!=='deepseek'&&providerId!=='qwen')throw new Error('Invalid provider');await credentialStore.delete(providerId);});
  ipcMain.handle('provider:list-profiles',async event=>{assertSender(event.sender);return await supervisor.listProviderProfiles();});
  ipcMain.handle('provider:check',async(event,profileId:unknown)=>{assertSender(event.sender);if(typeof profileId!=='string')throw new Error('Invalid profile');const profile=(await supervisor.listProviderProfiles()).find(item=>item.id===profileId);if(!profile)throw new Error('PROFILE_NOT_AVAILABLE');const key=await credentialStore.load(profile.providerId);if(!key)throw new Error('PROVIDER_KEY_MISSING');const session=await supervisor.createProviderSecret(profile.providerId,key);return await supervisor.checkProvider(profile.id,session.secretSessionId);});
  ipcMain.handle('provider:select-thread-profile',async(event,raw:unknown)=>{assertSender(event.sender);if(!isRecord(raw)||typeof raw.profileId!=='string')throw new Error('Invalid profile selection');assertId(raw.threadId,'thr');return await supervisor.selectThreadProfile(raw.threadId,raw.profileId);});
  ipcMain.handle('mcp:list-servers',async event=>{assertSender(event.sender);return await supervisor.listMcpServers();});
  ipcMain.handle('mcp:save-server',async(event,raw:unknown)=>{assertSender(event.sender);return await supervisor.saveMcpServer(parseMcpDraft(raw));});
  ipcMain.handle('mcp:connect-server',async(event,raw:unknown)=>{assertSender(event.sender);if(!isRecord(raw))throw new Error('Invalid MCP connection');assertId(raw.serverId,'mcp');if(raw.bearerToken!==undefined&&typeof raw.bearerToken!=='string')throw new Error('Invalid MCP credential');return await connectMcpWithStoredCredential(raw.serverId,raw.bearerToken as string|undefined);});
  ipcMain.handle('mcp:disconnect-server',async(event,id:unknown)=>{assertSender(event.sender);assertId(id,'mcp');await supervisor.disconnectMcpServer(id);});
  ipcMain.handle('mcp:delete-server',async(event,id:unknown)=>{assertSender(event.sender);assertId(id,'mcp');await deleteMcpServer(id);});
  ipcMain.handle('mcp:list-tools',async(event,id:unknown)=>{assertSender(event.sender);assertId(id,'mcp');return await supervisor.listMcpTools(id);});
  ipcMain.handle('mcp:get-diagnostic',async(event,id:unknown)=>{assertSender(event.sender);assertId(id,'mcp');return await supervisor.mcpDiagnostic(id);});
  ipcMain.handle('skill:list',async(event,raw:unknown)=>{assertSender(event.sender);const value=raw===undefined?{}:raw;if(!isRecord(value)||(value.scope!==undefined&&!['BUILTIN','USER','WORKSPACE'].includes(String(value.scope)))||(value.status!==undefined&&!['VALID','INVALID','SHADOWED','MISSING'].includes(String(value.status))))throw new Error('Invalid Skill query');return await supervisor.listSkills(value.scope as import('./protocol.js').SkillScope|undefined,value.status as import('./protocol.js').SkillStatus|undefined);});
  ipcMain.handle('skill:get',async(event,id:unknown)=>{assertSender(event.sender);assertId(id,'skl');return await supervisor.getSkill(id);});
  ipcMain.handle('skill:list-resources',async(event,id:unknown)=>{assertSender(event.sender);assertId(id,'skl');return await supervisor.listSkillResources(id);});
  ipcMain.handle('skill:read-resource',async(event,raw:unknown)=>{assertSender(event.sender);if(!isRecord(raw))throw new Error('Invalid Skill resource');assertId(raw.skillId,'skl');assertId(raw.resourceId,'res');return await supervisor.readSkillResource(raw.skillId,raw.resourceId);});
  ipcMain.handle('skill:refresh',async(event,scope:unknown)=>{assertSender(event.sender);if(scope!==undefined&&!['BUILTIN','USER','WORKSPACE'].includes(String(scope)))throw new Error('Invalid Skill scope');return await supervisor.refreshSkills(scope as import('./protocol.js').SkillScope|undefined);});
  ipcMain.handle('skill:set-enabled',async(event,raw:unknown)=>{assertSender(event.sender);if(!isRecord(raw)||typeof raw.enabled!=='boolean')throw new Error('Invalid Skill update');assertId(raw.skillId,'skl');return await supervisor.setSkillEnabled(raw.skillId,raw.enabled);});
  ipcMain.handle('skill-import:select-local',async event=>{assertSender(event.sender);const window=liveMainWindow();if(!window)throw new Error('Window unavailable');const selected=await dialog.showOpenDialog(window,{title:'选择 Skill 根目录',properties:['openDirectory','dontAddToRecent']});if(selected.canceled||selected.filePaths.length===0)return {cancelled:true as const};return await supervisor.inspectLocalSkill(selected.filePaths[0]);});
  ipcMain.handle('skill-import:inspect-github',async(event,raw:unknown)=>{assertSender(event.sender);if(!isRecord(raw)||typeof raw.repositoryUrl!=='string'||raw.repositoryUrl.length>512||(raw.ref!==undefined&&(typeof raw.ref!=='string'||raw.ref.length>256))||(raw.subdirectory!==undefined&&(typeof raw.subdirectory!=='string'||raw.subdirectory.length>512)))throw new Error('Invalid GitHub Skill source');return await supervisor.inspectGitHubSkill({repositoryUrl:raw.repositoryUrl,ref:raw.ref as string|undefined,subdirectory:raw.subdirectory as string|undefined});});
  ipcMain.handle('skill-import:commit',async(event,raw:unknown)=>{assertSender(event.sender);if(!isRecord(raw)||!['INSTALL','REPLACE','NO_OP'].includes(String(raw.action))||(raw.expectedExistingContentHash!==undefined&&(typeof raw.expectedExistingContentHash!=='string'||!/^[0-9a-f]{64}$/.test(raw.expectedExistingContentHash))))throw new Error('Invalid Skill import commit');assertId(raw.sessionId,'imp');return await supervisor.commitSkillImport({sessionId:raw.sessionId,action:raw.action as 'INSTALL'|'REPLACE'|'NO_OP',expectedExistingContentHash:raw.expectedExistingContentHash as string|undefined});});
  ipcMain.handle('skill-import:cancel',async(event,id:unknown)=>{assertSender(event.sender);assertId(id,'imp');await supervisor.cancelSkillImport(id);});
  ipcMain.handle('skill-import:remove',async(event,id:unknown)=>{assertSender(event.sender);assertId(id,'skl');return await supervisor.removeInstalledSkill(id);});
  ipcMain.handle('skill-import:rollback',async(event,id:unknown)=>{assertSender(event.sender);assertId(id,'skl');return await supervisor.rollbackInstalledSkill(id);});
  ipcMain.handle('skill-import:get-installation',async(event,id:unknown)=>{assertSender(event.sender);assertId(id,'skl');return await supervisor.getSkillInstallation(id);});
  ipcMain.handle('skill-import:cancel-active',event=>{assertSender(event.sender);supervisor.cancelActiveSkillInspection();});
  ipcMain.handle('approval:resolve',async(event,raw:unknown)=>{assertSender(event.sender);if(!isRecord(raw)||!['ALLOW_ONCE','ALLOW_THREAD','DENY'].includes(String(raw.decision))||typeof raw.expectedRequestFingerprint!=='string'||!/^sha256:[0-9a-f]{64}$/.test(raw.expectedRequestFingerprint))throw new Error('Invalid approval resolution');assertId(raw.approvalId,'apr');return await supervisor.resolveApproval(raw.approvalId,raw.decision as import('./protocol.js').ApprovalDecision,raw.expectedRequestFingerprint);});
  ipcMain.handle('file-root:list',async(event,workspaceId:unknown)=>{assertSender(event.sender);if(workspaceId!=='ws_default')throw new Error('Invalid workspace');return await supervisor.listFileRoots(workspaceId);});
  ipcMain.handle('file-root:select-add',async(event,raw:unknown)=>{assertSender(event.sender);if(!isRecord(raw)||raw.workspaceId!=='ws_default'||(raw.mode!=='READ_ONLY'&&raw.mode!=='READ_WRITE'))throw new Error('Invalid file root request');const window=liveMainWindow();if(!window)throw new Error('Window unavailable');const selected=await dialog.showOpenDialog(window,{title:'选择文件访问根目录',properties:['openDirectory','dontAddToRecent']});if(selected.canceled||selected.filePaths.length===0)return {cancelled:true as const};return await supervisor.addFileRoot('ws_default',selected.filePaths[0],raw.mode);});
  ipcMain.handle('file-root:update',async(event,raw:unknown)=>{assertSender(event.sender);if(!isRecord(raw)||(raw.mode!=='READ_ONLY'&&raw.mode!=='READ_WRITE')||typeof raw.enabled!=='boolean'||!Number.isSafeInteger(raw.expectedVersion)||Number(raw.expectedVersion)<1)throw new Error('Invalid file root update');assertId(raw.rootId,'root');return await supervisor.updateFileRoot(raw as {rootId:string;mode:import('./protocol.js').FileRootMode;enabled:boolean;expectedVersion:number});});
  ipcMain.handle('file-root:remove',async(event,raw:unknown)=>{assertSender(event.sender);if(!isRecord(raw)||!Number.isSafeInteger(raw.expectedVersion)||Number(raw.expectedVersion)<1)throw new Error('Invalid file root removal');assertId(raw.rootId,'root');await supervisor.removeFileRoot(raw.rootId,Number(raw.expectedVersion));});
}

function recoveryState():RecoveryState{
  const mapping:Record<string,RecoveryState['hostState']>={starting:'STARTING',ready:'READY',restarting:'RESTARTING','crash-loop':'CRASH_LOOP',stopping:'FAILED',stopped:'FAILED',failed:'FAILED'};
  return {hostState:mapping[supervisor.state.status],recoveredFromUncleanExit,diagnosticId:supervisor.state.diagnosticId};
}

function publicSettings(){const value=settingsStore.load();return {defaultWorkspaceLabel:path.basename(value.defaultWorkspacePath)||'工作区',defaultWorkspaceMaskedPath:maskedWorkspace(value.defaultWorkspacePath),maxIterations:value.maxIterations,workspaceRestartRequired:path.resolve(value.defaultWorkspacePath)!==path.resolve(activeWorkspacePath),runtimeLogs:{enabled:value.runtimeLogs.enabled,main:value.runtimeLogs.main,host:value.runtimeLogs.host,renderer:value.runtimeLogs.renderer,directoryLabel:path.basename(value.runtimeLogs.directory)||'日志',directoryMaskedPath:maskedWorkspace(value.runtimeLogs.directory),maxFileSizeMb:10,maxFiles:5}};}

function installMainLogCapture():void{
  const levels={debug:'DEBUG',info:'INFO',log:'INFO',warn:'WARN',error:'ERROR'} as const;
  for(const method of Object.keys(levels) as Array<keyof typeof levels>){
    const original=console[method].bind(console);
    console[method]=((...args:unknown[])=>{runtimeLogs?.write('main',levels[method],format(...args));original(...args);}) as typeof console[typeof method];
  }
  process.on('uncaughtExceptionMonitor',error=>runtimeLogs?.write('main','ERROR',error.stack??error.message));
  process.on('unhandledRejection',reason=>runtimeLogs?.write('main','ERROR',`Unhandled rejection: ${format(reason)}`));
}

function writeProcessLog(source:'main'|'renderer',level:RuntimeLogLevel,message:string):void{
  process.stderr.write(`[${source}] ${message}\n`);runtimeLogs?.write(source,level,message);
}
function resolveArtifact(value:unknown):{path:string;fileName:string;byteSize:number}{
  if(!isRecord(value)||value.rootId!=='root_default'||typeof value.relativePath!=='string'||value.relativePath.length<1||value.relativePath.length>1024)throw new Error('ARTIFACT_INVALID');
  const portable=value.relativePath.replaceAll('\\','/');if(portable.startsWith('/')||portable.split('/').some(segment=>!segment||segment==='.'||segment==='..'))throw new Error('ARTIFACT_INVALID');
  const root=realpathSync.native(activeWorkspacePath);const candidate=realpathSync.native(path.resolve(root,...portable.split('/')));const relative=path.relative(root,candidate);const fileStat=statSync(candidate);
  if(relative.startsWith('..')||path.isAbsolute(relative)||!fileStat.isFile())throw new Error('ARTIFACT_INVALID');
  const lower=candidate.toLocaleLowerCase();if(!['.docx','.xlsx','.xls','.pptx','.html','.htm','.md','.markdown'].some(extension=>lower.endsWith(extension)))throw new Error('ARTIFACT_TYPE_UNSUPPORTED');
  return {path:candidate,fileName:path.basename(candidate),byteSize:fileStat.size};
}

function diagnosticId():string{return `diag_${randomUUID().replaceAll('-','').slice(0,16)}`;}
function stableErrorCode(error:unknown):string{const message=error instanceof Error?error.message:'';return /^(RELEASE_|INSTALL_|RUNTIME_|DATABASE_|HOST_)[A-Z0-9_]+$/.test(message)?message:'HOST_START_FAILED';}

function assertSender(sender: Electron.WebContents): void {
  const window=liveMainWindow();if (!window || sender !== window.webContents) throw new Error('Untrusted IPC sender');
}
function parseStartInput(value: unknown): { threadId?: string; message: string;profileId?:string; explicitSkillIds:string[] } {
  if (!isRecord(value) || typeof value.message !== 'string' || value.message.trim().length < 1 || value.message.length > 100_000)
    throw new Error('Invalid turn input');
  if (value.threadId !== undefined) assertId(value.threadId, 'thr');
  if(value.profileId!==undefined&&!['deepseek-v4-pro','qwen3.7-flash'].includes(String(value.profileId)))throw new Error('Invalid provider profile');
  const ids=value.explicitSkillIds===undefined?[]:value.explicitSkillIds;if(!Array.isArray(ids)||ids.length>5||new Set(ids).size!==ids.length)throw new Error('Invalid explicit Skills');ids.forEach(id=>assertId(id,'skl'));
  return { threadId: value.threadId as string | undefined, message: value.message.trim(),profileId:value.profileId as string|undefined,explicitSkillIds:ids as string[] };
}
function assertId(value: unknown, prefix: string): asserts value is string {
  if (typeof value !== 'string' || !new RegExp(`^${prefix}_[A-Za-z0-9_-]{8,}$`).test(value)) throw new Error('Invalid identifier');
}
function isRecord(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null; }
function parseMcpDraft(value:unknown):import('./protocol.js').McpServerDraft{if(!isRecord(value)||typeof value.name!=='string'||Array.from(value.name.trim()).length<1||Array.from(value.name).length>80||(value.transport!=='STDIO'&&value.transport!=='STREAMABLE_HTTP')||typeof value.enabled!=='boolean')throw new Error('Invalid MCP configuration');if(value.transport==='STDIO'&&(typeof value.executable!=='string'||!path.isAbsolute(value.executable)||/[\\/](cmd|powershell|pwsh|sh|bash)(\.exe)?$/i.test(value.executable)))throw new Error('Invalid STDIO configuration');if(value.transport==='STREAMABLE_HTTP'){if(typeof value.endpoint!=='string')throw new Error('Invalid HTTP configuration');try{const endpoint=new URL(value.endpoint);if((endpoint.protocol!=='http:'&&endpoint.protocol!=='https:')||!endpoint.hostname||endpoint.username||endpoint.password||endpoint.hash)throw new Error('Invalid HTTP configuration');}catch{throw new Error('Invalid HTTP configuration');}}return value as import('./protocol.js').McpServerDraft;}
function newId(prefix: string): string { return `${prefix}_${randomUUID()}`; }

async function connectMcpWithStoredCredential(serverId: string, suppliedToken?: string): Promise<import('./protocol.js').McpServerSummary> {
  const page = await supervisor.listMcpServers();
  let server = page.items.find(item => item.id === serverId);
  if (!server) throw new Error('MCP server not found');
  if (suppliedToken !== undefined) {
    const credentialRef = await credentialStore.saveMcp(server.credentialRef ?? undefined, suppliedToken);
    const draft:import('./protocol.js').McpServerDraft={id:server.id,name:server.name,transport:server.transport,enabled:server.enabled,credentialRef};
    if(server.transport==='STDIO'){draft.executable=server.executable??undefined;draft.arguments=server.arguments;draft.workingDirectory=server.workingDirectory??undefined;draft.allowedEnvironmentNames=server.allowedEnvironmentNames;}
    else draft.endpoint=server.endpoint??undefined;
    server = await supervisor.saveMcpServer(draft);
  }
  const bearerToken = await credentialStore.loadMcp(server.credentialRef);
  return await supervisor.connectMcpServer(serverId, bearerToken);
}

async function restoreMcpConnections(): Promise<void> {
  const page = await supervisor.listMcpServers();
  await Promise.allSettled(page.items.filter(server => server.enabled).map(server => connectMcpWithStoredCredential(server.id)));
}

async function deleteMcpServer(serverId: string): Promise<void> {
  const page = await supervisor.listMcpServers();
  const server = page.items.find(item => item.id === serverId);
  if (!server) throw new Error('MCP server not found');
  // Close the execution path before removing the corresponding local secret.
  await supervisor.deleteMcpServer(serverId);
  await credentialStore.deleteMcp(server.credentialRef);
}

async function runDesktopSmoke(): Promise<void> {
  if(process.env.AGENT_DESKTOP_W7_SMOKE==='1'){
    if(process.env.AGENT_DESKTOP_W7_MAIN_RESTORE==='1'){
      if(!recoveredFromUncleanExit)throw new Error('Main crash marker was not recovered');
      const snapshots=await Promise.all((await supervisor.listThreads()).items.map(thread=>supervisor.snapshot(thread.id)));
      if(!snapshots.some(snapshot=>snapshot.timeline.some(item=>item.kind==='ASSISTANT_MESSAGE'&&item.value.content==='Mock response: week7 main crash persisted')))
        throw new Error('Committed Main crash fixture was not recovered');
      smokeLog('week7-main-restored');smokeLog('week7-smoke-passed');await supervisor.stop();exitSmoke(0);return;
    }
    if(process.env.AGENT_DESKTOP_W7_MAIN_CRASH==='1'){
      const completed=await startSmokeTurn('week7 main crash persisted');await completed.finished;
      const snapshot=await supervisor.snapshot(completed.threadId);
      if(!snapshot.turns.some(turn=>turn.id===completed.turnId&&turn.status==='COMPLETED'))throw new Error('Main crash fixture was not committed');
      smokeLog(`week7-main-crash-host-pid:${supervisor.processIdForWeek7Smoke()}`);
      if(process.env.AGENT_DESKTOP_W7_EXTERNAL_MAIN_CRASH==='1'){
        // The acceptance launcher terminates only this Main PID, without its
        // process tree, then proves the Host exits through the parent pipe.
        await new Promise<void>(()=>undefined);
        return;
      }
      // Skip the normal graceful-shutdown path so the Host must observe its
      // parent pipe closing. Electron's packaged Main can retain Chromium
      // processes after process.exit(), while app.exit() reliably terminates
      // the native process tree with the requested code.
      quitting=true;
      app.exit(77);
      return;
    }
    if(process.env.AGENT_DESKTOP_W7_UPGRADE_VERIFY==='1'){
      const expectedVersion=process.env.AGENT_DESKTOP_W7_EXPECTED_VERSION;
      if(expectedVersion&&releaseManifest.appVersion!==expectedVersion)throw new Error('Upgraded application version mismatch');
      const threads=(await supervisor.listThreads()).items;
      if(threads.length<2)throw new Error('Previous-release Threads were not preserved');
      const snapshots=await Promise.all(threads.map(thread=>supervisor.snapshot(thread.id)));
      if(!snapshots.some(snapshot=>snapshot.timeline.some(item=>item.kind==='TOOL_CALL'&&item.value.status==='SUCCEEDED')))throw new Error('Previous-release Tool Items were not preserved');
      const servers=(await supervisor.listMcpServers()).items;
      if(!servers.some(server=>server.transport==='STDIO')||!servers.some(server=>server.credentialRef))throw new Error('Previous-release MCP configuration or credential reference was not preserved');
      const credentialServer=servers.find(server=>server.credentialRef)!;
      if(!await credentialStore.loadMcp(credentialServer.credentialRef))throw new Error('Previous-release safeStorage ciphertext is unreadable');
      const skill=(await supervisor.listSkills('USER')).items.find(item=>item.name==='managed-import'&&item.status==='VALID');
      if(!skill||(await supervisor.getSkillInstallation(skill.id)).managed!==true)throw new Error('Previous-release managed Skill was not preserved');
      if(!(await supervisor.listFileRoots('ws_default')).some(root=>root.id==='root_default'))throw new Error('Previous-release file root was not preserved');
      if(path.resolve(appPaths.appHome).toLowerCase().startsWith(path.resolve(appPaths.installRoot).toLowerCase()+path.sep))throw new Error('appHome was moved into the installation directory');
      smokeLog('week7-upgrade-data-preserved');smokeLog('week7-smoke-passed');await supervisor.stop();exitSmoke(0);return;
    }
    const originalWindow=mainWindow;
    if(process.env.AGENT_DESKTOP_W7_RENDERER_SMOKE==='1'&&originalWindow){
      const rendererPid=originalWindow.webContents.getOSProcessId();
      if(!Number.isSafeInteger(rendererPid)||rendererPid<=0||rendererPid===process.pid)throw new Error('Week 7 Renderer process is unavailable');
      // Electron's forcefullyCrashRenderer() can be a no-op for a hidden packaged
      // window on Windows. Terminating this window's exact Renderer PID still
      // exercises the production render-process-gone handler deterministically.
      process.kill(rendererPid,'SIGTERM');
      await waitFor(()=>Promise.resolve(Boolean(liveMainWindow()&&mainWindow!==originalWindow)),60_000);
      smokeLog('week7-renderer-recovered');
    }
    const nodeExecutable=process.env.AGENT_DESKTOP_NODE;
    const fixtureScript=process.env.AGENT_DESKTOP_W7_MCP_FIXTURE;
    const dangerMarker=process.env.AGENT_DESKTOP_W7_MCP_MARKER;
    const skillSource=process.env.AGENT_DESKTOP_W7_SKILL_SOURCE;
    const scriptMarker=process.env.AGENT_DESKTOP_W7_SCRIPT_MARKER;
    if(!nodeExecutable||!fixtureScript||!dangerMarker||!skillSource||!scriptMarker)throw new Error('Week 7 no-replay fixtures are missing');

    const pendingTurns:Array<Awaited<ReturnType<typeof startSmokeTurn>>> = [];
    const workspacePending=await startSmokeTurn('__week6_tool__|workspace.write_text|write|{"rootId":"root_default","relativePath":"week7-no-replay.txt","content":"must-not-run","overwrite":false}');
    await waitFor(()=>Promise.resolve(workspacePending.events.some(event=>event.type==='CUSTOM approval.required')),5_000);pendingTurns.push(workspacePending);void workspacePending.finished.catch(()=>undefined);

    const dangerServer=await supervisor.saveMcpServer({name:'Week 7 no replay',transport:'STDIO',enabled:true,executable:nodeExecutable,arguments:[fixtureScript,'stdio',dangerMarker],workingDirectory:path.dirname(fixtureScript),allowedEnvironmentNames:[]});
    await connectMcpWithStoredCredential(dangerServer.id);
    const mcpPending=await startSmokeTurn(`__week6_tool__|mcp:${dangerServer.id}:danger|danger|{}`);
    await waitFor(()=>Promise.resolve(mcpPending.events.some(event=>event.type==='CUSTOM approval.required')),5_000);pendingTurns.push(mcpPending);void mcpPending.finished.catch(()=>undefined);

    const inspected=await supervisor.inspectLocalSkill(skillSource);
    if(inspected.conflict!=='NONE')throw new Error(`Week 7 Skill fixture conflict: ${inspected.conflict}`);
    const installed=await supervisor.commitSkillImport({sessionId:inspected.sessionId,action:'INSTALL'});
    await supervisor.setSkillEnabled(installed.skillId,true);
    const resource=(await supervisor.listSkillResources(installed.skillId)).find(item=>item.relativePath==='scripts/safe.ps1');
    if(!resource)throw new Error('Week 7 Skill script fixture is missing');
    const scriptMessage=`__week6_tool__|skill.run_script|run|${JSON.stringify({skillId:installed.skillId,resourceId:resource.id,workingRootId:'root_default',arguments:[scriptMarker]})}`;
    const scriptPending=await startSmokeTurn(scriptMessage,newId('thr'),[installed.skillId]);
    await waitFor(()=>Promise.resolve(scriptPending.events.some(event=>event.type==='CUSTOM approval.required')),5_000);pendingTurns.push(scriptPending);void scriptPending.finished.catch(()=>undefined);

    if(existsSync(dangerMarker)||existsSync(path.join(appPaths.appHome,'workspace',scriptMarker)))throw new Error('Week 7 pending side effect executed before recovery');
    await supervisor.crashForWeek7Smoke();
    await waitFor(()=>Promise.resolve(supervisor.state.status==='restarting'||supervisor.state.status==='ready'),2_000);
    await waitFor(()=>Promise.resolve(supervisor.state.status==='ready'),hostRecoveryTimeoutMs()+5_000);
    for(const pending of pendingTurns){
      await waitFor(async()=>{const snapshot=await supervisor.snapshot(pending.threadId);return snapshot.turns.some(turn=>turn.id===pending.turnId&&turn.status==='CANCELLED')&&snapshot.timeline.some(item=>item.kind==='APPROVAL'&&item.value.decision==='INVALIDATED');},30_000,5_000);
    }
    if(existsSync(path.join(appPaths.appHome,'workspace','week7-no-replay.txt'))||existsSync(dangerMarker)||existsSync(path.join(appPaths.appHome,'workspace',scriptMarker)))throw new Error('Week 7 recovery replayed a side effect');
    smokeLog('week7-host-recovered-zero-replay');
    await supervisor.crashForWeek7Smoke();
    await waitFor(()=>Promise.resolve(supervisor.state.status==='crash-loop'),5_000);
    smokeLog('week7-crash-loop');
    const recoveredWindow=liveMainWindow();
    if(process.env.AGENT_DESKTOP_W7_RENDERER_SMOKE==='1'&&recoveredWindow){
      // The first crash above exercises Electron's native render-process-gone event. Calling
      // forcefullyCrashRenderer twice in one Windows Electron process is not deterministic, so
      // drive the same production handler directly for the consecutive-crash policy assertion.
      handleRendererGone(recoveredWindow);
      await waitFor(()=>Promise.resolve(Boolean(liveMainWindow()&&mainWindow!==recoveredWindow)),60_000);smokeLog('week7-renderer-crash-loop');
    }
    await supervisor.stop();smokeLog('week7-smoke-passed');exitSmoke(0);return;
  }
  // Week 4 additionally opens a hidden production Renderer and drives its real DOM/IPC path.
  // Other weekly smoke modes remain main-process-only for headless compatibility.
  if (supervisor.state.status !== 'ready') throw new Error('Electron main process did not observe ready Host');
  if(process.env.AGENT_DESKTOP_W6_SMOKE==='1'){
    if(process.env.AGENT_DESKTOP_W6_RESTORE==='1'){
      const snapshots=await Promise.all((await supervisor.listThreads()).items.map(thread=>supervisor.snapshot(thread.id)));
      const recovered=snapshots.some(snapshot=>snapshot.turns.some(turn=>turn.status==='CANCELLED'&&turn.errorCode==='APPROVAL_INVALIDATED')&&snapshot.timeline.some(item=>item.kind==='APPROVAL'&&item.value.decision==='INVALIDATED'));
      if(!recovered)throw new Error('Week 6 pending approval was not invalidated on restart');
      smokeLog('week6-restore-passed');smokeLog('smoke-passed');await supervisor.stop();exitSmoke(0);return;
    }
    const nodeExecutable=process.env.AGENT_DESKTOP_NODE;const fixtureScript=process.env.AGENT_DESKTOP_W6_MCP_FIXTURE;const scriptPath=process.env.AGENT_DESKTOP_W6_SCRIPT;
    if(!nodeExecutable||!fixtureScript||!scriptPath)throw new Error('Week 6 fixtures are missing');
    if(rendererSmokeMode){await verifyWeek6RendererApproval();await verifyArtifactRendererSmoke();}
    else{
      const write=await startSmokeTurn('__week6_tool__|workspace.write_text|write|{"rootId":"root_default","relativePath":"renderer-approved.txt","content":"approved","overwrite":false}');
      await resolveSmokeApproval(write,'ALLOW_ONCE');await write.finished;
    }
    if(!existsSync(path.join(configuredAppHome,'workspace','renderer-approved.txt')))throw new Error('Approved workspace write did not execute');

    const traversal=await startSmokeTurn('__week6_tool__|workspace.read_text|read|{"rootId":"root_default","relativePath":"../outside.txt"}');await traversal.finished;
    if(!traversal.events.some(event=>event.type==='TOOL_CALL_RESULT'&&event.value.errorCode==='FILE_PATH_INVALID')||traversal.events.some(event=>event.type==='CUSTOM approval.required'))throw new Error('Workspace traversal did not fail closed');

    let servers=(await supervisor.listMcpServers()).items;
    if(!servers.some(item=>item.name==='Week 6 danger'))await supervisor.saveMcpServer({name:'Week 6 danger',transport:'STDIO',enabled:true,executable:nodeExecutable,arguments:[fixtureScript,'stdio'],workingDirectory:path.dirname(fixtureScript),allowedEnvironmentNames:[]});
    servers=(await supervisor.listMcpServers()).items;const dangerServer=servers.find(item=>item.name==='Week 6 danger')!;await connectMcpWithStoredCredential(dangerServer.id);
    const dangerTool=`mcp:${dangerServer.id}:danger`;const dangerMarker=process.env.AGENT_W6_DANGER_MARKER!;
    const once=await startSmokeTurn(`__week6_tool__|${dangerTool}|danger|{}`);if(existsSync(dangerMarker))throw new Error('MCP danger executed before approval');await resolveSmokeApproval(once,'ALLOW_ONCE');await once.finished;
    if(markerCount(dangerMarker)!==1)throw new Error('ALLOW_ONCE did not execute exactly once');
    const denied=await startSmokeTurn(`__week6_tool__|${dangerTool}|danger|{}`,once.threadId);await resolveSmokeApproval(denied,'DENY');await denied.finished;if(markerCount(dangerMarker)!==1)throw new Error('Denied MCP Tool executed');
    const threadGrant=await startSmokeTurn(`__week6_tool__|${dangerTool}|danger|{}`);await resolveSmokeApproval(threadGrant,'ALLOW_THREAD');await threadGrant.finished;
    const reused=await startSmokeTurn(`__week6_tool__|${dangerTool}|danger|{}`,threadGrant.threadId);await reused.finished;if(reused.events.some(event=>event.type==='CUSTOM approval.required')||markerCount(dangerMarker)!==3)throw new Error('Thread grant scope was not reused exactly once');
    const isolated=await startSmokeTurn(`__week6_tool__|${dangerTool}|danger|{}`);await waitFor(()=>Promise.resolve(isolated.events.some(event=>event.type==='CUSTOM approval.required')),5_000);await supervisor.cancel(isolated.turnId);await isolated.finished;if(markerCount(dangerMarker)!==3)throw new Error('Cancelled approval executed MCP Tool');

    const skill=(await supervisor.listSkills('BUILTIN','VALID')).items.find(item=>item.name==='week6-safe-script');if(!skill)throw new Error('Week 6 script Skill is missing');
    const resource=(await supervisor.listSkillResources(skill.id)).find(item=>item.relativePath==='scripts/safe.ps1');if(!resource)throw new Error('Week 6 script resource is missing');
    const scriptMessage=`__week6_tool__|skill.run_script|run|${JSON.stringify({skillId:skill.id,resourceId:resource.id,workingRootId:'root_default',arguments:['script-approved.txt']})}`;
    const script=await startSmokeTurn(scriptMessage,newId('thr'),[skill.id]);await resolveSmokeApproval(script,'ALLOW_ONCE');await script.finished;
    if(!existsSync(path.join(configuredAppHome,'workspace','script-approved.txt')))throw new Error('Approved Skill script did not execute');
    const stale=await startSmokeTurn(scriptMessage,newId('thr'),[skill.id]);await waitFor(()=>Promise.resolve(stale.events.some(event=>event.type==='CUSTOM approval.required')),5_000);appendFileSync(scriptPath,"\n# stale after approval\n",'utf8');await resolveSmokeApproval(stale,'ALLOW_ONCE');await stale.finished;
    if(!stale.events.some(event=>event.type==='TOOL_CALL_RESULT'&&event.value.errorCode==='SCRIPT_STALE'))throw new Error('Changed Skill script did not invalidate approval');

    const restartPending=await startSmokeTurn('__week6_tool__|workspace.write_text|write|{"rootId":"root_default","relativePath":"restart-late.txt","content":"must-not-run","overwrite":false}');
    await waitFor(()=>Promise.resolve(restartPending.events.some(event=>event.type==='CUSTOM approval.required')),5_000);void restartPending.finished.catch(()=>undefined);await supervisor.abortForSmoke();
    smokeLog('week6-first-pass');smokeLog('smoke-passed');exitSmoke(0);return;
  }
  if(process.env.AGENT_DESKTOP_W5_SMOKE==='1'){
    const sourceV1=process.env.AGENT_DESKTOP_W5_SOURCE_V1;const sourceV2=process.env.AGENT_DESKTOP_W5_SOURCE_V2;
    if(!sourceV1||!sourceV2)throw new Error('Week 5 local source fixtures are missing');
    if(process.env.AGENT_DESKTOP_W5_RESTORE==='1'){
      const skill=(await supervisor.listSkills('USER')).items.find(item=>item.name==='managed-import'&&item.status==='VALID');
      if(!skill||skill.enabled)throw new Error('Week 5 managed Skill did not restart in disabled state');
      const installation=await supervisor.getSkillInstallation(skill.id);if(!installation.managed||!installation.rollbackAvailable)throw new Error('Week 5 provenance did not survive restart');
      if(rendererSmokeMode)await verifyWeek5RendererSmoke('managed-import','W5_VERSION_ONE');
      await supervisor.removeInstalledSkill(skill.id);
      if((await supervisor.listSkills('USER')).items.some(item=>item.name==='managed-import'&&item.status!=='MISSING'))throw new Error('Week 5 managed removal failed');
    }else{
      const inspected=await supervisor.inspectLocalSkill(sourceV1);if(inspected.conflict!=='NONE'||inspected.source.type!=='LOCAL_DIRECTORY'||!inspected.resources.some(item=>item.executableCandidate))throw new Error('Week 5 local preview is incomplete');
      const installed=await supervisor.commitSkillImport({sessionId:inspected.sessionId,action:'INSTALL'});if(installed.enabled)throw new Error('Imported Skill was enabled automatically');
      const replacement=await supervisor.inspectLocalSkill(sourceV2);if(replacement.conflict!=='REPLACE_REQUIRED'||!replacement.expectedExistingContentHash)throw new Error('Week 5 replacement conflict was not detected');
      const replaced=await supervisor.commitSkillImport({sessionId:replacement.sessionId,action:'REPLACE',expectedExistingContentHash:replacement.expectedExistingContentHash});if(!replaced.rollbackAvailable||replaced.enabled)throw new Error('Week 5 replacement state is invalid');
      const rolledBack=await supervisor.rollbackInstalledSkill(replaced.skillId);const detail=await supervisor.getSkill(rolledBack.skillId);if(!detail.instructions?.includes('W5_VERSION_ONE')||rolledBack.enabled)throw new Error('Week 5 rollback did not restore the previous disabled version');
      if(rendererSmokeMode)await verifyWeek5RendererSmoke('managed-import','W5_VERSION_ONE');
    }
    smokeLog('week5-passed');smokeLog('smoke-passed');await supervisor.stop();exitSmoke(0);return;
  }
  if(process.env.AGENT_DESKTOP_W4_SMOKE==='1'){
    const skills=(await supervisor.listSkills()).items;
    if(process.env.AGENT_DESKTOP_W4_RESTORE==='1'){
      const disabled=skills.find(item=>item.name==='script-blocked'&&item.scope==='WORKSPACE');
      if(!disabled||disabled.enabled)throw new Error('Week 4 disabled Skill state did not survive restart');
      const snapshots=await Promise.all((await supervisor.listThreads()).items.map(thread=>supervisor.snapshot(thread.id)));
      if(!snapshots.some(snapshot=>snapshot.timeline.some(item=>item.kind==='SKILL_ACTIVATION'&&item.value.reason==='EXPLICIT'))||
        !snapshots.some(snapshot=>snapshot.timeline.some(item=>item.kind==='SKILL_ACTIVATION'&&item.value.reason==='IMPLICIT')))throw new Error('Week 4 activation history did not survive restart');
      if(rendererSmokeMode)await verifyWeek4RendererHistory();
    }else{
      const explicit=skills.find(item=>item.name==='explicit-summary'&&item.status==='VALID');
      const implicit=skills.find(item=>item.name==='implicit-code-review'&&item.status==='VALID');
      const resources=skills.find(item=>item.name==='resource-on-demand'&&item.status==='VALID');
      if(!explicit||!implicit||!resources)throw new Error('Week 4 fixture catalog is incomplete');
      const detail=await supervisor.getSkill(explicit.id);if(!detail.instructions?.includes('EXPLICIT_BODY_MARKER'))throw new Error('Skill viewer did not return bounded instructions');
      if(rendererSmokeMode)await verifyWeek4RendererSmoke(explicit.id);
      const threadSnapshots=await Promise.all((await supervisor.listThreads()).items.map(thread=>supervisor.snapshot(thread.id)));
      const rendererThread=threadSnapshots.find(snapshot=>snapshot.timeline.some(item=>item.kind==='SKILL_ACTIVATION'&&item.value.reason==='EXPLICIT'));
      if(!rendererThread)throw new Error('Renderer did not submit the explicit Skill turn');
      const second=await startSmokeTurn('Please review this source code carefully',rendererThread.thread.id);await second.finished;
      const descriptors=await supervisor.listSkillResources(resources.id);const needed=descriptors.find(item=>item.relativePath==='references/needed.md');if(!needed)throw new Error('Resource descriptor is missing');
      const third=await startSmokeTurn('Load the needed reference only',rendererThread.thread.id);await third.finished;
      const snapshot=await supervisor.snapshot(rendererThread.thread.id);
      if(!snapshot.timeline.some(item=>item.kind==='SKILL_ACTIVATION'&&item.value.reason==='EXPLICIT')||
        !snapshot.timeline.some(item=>item.kind==='SKILL_ACTIVATION'&&item.value.reason==='IMPLICIT')||
        !third.events.some(item=>item.type==='CUSTOM skill.resource_loaded')||
        !snapshot.timeline.some(item=>item.kind==='ASSISTANT_MESSAGE'&&String(item.value.content).includes('Resource loaded on demand')))throw new Error('Week 4 progressive loading timeline is incomplete');
      await supervisor.refreshSkills('WORKSPACE');
      const script=skills.find(item=>item.name==='script-blocked'&&item.scope==='WORKSPACE');
      if(!script)throw new Error('Week 4 script fixture is missing');
      await supervisor.setSkillEnabled(script.id,false);
    }
    smokeLog('week4-passed');smokeLog('smoke-passed');await supervisor.stop();exitSmoke(0);return;
  }
  if (process.env.AGENT_DESKTOP_W3_SMOKE === '1') {
    const nodeExecutable=process.env.AGENT_DESKTOP_NODE;const fixtureScript=process.env.AGENT_DESKTOP_W3_FIXTURE;const httpEndpoint=process.env.AGENT_DESKTOP_W3_HTTP_ENDPOINT;
    if(!nodeExecutable||!fixtureScript||!httpEndpoint)throw new Error('Week 3 fixture configuration is missing');
    let servers=(await supervisor.listMcpServers()).items;
    const hasWeek3Fixtures=servers.some(server=>server.name==='Week 3 STDIO')&&servers.some(server=>server.name==='Week 3 HTTP');
    if(!hasWeek3Fixtures){
      // A release candidate may seed a real, enabled MCP catalog. Week 3 is an
      // isolated fixture test and must never depend on that service being online.
      for(const server of servers)await supervisor.deleteMcpServer(server.id);
      await supervisor.saveMcpServer({name:'Week 3 STDIO',transport:'STDIO',enabled:true,executable:nodeExecutable,arguments:[fixtureScript,'stdio'],workingDirectory:path.dirname(fixtureScript),allowedEnvironmentNames:[]});
      await supervisor.saveMcpServer({name:'Week 3 HTTP',transport:'STREAMABLE_HTTP',enabled:true,endpoint:httpEndpoint});servers=(await supervisor.listMcpServers()).items;
    }
    if(process.env.AGENT_DESKTOP_W3_RESTORE!=='1')for(const server of servers.filter(item=>item.enabled))try{await connectMcpWithStoredCredential(server.id,server.transport==='STREAMABLE_HTTP'?process.env.AGENT_DESKTOP_W3_BEARER:undefined);}catch(error){const diagnostic=await supervisor.mcpDiagnostic(server.id);throw new Error(`${server.name}: ${diagnostic.errorCode??'MCP_CONNECT_FAILED'} ${diagnostic.summary??''}`,{cause:error});}
    servers=(await supervisor.listMcpServers()).items;if(servers.filter(server=>server.status==='READY').length<2)throw new Error('Both Week 3 MCP transports must be READY');
    const stdio=servers.find(server=>server.transport==='STDIO')!;const http=servers.find(server=>server.transport==='STREAMABLE_HTTP')!;
    const stdioTools=await supervisor.listMcpTools(stdio.id);const httpTools=await supervisor.listMcpTools(http.id);if(!stdioTools.some(tool=>tool.name==='echo')||!httpTools.some(tool=>tool.name==='sum'))throw new Error('Week 3 tool catalogs are incomplete');
    if(process.env.AGENT_DESKTOP_W3_RESTORE==='1'){
      const snapshots=await Promise.all((await supervisor.listThreads()).items.map(thread=>supervisor.snapshot(thread.id)));if(!snapshots.some(snapshot=>snapshot.timeline.some(item=>item.kind==='TOOL_CALL'&&item.value.status==='SUCCEEDED'&&Number(item.value.elapsedMs)>=0)))throw new Error('Week 3 Tool Card did not survive restart');
    }else{
      const echo=await startSmokeTurn(`__week3_tool__|mcp:${stdio.id}:echo|echo|{"text":"hello"}`);await echo.finished;const sum=await startSmokeTurn(`__week3_tool__|mcp:${http.id}:sum|sum|{"a":2,"b":3}`,echo.threadId);await sum.finished;const snapshot=await supervisor.snapshot(echo.threadId);
      if(!snapshot.timeline.some(item=>item.kind==='TOOL_CALL'&&String(item.value.resultSummary).includes('hello'))||!snapshot.timeline.some(item=>item.kind==='TOOL_CALL'&&String(item.value.resultSummary).includes('5')))throw new Error('Week 3 Tool Card timeline is incomplete');
    }
    await verifyMcpDeleteSmoke(httpEndpoint);
    smokeLog('week3-passed');smokeLog('smoke-passed');await supervisor.stop();exitSmoke(0);return;
  }
  if (process.env.AGENT_DESKTOP_W3_SMOKE === '1') {
    const nodeExecutable=process.env.AGENT_DESKTOP_NODE;const fixtureScript=process.env.AGENT_DESKTOP_W3_FIXTURE;const httpEndpoint=process.env.AGENT_DESKTOP_W3_HTTP_ENDPOINT;
    if(!nodeExecutable||!fixtureScript||!httpEndpoint)throw new Error('Week 3 fixture configuration is missing');
    let servers=(await supervisor.listMcpServers()).items;
    if(servers.length===0){
      await supervisor.saveMcpServer({name:'Week 3 STDIO',transport:'STDIO',enabled:true,executable:nodeExecutable,arguments:[fixtureScript,'stdio'],workingDirectory:path.dirname(fixtureScript),allowedEnvironmentNames:[]});
      await supervisor.saveMcpServer({name:'Week 3 HTTP',transport:'STREAMABLE_HTTP',enabled:true,endpoint:httpEndpoint});servers=(await supervisor.listMcpServers()).items;
    }
    for(const server of servers.filter(item=>item.enabled))await connectMcpWithStoredCredential(server.id,server.transport==='STREAMABLE_HTTP'?process.env.AGENT_DESKTOP_W3_BEARER:undefined);
    servers=(await supervisor.listMcpServers()).items;if(servers.filter(server=>server.status==='READY').length<2)throw new Error('Both Week 3 MCP transports must be READY');
    const stdio=servers.find(server=>server.transport==='STDIO')!;const http=servers.find(server=>server.transport==='STREAMABLE_HTTP')!;
    const stdioTools=await supervisor.listMcpTools(stdio.id);const httpTools=await supervisor.listMcpTools(http.id);if(!stdioTools.some(tool=>tool.name==='echo')||!httpTools.some(tool=>tool.name==='sum'))throw new Error('Week 3 tool catalogs are incomplete');
    if(process.env.AGENT_DESKTOP_W3_RESTORE==='1'){
      const snapshots=await Promise.all((await supervisor.listThreads()).items.map(thread=>supervisor.snapshot(thread.id)));if(!snapshots.some(snapshot=>snapshot.timeline.some(item=>item.kind==='TOOL_CALL'&&item.value.status==='SUCCEEDED'&&Number(item.value.elapsedMs)>=0)))throw new Error('Week 3 Tool Card did not survive restart');
    }else{
      const echo=await startSmokeTurn(`__week3_tool__|mcp:${stdio.id}:echo|echo|{"text":"hello"}`);await echo.finished;const sum=await startSmokeTurn(`__week3_tool__|mcp:${http.id}:sum|sum|{"a":2,"b":3}`,echo.threadId);await sum.finished;const snapshot=await supervisor.snapshot(echo.threadId);
      if(!snapshot.timeline.some(item=>item.kind==='TOOL_CALL'&&String(item.value.resultSummary).includes('hello'))||!snapshot.timeline.some(item=>item.kind==='TOOL_CALL'&&String(item.value.resultSummary).includes('5')))throw new Error('Week 3 Tool Card timeline is incomplete');
    }
    smokeLog('week3-passed');smokeLog('smoke-passed');await supervisor.stop();exitSmoke(0);return;
  }
  if (process.env.AGENT_DESKTOP_W2_RESTORE === '1') {
    const page = await supervisor.listThreads();
    const snapshots = await Promise.all(page.items.map(thread => supervisor.snapshot(thread.id)));
    const restored = snapshots.some(snapshot => snapshot.timeline.some(item => item.kind === 'TOOL_CALL' && item.value.toolName === 'fixture.timeline' && item.value.resultSummary === 'fixture result')) &&
      snapshots.some(snapshot => snapshot.turns.some(turn => turn.status === 'COMPLETED')) &&
      snapshots.some(snapshot => snapshot.turns.some(turn => turn.status === 'CANCELLED')) &&
      snapshots.some(snapshot => snapshot.turns.some(turn => turn.status === 'INTERRUPTED'));
    if (!restored) throw new Error('Week 2 restart recovery snapshot is incomplete');
    smokeLog('smoke-passed');
    await supervisor.stop(); exitSmoke(0); return;
  }
  if (process.env.AGENT_DESKTOP_W2_INTERRUPT === '1') {
    const interrupted = await startSmokeTurn('x'.repeat(1000));
    await waitFor(async () => (await supervisor.snapshot(interrupted.threadId)).timeline.some(item =>
      item.kind === 'ASSISTANT_MESSAGE' && String(item.value.content ?? '').length > 0), 5_000);
    void interrupted.finished.catch(() => undefined);
    await supervisor.abortForSmoke();
    smokeLog('week2-interrupted');
    smokeLog('smoke-passed');
    exitSmoke(0); return;
  }
  const completed = await startSmokeTurn('electron smoke');
  smokeLog('smoke-turn-started');
  await completed.finished;
  const completedSnapshot = await supervisor.snapshot(completed.threadId);
  if (!completedSnapshot.timeline.some(item => item.kind === 'ASSISTANT_MESSAGE' && item.value.content === 'Mock response: electron smoke') ||
    !completedSnapshot.turns.some(turn => turn.id === completed.turnId && turn.status === 'COMPLETED')) throw new Error('Completed turn was not persisted');
  console.log('Desktop smoke verification passed: Electron main process, Host SSE, persistence and snapshot recovery.');
  if (process.env.AGENT_DESKTOP_W2_SMOKE === '1') {
    const fixture = await startSmokeTurn('__week2_tool_fixture__', completed.threadId);
    await fixture.finished;
    const fixtureSnapshot = await supervisor.snapshot(completed.threadId);
    if (!fixtureSnapshot.timeline.some(item => item.kind === 'TOOL_CALL' && item.value.toolName === 'fixture.timeline' && item.value.resultSummary === 'fixture result')) {
      throw new Error('Tool timeline fixture was not persisted');
    }
    const cancelling = await startSmokeTurn('x'.repeat(1000), completed.threadId);
    await waitFor(async () => (await supervisor.snapshot(cancelling.threadId)).turns.some(turn =>
      turn.id === cancelling.turnId && (turn.status === 'QUEUED' || turn.status === 'RUNNING')), 5_000);
    const cancellationStartedAt = Date.now();
    await supervisor.cancel(cancelling.turnId);
    await cancelling.finished;
    await waitFor(async () => (await supervisor.snapshot(cancelling.threadId)).turns.some(turn =>
      turn.id === cancelling.turnId && turn.status === 'CANCELLED'), 10_000);
    if (Date.now() - cancellationStartedAt > 10_000) throw new Error('Cancellation exceeded the 10 second bound');
    smokeLog('week2-cancelled');
  }
  smokeLog('smoke-passed');
  await supervisor.stop();
  app.exit(0);
}

async function verifyWeek4RendererSmoke(explicitSkillId:string):Promise<void>{
  const window=mainWindow;if(!window)throw new Error('Week 4 Renderer window was not created');
  const skillId=JSON.stringify(explicitSkillId);
  const result=await window.webContents.executeJavaScript(`(async()=>{
    const wait=async(check,label)=>{const deadline=Date.now()+20000;while(Date.now()<deadline){if(check())return;await new Promise(resolve=>setTimeout(resolve,50));}throw new Error('Renderer wait failed: '+label);};
    const button=text=>[...document.querySelectorAll('button')].find(item=>item.textContent?.trim()===text);
    await wait(()=>document.querySelector('.host-ready'),'host ready');
    button('技能广场')?.click();
    await wait(()=>document.querySelectorAll('.skill-card').length>=3,'Skill cards');
    const scope=document.querySelector('select[aria-label="来源"]');scope.value='WORKSPACE';scope.dispatchEvent(new Event('change',{bubbles:true}));
    await wait(()=>[...document.querySelectorAll('.skill-card strong')].some(item=>item.textContent==='explicit-summary'),'workspace filter');
    button('刷新')?.click();
    await wait(()=>document.querySelector('.validation-message')?.textContent?.includes('已刷新'),'workspace refresh');
    const card=name=>[...document.querySelectorAll('.skill-card')].find(item=>item.querySelector('strong')?.textContent===name);
    card('explicit-summary')?.querySelector('.skill-open')?.click();
    await wait(()=>document.querySelector('.plain-viewer')?.textContent?.includes('EXPLICIT_BODY_MARKER'),'plain viewer');
    if(document.querySelector('.plain-viewer script,.plain-viewer img,.plain-viewer a')||window.__week4SkillExecuted)throw new Error('Skill viewer rendered active content');
    let toggle=card('explicit-summary')?.querySelector('.skill-toggle');toggle?.click();
    await wait(()=>card('explicit-summary')?.querySelector('.skill-toggle')?.textContent==='启用','disable');
    toggle=card('explicit-summary')?.querySelector('.skill-toggle');toggle?.click();
    await wait(()=>card('explicit-summary')?.querySelector('.skill-toggle')?.textContent==='停用','enable');
    card('resource-on-demand')?.querySelector('.skill-open')?.click();
    await wait(()=>[...document.querySelectorAll('.skill-resources button')].some(item=>item.textContent?.includes('references/needed.md')),'resource list');
    [...document.querySelectorAll('.skill-resources button')].find(item=>item.textContent?.includes('references/needed.md'))?.click();
    await wait(()=>[...document.querySelectorAll('.plain-viewer')].some(item=>item.textContent?.includes('RESOURCE_NEEDED_MARKER')),'resource viewer');
    button('新建会话')?.click();
    await wait(()=>document.querySelector('select[aria-label="显式选择 Skill"]'),'composer');
    const picker=document.querySelector('select[aria-label="显式选择 Skill"]');picker.value=${skillId};picker.dispatchEvent(new Event('change',{bubbles:true}));
    await wait(()=>document.querySelector('.skill-chip')?.textContent?.includes('explicit-summary'),'Skill chip');
    const input=document.querySelector('textarea');Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(input,'Renderer explicit selection');input.dispatchEvent(new Event('input',{bubbles:true}));
    input.closest('form').requestSubmit();
    await wait(()=>[...document.querySelectorAll('.skill-activation')].some(item=>item.textContent?.includes('已显式启用')&&item.textContent?.includes('explicit-summary')),'explicit activation');
    await wait(()=>!document.querySelector('.skill-chip'),'chip cleared');
    await wait(()=>button('发送')&&!document.querySelector('button.stop'),'explicit turn completion');
    return {scope:scope.value,viewer:true,resource:true,explicit:true};
  })()`,true) as {scope?:string;viewer?:boolean;resource?:boolean;explicit?:boolean};
  if(result.scope!=='WORKSPACE'||!result.viewer||!result.resource||!result.explicit)throw new Error('Week 4 Renderer assertions were incomplete');
}

async function verifyWeek5RendererSmoke(skillName:string,bodyMarker:string):Promise<void>{
  const window=mainWindow;if(!window)throw new Error('Week 5 Renderer window was not created');
  const result=await window.webContents.executeJavaScript(`(async()=>{
    const wait=async(check,label)=>{const deadline=Date.now()+20000;while(Date.now()<deadline){if(check())return;await new Promise(resolve=>setTimeout(resolve,50));}throw new Error('Renderer wait failed: '+label);};
    const button=text=>[...document.querySelectorAll('button')].find(item=>item.textContent?.trim()===text);
    await wait(()=>document.querySelector('.host-ready'),'host ready');button('技能广场')?.click();
    await wait(()=>button('导入 Skill'),'import entry');
    const card=()=>[...document.querySelectorAll('.skill-card')].find(item=>item.querySelector('strong')?.textContent===${JSON.stringify(skillName)});
    await wait(()=>card(),'managed card');card()?.querySelector('.skill-open')?.click();
    await wait(()=>document.querySelector('.installation-card')?.textContent?.includes('由本应用管理'),'provenance');
    await wait(()=>document.querySelector('.plain-viewer')?.textContent?.includes(${JSON.stringify(bodyMarker)}),'managed viewer');
    button('导入 Skill')?.click();await wait(()=>document.querySelector('.skill-import-dialog'),'import dialog');
    if(!document.querySelector('.source-tabs')||!document.querySelector('.skill-import-dialog')?.textContent?.includes('页面不会收到绝对路径'))throw new Error('Import safety copy is missing');
    button('关闭')?.click();return {managed:true,dialog:true};
  })()`,true) as {managed?:boolean;dialog?:boolean};
  if(!result.managed||!result.dialog)throw new Error('Week 5 Renderer assertions were incomplete');
}

async function verifyWeek4RendererHistory():Promise<void>{
  const window=mainWindow;if(!window)throw new Error('Week 4 Renderer window was not created');
  await window.webContents.executeJavaScript(`(async()=>{const deadline=Date.now()+20000;while(Date.now()<deadline){const values=[...document.querySelectorAll('.skill-activation')].map(item=>item.textContent||'');if(values.some(value=>value.includes('已显式启用'))&&values.some(value=>value.includes('已隐式启用')))return true;await new Promise(resolve=>setTimeout(resolve,50));}throw new Error('Renderer activation history was not restored');})()`,true);
}

async function verifyMcpDeleteSmoke(endpoint: string): Promise<void> {
  let server = await supervisor.saveMcpServer({name:'Week 3 Delete',transport:'STREAMABLE_HTTP',enabled:false,endpoint});
  const credentialRef = await credentialStore.saveMcp(undefined, 'week3-delete-secret');
  server = await supervisor.saveMcpServer({id:server.id,name:server.name,transport:'STREAMABLE_HTTP',enabled:false,endpoint,credentialRef});
  if (await credentialStore.loadMcp(credentialRef) !== 'week3-delete-secret') throw new Error('MCP delete credential fixture was not stored');
  await deleteMcpServer(server.id);
  if ((await supervisor.listMcpServers()).items.some(item => item.id === server.id)) throw new Error('Deleted MCP server remains visible');
  if (await credentialStore.loadMcp(credentialRef) !== undefined) throw new Error('Deleted MCP credential remains on disk');
}

async function startSmokeTurn(message: string, threadId = newId('thr'),explicitSkillIds:string[]=[]): Promise<{ threadId: string; turnId: string; finished: Promise<void>;events:AgUiEvent[] }> {
  const turnId = newId('trn');
  const input: RunAgentInput = {
    threadId, runId: newId('run'), messages: [{ id: newId('msg'), role: 'user', content: message }],
    context: { turnId, workspaceId: 'ws_default', modelProfileId: 'mock',maxIterations:10 },explicitSkillIds
  };
  const started: AgUiEvent[] = [];
  const finished = supervisor.run(input, event => started.push(event));
  await waitFor(async () => started.some(event => event.type === 'RUN_STARTED'), 5_000);
  return { threadId, turnId, finished,events:started };
}

async function resolveSmokeApproval(turn:{events:AgUiEvent[]},decision:import('./protocol.js').ApprovalDecision):Promise<void>{
  await waitFor(()=>Promise.resolve(turn.events.some(event=>event.type==='CUSTOM approval.required')),5_000);
  const event=turn.events.find(item=>item.type==='CUSTOM approval.required');const approvalId=event?.value.approvalId;const fingerprint=event?.value.requestFingerprint;
  if(typeof approvalId!=='string'||typeof fingerprint!=='string')throw new Error('Approval event is incomplete');
  await supervisor.resolveApproval(approvalId,decision,fingerprint);
}

function markerCount(file:string):number{return existsSync(file)?readFileSync(file,'utf8').split(/\r?\n/).filter(Boolean).length:0;}

async function verifyWeek6RendererApproval():Promise<void>{
  const window=mainWindow;if(!window)throw new Error('Week 6 Renderer window was not created');
  const message='__week6_tool__|workspace.write_text|write|{"rootId":"root_default","relativePath":"renderer-approved.txt","content":"approved","overwrite":false}';
  const result=await window.webContents.executeJavaScript(`(async()=>{
    const wait=async(check,label)=>{const deadline=Date.now()+20000;while(Date.now()<deadline){if(check())return;await new Promise(resolve=>setTimeout(resolve,50));}throw new Error('Renderer wait failed: '+label);};
    const button=text=>[...document.querySelectorAll('button')].find(item=>item.textContent?.trim()===text);
    await wait(()=>document.querySelector('.host-ready'),'host ready');button('新建会话')?.click();
    const input=document.querySelector('textarea');Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(input,${JSON.stringify(message)});input.dispatchEvent(new Event('input',{bubbles:true}));input.closest('form').requestSubmit();
    await wait(()=>document.querySelector('.approval-card'),'approval card');const card=document.querySelector('.approval-card');
    if(!card.textContent.includes('WRITE · high')||!card.textContent.includes('renderer-approved.txt')||!button('拒绝')||!button('允许一次')||!button('本次应用运行期间，在当前会话允许'))throw new Error('Approval Card content or choices are incomplete');
    if(document.activeElement?.closest?.('.approval-actions'))throw new Error('Approval action received dangerous default focus');
    button('允许一次').click();await wait(()=>card.textContent.includes('已允许一次'),'resolved approval');
    await wait(()=>[...document.querySelectorAll('.tool-entry')].some(item=>item.textContent?.includes('SUCCEEDED')),'tool success');await wait(()=>button('发送')&&!document.querySelector('button.stop'),'turn completion');
    return {card:true,resolved:true,executed:true};
  })()`,true) as {card?:boolean;resolved?:boolean;executed?:boolean};
  if(!result.card||!result.resolved||!result.executed)throw new Error('Week 6 Renderer approval assertions were incomplete');
}

async function verifyArtifactRendererSmoke():Promise<void>{
  const window=mainWindow;if(!window)throw new Error('Artifact Renderer window was not created');
  await writeFile(path.join(activeWorkspacePath,'preview-fixture.md'),'# 输出物预览\n\nMarkdown **渲染成功**。\n','utf8');
  const result=await window.webContents.executeJavaScript(`(async()=>{
    let stage='start';
    try{
    const wait=async(check,label)=>{const deadline=Date.now()+20000;while(Date.now()<deadline){if(check())return;await new Promise(resolve=>setTimeout(resolve,50));}throw new Error('Renderer wait failed: '+label);};
    const button=text=>[...document.querySelectorAll('button')].find(item=>item.textContent?.trim()===text);
    stage='open general settings';
    document.querySelector('button[aria-label="设置"]')?.click();await wait(()=>button('常规'),'general menu');button('常规').click();await wait(()=>document.querySelector('input[aria-label="最大迭代限制"]'),'general settings');
    stage='save max iterations';
    const iteration=document.querySelector('input[aria-label="最大迭代限制"]');const inputSetter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')?.set;if(!inputSetter)throw new Error('input setter unavailable');inputSetter.call(iteration,'12');iteration.dispatchEvent(new Event('input',{bubbles:true}));button('保存').click();await wait(()=>document.body.textContent.includes('下一轮对话开始生效'),'iteration saved');
    stage='submit artifact fixture';
    button('新建会话').click();await wait(()=>document.querySelector('textarea'),'composer');const input=document.querySelector('textarea');Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(input,'__artifact_fixture__');input.dispatchEvent(new Event('input',{bubbles:true}));input.closest('form').requestSubmit();
    stage='open artifact preview';
    await wait(()=>document.querySelector('.artifact-card'),'artifact card');document.querySelector('.artifact-main').click();await wait(()=>document.querySelector('.artifact-preview-panel'),'preview panel');await wait(()=>document.querySelector('.preview-markdown')?.textContent?.includes('渲染成功'),'markdown preview');
    stage='expand artifact preview';
    document.querySelector('button[aria-label="展开面板"]').click();await wait(()=>document.querySelector('.chat-layout.preview-expanded'),'expanded preview');if(!document.querySelector('.sidebar')||!document.querySelector('button[aria-label="退出展开"]'))throw new Error('Expanded preview covered navigation or cannot be restored');document.querySelector('button[aria-label="退出展开"]').click();await wait(()=>!document.querySelector('.chat-layout.preview-expanded'),'restore preview width');
    stage='collapse artifact preview';
    document.querySelector('button[aria-label="收起预览"]').click();await wait(()=>!document.querySelector('.artifact-preview-panel')&&document.querySelector('button[aria-label="展开预览"]'),'preview collapse');document.querySelector('button[aria-label="展开预览"]').click();await wait(()=>document.querySelector('.artifact-preview-panel'),'preview expand');
    stage='open artifact menu';
    document.querySelector('.artifact-actions>button').click();await wait(()=>document.querySelector('.artifact-menu')?.textContent?.includes('默认应用')&&document.querySelector('.artifact-menu')?.textContent?.includes('打开文件夹')&&document.querySelector('.artifact-menu')?.textContent?.includes('另存为'),'artifact menu');
    return {settings:true,card:true,preview:true,expanded:true,collapse:true,menu:true};
    }catch(error){return {error:String(error),stage};}
  })()`,true) as {settings?:boolean;card?:boolean;preview?:boolean;expanded?:boolean;collapse?:boolean;menu?:boolean;error?:string;stage?:string};
  if('error' in result)throw new Error(`Artifact Renderer smoke failed at ${String(result.stage)}: ${String(result.error)}`);
  if(!result.settings||!result.card||!result.preview||!result.expanded||!result.collapse||!result.menu)throw new Error('Artifact Renderer assertions were incomplete');
}

function smokeLog(message: string): void {
  const file = process.env.AGENT_DESKTOP_SMOKE_LOG;
  if (!smokeMode || !file) return;
  try { appendFileSync(file, `${new Date().toISOString()} ${message}\n`, 'utf8'); } catch { /* diagnostics only */ }
}

function exitSmoke(code: number): void {
  // Electron can retain SDK/Chromium handles for several minutes on Windows even
  // after the Host has stopped. Tell the acceptance launcher that graceful cleanup
  // is complete so it can terminate the Windows launcher process deterministically.
  process.stdout.write(`AGENT_DESKTOP_SMOKE_EXIT:${code}\n`);
  app.exit(code);
}

async function waitFor(check: () => Promise<boolean>, timeoutMs: number, checkTimeoutMs = 1_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await withTimeout(check(), checkTimeoutMs, false)) return;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error('Desktop smoke verification timed out');
}

async function withTimeout<T>(promise: Promise<T>, timeoutMs: number, fallback?: T): Promise<T> {
  const hasFallback = arguments.length >= 3;
  return await Promise.race([
    promise,
    new Promise<T>((resolve, reject) => setTimeout(() => {
      if (hasFallback) resolve(fallback as T);
      else reject(new Error('Operation timed out'));
    }, timeoutMs))
  ]);
}
