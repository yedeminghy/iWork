import { contextBridge, ipcRenderer } from 'electron';
import type { AgUiEvent, HostState, ReleaseInfo, RecoveryState, ThreadPage, ThreadSnapshot, ThreadSummary, TurnOperationResult, DiagnosticPreview, ProviderProfileSummary,ProviderCheckResult,McpServerDraft, McpServerSummary, ToolDescriptor, McpDiagnostic, SkillScope, SkillStatus, SkillSummary, SkillDetail, SkillResourceDescriptor, SkillResourceView, SkillRefreshResult, SkillImportManifest, SkillInstallResult, SkillInstallationInfo, ApprovalDecision, ApprovalResolution, FileRootMode, FileRootSummary, DesktopSettingsView, RuntimeLogSettingsUpdate, OutputArtifact, ArtifactPreview, ArtifactLayoutPreview } from './protocol.js';

const api = Object.freeze({
  getHostState: (): Promise<HostState> => ipcRenderer.invoke('host:get-state'),
  getReleaseInfo:():Promise<ReleaseInfo>=>ipcRenderer.invoke('release:get-info'),
  getRecoveryState:():Promise<RecoveryState>=>ipcRenderer.invoke('recovery:get-state'),
  getSettings:():Promise<DesktopSettingsView>=>ipcRenderer.invoke('settings:get'),
  setMaxIterations:(value:number):Promise<DesktopSettingsView>=>ipcRenderer.invoke('settings:set-max-iterations',value),
  selectDefaultWorkspace:():Promise<DesktopSettingsView|{cancelled:true}>=>ipcRenderer.invoke('settings:select-workspace'),
  setRuntimeLogs:(value:RuntimeLogSettingsUpdate):Promise<DesktopSettingsView>=>ipcRenderer.invoke('settings:set-runtime-logs',value),
  selectLogDirectory:():Promise<DesktopSettingsView|{cancelled:true}>=>ipcRenderer.invoke('settings:select-log-directory'),
  openLogDirectory:():Promise<void>=>ipcRenderer.invoke('settings:open-log-directory'),
  previewArtifact:(artifact:OutputArtifact):Promise<ArtifactPreview>=>ipcRenderer.invoke('artifact:preview',artifact),
  generateArtifactLayoutPreview:(artifact:OutputArtifact):Promise<ArtifactLayoutPreview>=>ipcRenderer.invoke('artifact:layout-preview',artifact),
  releaseArtifactPreview:(previewId:string):Promise<void>=>ipcRenderer.invoke('artifact:release-preview',previewId),
  openArtifact:(artifact:OutputArtifact):Promise<void>=>ipcRenderer.invoke('artifact:open-default',artifact),
  showArtifactInFolder:(artifact:OutputArtifact):Promise<void>=>ipcRenderer.invoke('artifact:show-in-folder',artifact),
  saveArtifactAs:(artifact:OutputArtifact):Promise<{saved:boolean}>=>ipcRenderer.invoke('artifact:save-as',artifact),
  startTurn: (input: { threadId?: string; message: string; profileId?:string;explicitSkillIds?:string[] }) => ipcRenderer.invoke('agent:start-turn', input),
  cancelTurn: (turnId: string): Promise<TurnOperationResult> => ipcRenderer.invoke('agent:cancel-turn', turnId),
  steerTurn:(input:{turnId:string;message:string}):Promise<void>=>ipcRenderer.invoke('agent:steer-turn',input),
  retryTurn:(turnId:string):Promise<{threadId:string;turnId:string;message:string}>=>ipcRenderer.invoke('agent:retry-turn',turnId),
  previewDiagnostics:():Promise<DiagnosticPreview>=>ipcRenderer.invoke('diagnostic:preview'),
  exportDiagnostics:():Promise<{exported:boolean;sha256?:string}>=>ipcRenderer.invoke('diagnostic:export'),
  listThreads: (input?: { cursor?: string; limit?: number; status?: 'ACTIVE' | 'ARCHIVED' }): Promise<ThreadPage> => ipcRenderer.invoke('agent:list-threads', input),
  getThreadSnapshot: (threadId: string): Promise<ThreadSnapshot> => ipcRenderer.invoke('agent:get-snapshot', threadId),
  renameThread: (input: { threadId: string; title: string }): Promise<ThreadSummary> => ipcRenderer.invoke('agent:rename-thread', input),
  archiveThread: (threadId: string): Promise<void> => ipcRenderer.invoke('agent:archive-thread', threadId),
  restoreThread: (threadId: string): Promise<ThreadSummary> => ipcRenderer.invoke('agent:restore-thread', threadId),
  listMcpServers: ():Promise<{items:McpServerSummary[]}>=>ipcRenderer.invoke('mcp:list-servers'),
  saveMcpServer:(input:McpServerDraft):Promise<McpServerSummary>=>ipcRenderer.invoke('mcp:save-server',input),
  connectMcpServer:(input:{serverId:string;bearerToken?:string}):Promise<McpServerSummary>=>ipcRenderer.invoke('mcp:connect-server',input),
  disconnectMcpServer:(serverId:string):Promise<void>=>ipcRenderer.invoke('mcp:disconnect-server',serverId),
  deleteMcpServer:(serverId:string):Promise<void>=>ipcRenderer.invoke('mcp:delete-server',serverId),
  listMcpTools:(serverId:string):Promise<ToolDescriptor[]>=>ipcRenderer.invoke('mcp:list-tools',serverId),
  getMcpDiagnostic:(serverId:string):Promise<McpDiagnostic>=>ipcRenderer.invoke('mcp:get-diagnostic',serverId),
  listSkills:(input?:{scope?:SkillScope;status?:SkillStatus}):Promise<{items:SkillSummary[];generation:number}>=>(ipcRenderer.invoke('skill:list',input)),
  getSkill:(skillId:string):Promise<SkillDetail>=>ipcRenderer.invoke('skill:get',skillId),
  listSkillResources:(skillId:string):Promise<SkillResourceDescriptor[]>=>ipcRenderer.invoke('skill:list-resources',skillId),
  readSkillResource:(input:{skillId:string;resourceId:string}):Promise<SkillResourceView>=>ipcRenderer.invoke('skill:read-resource',input),
  refreshSkills:(scope?:SkillScope):Promise<SkillRefreshResult>=>ipcRenderer.invoke('skill:refresh',scope),
  setSkillEnabled:(input:{skillId:string;enabled:boolean}):Promise<SkillSummary>=>ipcRenderer.invoke('skill:set-enabled',input),
  selectAndInspectLocalSkill:():Promise<SkillImportManifest|{cancelled:true}>=>ipcRenderer.invoke('skill-import:select-local'),
  inspectGitHubSkill:(input:{repositoryUrl:string;ref?:string;subdirectory?:string}):Promise<SkillImportManifest>=>ipcRenderer.invoke('skill-import:inspect-github',input),
  commitSkillImport:(input:{sessionId:string;action:'INSTALL'|'REPLACE'|'NO_OP';expectedExistingContentHash?:string}):Promise<SkillInstallResult>=>ipcRenderer.invoke('skill-import:commit',input),
  cancelSkillImport:(sessionId:string):Promise<void>=>ipcRenderer.invoke('skill-import:cancel',sessionId),
  removeInstalledSkill:(skillId:string):Promise<SkillInstallResult>=>ipcRenderer.invoke('skill-import:remove',skillId),
  rollbackInstalledSkill:(skillId:string):Promise<SkillInstallResult>=>ipcRenderer.invoke('skill-import:rollback',skillId),
  getSkillInstallation:(skillId:string):Promise<SkillInstallationInfo>=>ipcRenderer.invoke('skill-import:get-installation',skillId),
  cancelActiveSkillInspection:():Promise<void>=>ipcRenderer.invoke('skill-import:cancel-active'),
  resolveApproval:(input:{approvalId:string;decision:ApprovalDecision;expectedRequestFingerprint:string}):Promise<ApprovalResolution>=>ipcRenderer.invoke('approval:resolve',input),
  listFileRoots:(workspaceId:string):Promise<FileRootSummary[]>=>ipcRenderer.invoke('file-root:list',workspaceId),
  selectAndAddFileRoot:(input:{workspaceId:string;mode:FileRootMode}):Promise<FileRootSummary|{cancelled:true}>=>ipcRenderer.invoke('file-root:select-add',input),
  updateFileRoot:(input:{rootId:string;mode:FileRootMode;enabled:boolean;expectedVersion:number}):Promise<FileRootSummary>=>ipcRenderer.invoke('file-root:update',input),
  removeFileRoot:(input:{rootId:string;expectedVersion:number}):Promise<void>=>ipcRenderer.invoke('file-root:remove',input),
  saveProviderKey: (input: { providerId: 'deepseek' | 'qwen'; apiKey: string }): Promise<void> =>
    ipcRenderer.invoke('provider:save-key', input),
  deleteProviderKey:(providerId:'deepseek'|'qwen'):Promise<void>=>ipcRenderer.invoke('provider:delete-key',providerId),
  listProviderProfiles:():Promise<ProviderProfileSummary[]>=>ipcRenderer.invoke('provider:list-profiles'),
  checkProvider:(profileId:string):Promise<ProviderCheckResult>=>ipcRenderer.invoke('provider:check',profileId),
  selectThreadProfile:(input:{threadId:string;profileId:string}):Promise<ThreadSummary>=>ipcRenderer.invoke('provider:select-thread-profile',input),
  onHostState: (listener: (state: HostState) => void): (() => void) => {
    const wrapped = (_event: Electron.IpcRendererEvent, value: HostState) => listener(value);
    ipcRenderer.on('host:state', wrapped);
    return () => ipcRenderer.removeListener('host:state', wrapped);
  },
  onAgentEvent: (listener: (event: AgUiEvent) => void): (() => void) => {
    const wrapped = (_event: Electron.IpcRendererEvent, value: AgUiEvent) => listener(value);
    ipcRenderer.on('agent:event', wrapped);
    return () => ipcRenderer.removeListener('agent:event', wrapped);
  }
});

contextBridge.exposeInMainWorld('agentDesktop', api);
