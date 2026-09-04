import { type CSSProperties, FormEvent, useEffect, useLayoutEffect, useReducer, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import McpSettings from './McpSettings';
import SkillsSettings from './SkillsSettings';
import ProviderSettings from './ProviderSettings';
import GeneralSettings from './GeneralSettings';
import OutputArtifacts from './OutputArtifacts';
import ArtifactPreviewPanel from './ArtifactPreviewPanel';

type Message = { id: string; order: number; role: 'user' | 'assistant'; content: string; status: 'streaming' | 'complete' | 'cancelled' };
type Tool = { id: string; order: number; toolName: string; serverId?:string; argumentsSummary: string; resultSummary?: string; status: string; elapsedMs?:number; errorCode?:string;artifacts:OutputArtifact[] };
type SkillActivation={id:string;order:number;name:string;scope:string;reason:string};
type Approval={id:string;turnId?:string;order:number;toolCallId:string;capability:string;risk:string;title:string;summary:string;warning?:string;requestFingerprint:string;expiresAt:string;decision?:string;source:Record<string,unknown>;target:Record<string,unknown>};
type VisibleEntry =
  | {kind:'message';order:number;item:Message}
  | {kind:'tool';order:number;item:Tool}
  | {kind:'activation';order:number;item:SkillActivation}
  | {kind:'approval';order:number;item:Approval};
type ConversationGroup={id:string;entries:VisibleEntry[]};
type State = {
  messages: Message[]; tools: Tool[]; activations:SkillActivation[];approvals:Approval[]; threadId?: string; activeTurnId?: string; activeTurnStatus?: string;lastTurnId?:string;
  threadStatus?: 'ACTIVE' | 'ARCHIVED';modelProfileId?:string; error?: string; lastSequenceByTurn: Record<string, number>; buffered: Record<string, AgUiEvent[]>; loadingThreadId?: string; needsSyncThreadId?: string;
};
type Action =
  | { type: 'submitted'; content: string } | { type: 'started'; threadId: string; turnId: string }
  | { type: 'event'; event: AgUiEvent } | { type: 'error'; message: string } | { type: 'new-thread' }
  | { type: 'snapshot-requested'; threadId: string } | { type: 'hydrated'; snapshot: ThreadSnapshot }
  | { type: 'cancel-requested' };

const initialState: State = { messages: [], tools: [], activations:[],approvals:[], lastSequenceByTurn: {}, buffered: {} };
const activeStatuses = new Set(['QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'CANCELLING']);
const PREVIEW_MIN_WIDTH = 280;
const PREVIEW_MAX_WIDTH = 900;
const CHAT_MIN_WIDTH = 320;
const PREVIEW_RESIZER_WIDTH = 8;

function timeline(snapshot: ThreadSnapshot): { messages: Message[]; tools: Tool[]; activations:SkillActivation[];approvals:Approval[] } {
  const messages: Message[] = []; const tools: Tool[] = [];const activations:SkillActivation[]=[];const approvals:Approval[]=[];
  snapshot.timeline.forEach((item, order) => {
    if (item.kind === 'USER_MESSAGE') messages.push({ id: item.id, order, role: 'user', content: String(item.value.content ?? ''), status: 'complete' });
    if (item.kind === 'ASSISTANT_MESSAGE') messages.push({ id: item.id, order, role: 'assistant', content: String(item.value.content ?? ''), status: item.value.status === 'STREAMING' ? 'streaming' : 'complete' });
    if (item.kind === 'TOOL_CALL') tools.push({ id: item.id, order, toolName: String(item.value.toolName ?? 'tool'),serverId:String(item.value.serverId??''), argumentsSummary: String(item.value.argumentsSummary ?? ''), resultSummary: item.value.resultSummary === undefined ? undefined : String(item.value.resultSummary), status: String(item.value.status ?? 'RUNNING'),elapsedMs:item.value.elapsedMs===undefined?undefined:Number(item.value.elapsedMs),errorCode:item.value.errorCode===undefined?undefined:String(item.value.errorCode),artifacts:artifactsFrom(item.value.artifacts) });
    if(item.kind==='SKILL_ACTIVATION')activations.push({id:item.id,order,name:String(item.value.name??'Skill'),scope:String(item.value.scope??''),reason:String(item.value.reason??'')});
    if(item.kind==='APPROVAL')approvals.push(approvalFrom(item.id,order,item.value,item.turnId??undefined));
  });
  let reconciledTools=tools;
  for(const approval of approvals)reconciledTools=reconcileApprovalTool(reconciledTools,approval.toolCallId,approval.decision);
  return { messages, tools:reconciledTools,activations,approvals };
}

function approvalFrom(id:string,order:number,value:Record<string,unknown>,turnId?:string):Approval{return {id,turnId,order,toolCallId:String(value.toolCallId??''),capability:String(value.capability??'UNKNOWN'),risk:String(value.risk??'critical'),title:String(value.title??'需要审批'),summary:String(value.summary??''),warning:value.warning===undefined?undefined:String(value.warning),requestFingerprint:String(value.requestFingerprint??''),expiresAt:String(value.expiresAt??''),decision:value.decision===undefined?undefined:String(value.decision),source:(value.source&&typeof value.source==='object'?value.source:{}) as Record<string,unknown>,target:(value.target&&typeof value.target==='object'?value.target:{}) as Record<string,unknown>};}

function reconcileApprovalTool(tools:Tool[],toolCallId:string,decision?:string):Tool[]{
  if(!toolCallId)return tools;
  const open=new Set(['RUNNING','WAITING_APPROVAL']);
  return tools.map(tool=>{
    if(tool.id!==toolCallId||!open.has(tool.status))return tool;
    if(!decision)return {...tool,status:'WAITING_APPROVAL'};
    if(decision==='ALLOW_ONCE'||decision==='ALLOW_THREAD')return {...tool,status:'RUNNING',errorCode:undefined};
    const errorCode=decision==='EXPIRED'?'APPROVAL_EXPIRED':decision==='DENY'?'TOOL_PERMISSION_DENIED':decision==='INVALIDATED'?'APPROVAL_INVALIDATED':'TOOL_APPROVAL_CANCELLED';
    return {...tool,status:'FAILED',errorCode};
  });
}

function settleOpenTools(tools:Tool[],status:string,errorCode:string):Tool[]{
  return tools.map(tool=>tool.status==='RUNNING'||tool.status==='WAITING_APPROVAL'?{...tool,status,errorCode}:tool);
}

function nextOrder(state: State): number {
  return Math.max(-1, ...state.messages.map(item => item.order), ...state.tools.map(item => item.order),...state.activations.map(item=>item.order),...state.approvals.map(item=>item.order)) + 1;
}

function groupTimeline(entries:VisibleEntry[]):ConversationGroup[]{
  const groups:ConversationGroup[]=[];
  let current:ConversationGroup|undefined;
  for(const entry of entries){
    if(entry.kind==='message'&&entry.item.role==='user'){
      current={id:`turn:${entry.item.id}`,entries:[]};
      groups.push(current);
    }
    if(!current){current={id:'turn:initial',entries:[]};groups.push(current);}
    current.entries.push(entry);
  }
  return groups;
}

function artifactsFrom(value:unknown):OutputArtifact[]{
  if(!Array.isArray(value))return [];
  return value.flatMap(item=>{if(!item||typeof item!=='object')return [];const artifact=item as Record<string,unknown>;
    if(artifact.rootId!=='root_default'||typeof artifact.relativePath!=='string'||typeof artifact.fileName!=='string'||typeof artifact.mediaType!=='string'||typeof artifact.byteSize!=='number')return [];
    return [{rootId:'root_default',relativePath:artifact.relativePath,fileName:artifact.fileName,mediaType:artifact.mediaType,byteSize:artifact.byteSize} satisfies OutputArtifact];});
}
function uniqueArtifacts(entries:VisibleEntry[]):OutputArtifact[]{const values=new Map<string,OutputArtifact>();for(const entry of entries)if(entry.kind==='tool')for(const artifact of entry.item.artifacts)values.set(`${artifact.rootId}:${artifact.relativePath}`,artifact);return [...values.values()];}

function applyEvent(state: State, event: AgUiEvent): State {
  const last = state.lastSequenceByTurn[event.turnId] ?? 0;
  if (event.sequence <= last) return state;
  if (event.sequence > last + 1) return { ...state, needsSyncThreadId: event.threadId };
  const next = { ...state, lastSequenceByTurn: { ...state.lastSequenceByTurn, [event.turnId]: event.sequence } };
  if (event.type === 'TEXT_MESSAGE_START') return { ...next, activeTurnId: event.turnId, activeTurnStatus: 'RUNNING', messages: [...next.messages, { id: String(event.value.messageId), order: nextOrder(next), role: 'assistant', content: '', status: 'streaming' }] };
  if (event.type === 'TEXT_MESSAGE_CONTENT') return { ...next, messages: next.messages.map(message => message.id === String(event.value.messageId) ? { ...message, content: message.content + String(event.value.delta ?? '') } : message) };
  if (event.type === 'TEXT_MESSAGE_END') return { ...next, messages: next.messages.map(message => message.id === String(event.value.messageId) ? { ...message, status: 'complete' } : message) };
  if (event.type === 'TOOL_CALL_START') return { ...next, tools: [...next.tools, { id: String(event.value.toolCallId), order: nextOrder(next), toolName: String(event.value.toolName ?? 'tool'),serverId:String(event.value.serverId??''), argumentsSummary: '', status: 'RUNNING',artifacts:[] }] };
  if (event.type === 'TOOL_CALL_ARGS') return { ...next, tools: next.tools.map(tool => tool.id === String(event.value.toolCallId) ? { ...tool, argumentsSummary: tool.argumentsSummary + String(event.value.delta ?? '') } : tool) };
  if (event.type === 'TOOL_CALL_RESULT') return { ...next, tools: next.tools.map(tool => tool.id === String(event.value.toolCallId) ? { ...tool, resultSummary: String(event.value.resultSummary ?? ''), status: String(event.value.status ?? 'SUCCEEDED'),elapsedMs:Number(event.value.elapsedMs??0),errorCode:event.value.errorCode===undefined?undefined:String(event.value.errorCode),artifacts:artifactsFrom(event.value.artifacts) } : tool) };
  if(event.type==='CUSTOM skill.activated')return {...next,activations:[...next.activations,{id:`${event.turnId}:${event.value.skillId}`,order:nextOrder(next),name:String(event.value.name??'Skill'),scope:String(event.value.scope??''),reason:String(event.value.reason??'')}]};
  if(event.type==='CUSTOM approval.required')return {...next,activeTurnStatus:'WAITING_APPROVAL',tools:reconcileApprovalTool(next.tools,String(event.value.toolCallId)),approvals:[...next.approvals,approvalFrom(String(event.value.approvalId),nextOrder(next),event.value,event.turnId)]};
  if(event.type==='CUSTOM approval.resolved'){
    const decision=String(event.value.decision);
    return {...next,activeTurnStatus:'RUNNING',tools:reconcileApprovalTool(next.tools,String(event.value.toolCallId),decision),approvals:next.approvals.map(item=>item.id===String(event.value.approvalId)?{...item,decision}:item)};
  }
  if (event.type === 'RUN_FINISHED') {
    const cancelled = event.value.outcome === 'cancelled';
    return { ...next, activeTurnId: undefined,lastTurnId:event.turnId, activeTurnStatus: cancelled ? 'CANCELLED' : 'COMPLETED',tools:settleOpenTools(next.tools,cancelled?'CANCELLED':'FAILED',cancelled?'TOOL_CANCELLED':'TOOL_RESULT_MISSING'), messages: next.messages.map(message => message.role === 'assistant' && message.status === 'streaming' ? { ...message, status: cancelled ? 'cancelled' : 'complete' } : message) };
  }
  if (event.type === 'RUN_ERROR') return { ...next, activeTurnId: undefined,lastTurnId:event.turnId, activeTurnStatus: 'FAILED',tools:settleOpenTools(next.tools,'FAILED',String(event.value.code??'TOOL_EXECUTION_FAILED')), error: String(event.value.message ?? '运行失败') };
  return next;
}

function reducer(state: State, action: Action): State {
  if (action.type === 'new-thread') return { ...initialState };
  if (action.type === 'snapshot-requested') return { ...state, loadingThreadId: action.threadId, error: undefined };
  if (action.type === 'hydrated') {
    const projected = timeline(action.snapshot); const turns=[...action.snapshot.turns].reverse();const active = turns.find(turn => activeStatuses.has(turn.status));const last=turns[0];
    let next: State = { ...state, ...projected, threadId: action.snapshot.thread.id, threadStatus: action.snapshot.thread.status,modelProfileId:action.snapshot.thread.modelProfileId, activeTurnId: active?.id,lastTurnId:last?.id, activeTurnStatus: active?.status??last?.status,
      loadingThreadId: undefined, needsSyncThreadId: undefined, lastSequenceByTurn: Object.fromEntries(action.snapshot.turns.map(turn => [turn.id, turn.lastSequence])) };
    const events = next.buffered[action.snapshot.thread.id] ?? [];
    next = { ...next, buffered: { ...next.buffered, [action.snapshot.thread.id]: [] } };
    return events.sort((a, b) => a.sequence - b.sequence).reduce(applyEvent, next);
  }
  if (action.type === 'submitted') return { ...state, error: undefined, messages: [...state.messages, { id: crypto.randomUUID(), order: nextOrder(state), role: 'user', content: action.content, status: 'complete' }] };
  if (action.type === 'started') {
    let next: State = { ...state, threadId: action.threadId, activeTurnId: action.turnId,lastTurnId:action.turnId, activeTurnStatus: 'RUNNING' };
    const events = next.buffered[action.threadId] ?? [];
    next = { ...next, buffered: { ...next.buffered, [action.threadId]: [] } };
    return events.sort((a, b) => a.sequence - b.sequence).reduce(applyEvent, next);
  }
  if (action.type === 'cancel-requested') return { ...state, activeTurnStatus: 'CANCELLING' };
  if (action.type === 'error') return { ...state, error: action.message };
  if (state.loadingThreadId === action.event.threadId || state.threadId !== action.event.threadId) return { ...state, buffered: { ...state.buffered, [action.event.threadId]: [...(state.buffered[action.event.threadId] ?? []), action.event] } };
  return applyEvent(state, action.event);
}

export default function App() {
  const [state, dispatch] = useReducer(reducer, initialState);
  const [input, setInput] = useState(''); const [host, setHost] = useState<HostState>({ status: 'starting' }); const [threads, setThreads] = useState<ThreadSummary[]>([]); const [showArchived, setShowArchived] = useState(false); const [renamingThreadId,setRenamingThreadId]=useState<string>();const [renameTitle,setRenameTitle]=useState(''); const [page,setPage]=useState<'chat'|'general'|'providers'|'mcp'|'skills'|'about'>('chat');
  const [sidebarCollapsed,setSidebarCollapsed]=useState(false);const [conversationListExpanded,setConversationListExpanded]=useState(true);const [searchOpen,setSearchOpen]=useState(false);const [searchQuery,setSearchQuery]=useState('');const [openThreadMenuId,setOpenThreadMenuId]=useState<string>();const [settingsOpen,setSettingsOpen]=useState(false);
  const [selectedProfileId,setSelectedProfileId]=useState(()=>localStorage.getItem('agentDesktop.selectedProfileId')??'deepseek-v4-pro');
  const [release,setRelease]=useState<ReleaseInfo>();const [recovery,setRecovery]=useState<RecoveryState>();
  const [availableSkills,setAvailableSkills]=useState<SkillSummary[]>([]);const [selectedSkillIds,setSelectedSkillIds]=useState<string[]>([]);
  const [approvalMode,setApprovalMode]=useState<'ASK'|'AUTO'>(()=>localStorage.getItem('agentDesktop.approvalMode')==='AUTO'?'AUTO':'ASK');
  const [autoApprovalTurnId,setAutoApprovalTurnId]=useState<string>();const autoApproving=useRef(new Set<string>());
  const [selectedArtifact,setSelectedArtifact]=useState<OutputArtifact>();const [previewCollapsed,setPreviewCollapsed]=useState(false);const [previewExpanded,setPreviewExpanded]=useState(false);
  const [previewWidth,setPreviewWidth]=useState(()=>{const stored=Number(localStorage.getItem('agentDesktop.previewWidth'));return Number.isFinite(stored)?Math.min(PREVIEW_MAX_WIDTH,Math.max(PREVIEW_MIN_WIDTH,stored)):420;});
  const previewWidthRef=useRef(previewWidth);const timelineRef=useRef<HTMLDivElement>(null);const timelineFollowsLatest=useRef(true);

  function updatePreviewWidth(width:number){const next=Math.round(Math.min(PREVIEW_MAX_WIDTH,Math.max(PREVIEW_MIN_WIDTH,width)));previewWidthRef.current=next;setPreviewWidth(next);}
  function resizePreview(clientX:number,handle:HTMLDivElement){const layout=handle.parentElement;if(!layout)return;const bounds=layout.getBoundingClientRect();const maxWidth=Math.max(PREVIEW_MIN_WIDTH,Math.min(PREVIEW_MAX_WIDTH,bounds.width-CHAT_MIN_WIDTH-PREVIEW_RESIZER_WIDTH));updatePreviewWidth(Math.min(maxWidth,bounds.right-clientX));}

  async function refreshThreads(): Promise<ThreadSummary[]> {
    const [active,archived]=await Promise.all([window.agentDesktop.listThreads({status:'ACTIVE'}),window.agentDesktop.listThreads({status:'ARCHIVED'})]);
    const items=[...active.items,...archived.items].sort((left,right)=>Date.parse(right.updatedAt)-Date.parse(left.updatedAt));
    setThreads(items);return items;
  }
  async function openThread(threadId: string): Promise<void> {
    timelineFollowsLatest.current=true;
    setSelectedArtifact(undefined);
    setPreviewExpanded(false);
    dispatch({ type: 'snapshot-requested', threadId });
    try { const snapshot = await window.agentDesktop.getThreadSnapshot(threadId); localStorage.setItem('agentDesktop.selectedThreadId', threadId); dispatch({ type: 'hydrated', snapshot }); }
    catch { dispatch({ type: 'error', message: '无法打开会话。' }); }
  }

  useEffect(() => {
    void window.agentDesktop.getHostState().then(setHost).catch(() => setHost({ status: 'failed' }));
    void window.agentDesktop.getReleaseInfo().then(setRelease).catch(()=>undefined);
    void window.agentDesktop.getRecoveryState().then(setRecovery).catch(()=>undefined);
    const removeState = window.agentDesktop.onHostState(setHost); const removeEvent = window.agentDesktop.onAgentEvent(event => {dispatch({ type: 'event', event });if(event.type==='RUN_FINISHED'||event.type==='RUN_ERROR'){setAutoApprovalTurnId(undefined);void refreshThreads();}});
    const refreshHostState = window.setInterval(() => {
      void window.agentDesktop.getHostState().then(setHost).catch(() => undefined);
    }, 250);
    return () => { window.clearInterval(refreshHostState); removeState(); removeEvent(); };
  }, []);
  useEffect(() => { if (host.status !== 'ready') return; void refreshThreads().then(items => {
    const preferred = localStorage.getItem('agentDesktop.selectedThreadId');
    const candidates=items.filter(thread=>thread.status===(showArchived?'ARCHIVED':'ACTIVE'));
    const selected = candidates.find(thread => thread.id === preferred) ?? candidates[0]; if (selected) void openThread(selected.id);
  }).catch(() => dispatch({ type: 'error', message: '无法恢复本地会话。' })); }, [host.status, showArchived]);
  useEffect(()=>{if(host.status==='ready')void window.agentDesktop.getRecoveryState().then(setRecovery).catch(()=>undefined);},[host.status]);
  useEffect(() => { if (state.needsSyncThreadId) void openThread(state.needsSyncThreadId); }, [state.needsSyncThreadId]);
  useEffect(()=>{if(host.status==='ready')void window.agentDesktop.listSkills({status:'VALID'}).then(page=>setAvailableSkills(page.items.filter(item=>item.enabled))).catch(()=>setAvailableSkills([]));},[host.status,page]);
  useEffect(()=>{
    if(!autoApprovalTurnId)return;const approval=state.approvals.find(item=>item.turnId===autoApprovalTurnId&&!item.decision&&!autoApproving.current.has(item.id));if(!approval)return;
    autoApproving.current.add(approval.id);void window.agentDesktop.resolveApproval({approvalId:approval.id,decision:'ALLOW_ONCE',expectedRequestFingerprint:approval.requestFingerprint})
      .then(()=>state.threadId?openThread(state.threadId):undefined).catch(()=>state.threadId?openThread(state.threadId):undefined).finally(()=>autoApproving.current.delete(approval.id));
  },[autoApprovalTurnId,state.approvals,state.threadId]);

  async function submit(event: FormEvent) {
    event.preventDefault(); const message = input.trim(); if (!message || host.status !== 'ready') return;
    timelineFollowsLatest.current=true;
    if(state.activeTurnId){
      if(new TextEncoder().encode(message).length>4096){dispatch({type:'error',message:'追加指令不能超过 4 KiB。'});return;}
      try{await window.agentDesktop.steerTurn({turnId:state.activeTurnId,message});setInput('');dispatch({type:'submitted',content:message});}
      catch{dispatch({type:'error',message:'追加指令失败；当前运行可能已结束或已达到数量限制。'});}return;
    }
    const explicitSkillIds=[...selectedSkillIds];setInput('');setSelectedSkillIds([]); dispatch({ type: 'submitted', content: message });
    try { const started = await window.agentDesktop.startTurn({ threadId: state.threadId, message,profileId:selectedProfileId,explicitSkillIds }); localStorage.setItem('agentDesktop.selectedThreadId', started.threadId);setAutoApprovalTurnId(approvalMode==='AUTO'?started.turnId:undefined);dispatch({ type: 'started', ...started }); await refreshThreads(); }
    catch { dispatch({ type: 'error', message: '无法启动本地 Agent，请检查 Host 状态。' }); }
  }
  async function cancel() { if (!state.activeTurnId) return; dispatch({ type: 'cancel-requested' }); try { await window.agentDesktop.cancelTurn(state.activeTurnId); } catch { dispatch({ type: 'error', message: '取消请求失败。' }); } }
  async function retry(){if(!state.lastTurnId||!['FAILED','CANCELLED','INTERRUPTED'].includes(state.activeTurnStatus??''))return;try{const started=await window.agentDesktop.retryTurn(state.lastTurnId);dispatch({type:'submitted',content:started.message});dispatch({type:'started',threadId:started.threadId,turnId:started.turnId});}catch{dispatch({type:'error',message:'该轮次不可重试，或 Provider 凭据不可用。'});}}
  async function rename(threadId:string) {
    if (!renameTitle.trim()) return;
    try {
      await window.agentDesktop.renameThread({ threadId, title: renameTitle.trim() });
      setRenamingThreadId(undefined);setRenameTitle('');
      await refreshThreads();
    } catch { dispatch({ type: 'error', message: '重命名失败。' }); }
  }
  async function archive(threadId:string) { if (!window.confirm('归档该会话？')) return; try { await window.agentDesktop.archiveThread(threadId); if(state.threadId===threadId){localStorage.removeItem('agentDesktop.selectedThreadId');dispatch({type:'new-thread'});}setOpenThreadMenuId(undefined);await refreshThreads(); } catch { dispatch({ type: 'error', message: '会话正在运行，请先停止。' }); } }
  async function restore(threadId:string) { try { await window.agentDesktop.restoreThread(threadId); setShowArchived(false);setOpenThreadMenuId(undefined);await refreshThreads(); } catch { dispatch({ type: 'error', message: '恢复会话失败。' }); } }

  const visibleTimeline:VisibleEntry[] = [
    ...state.messages.map(message => ({ kind: 'message' as const, order: message.order, item: message })),
    ...state.tools.map(tool => ({ kind: 'tool' as const, order: tool.order, item: tool })),
    ...state.activations.map(activation=>({kind:'activation' as const,order:activation.order,item:activation})),
    ...state.approvals.map(approval=>({kind:'approval' as const,order:approval.order,item:approval}))
  ].sort((left, right) => left.order - right.order);
  const conversationGroups=groupTimeline(visibleTimeline);
  const latestMessage=state.messages.at(-1);const latestTool=state.tools.at(-1);const latestApproval=state.approvals.at(-1);
  const timelineVersion=`${state.threadId??''}:${state.messages.length}:${latestMessage?.content.length??0}:${latestMessage?.status??''}:${state.tools.length}:${latestTool?.argumentsSummary.length??0}:${latestTool?.resultSummary?.length??0}:${latestTool?.status??''}:${state.activations.length}:${state.approvals.length}:${latestApproval?.decision??''}`;
  useLayoutEffect(()=>{const node=timelineRef.current;if(node&&timelineFollowsLatest.current)node.scrollTop=node.scrollHeight;},[timelineVersion]);
  const normalizedSearch=searchQuery.trim().toLocaleLowerCase();
  const visibleThreads=threads.filter(thread=>(searchOpen&&normalizedSearch
    ? `${thread.title} ${thread.preview??''}`.toLocaleLowerCase().includes(normalizedSearch)
    : thread.status===(showArchived?'ARCHIVED':'ACTIVE')));

  return <main className={`app-shell ${sidebarCollapsed?'sidebar-is-collapsed':''}`}><aside className="sidebar"><div className="sidebar-header"><div className="brand"><span className="brand-mark">i</span><span className="brand-name">iWork</span></div><div className="sidebar-header-actions"><button className="icon-button" title="搜索历史会话" aria-label="搜索历史会话" onClick={()=>{setSearchOpen(value=>!value);setSettingsOpen(false);if(sidebarCollapsed)setSidebarCollapsed(false);}}><Icon name="search"/></button><button className="icon-button" title={sidebarCollapsed?'展开侧边栏':'收起侧边栏'} aria-label={sidebarCollapsed?'展开侧边栏':'收起侧边栏'} onClick={()=>setSidebarCollapsed(value=>!value)}><Icon name={sidebarCollapsed?'expand':'collapse'}/></button></div></div>
    {searchOpen&&!sidebarCollapsed&&<div className="thread-search"><Icon name="search"/><input autoFocus aria-label="搜索历史会话" placeholder="搜索历史会话" value={searchQuery} onChange={event=>setSearchQuery(event.target.value)}/>{searchQuery&&<button aria-label="清空搜索" title="清空搜索" onClick={()=>setSearchQuery('')}>×</button>}</div>}
    <button className="new-chat" title="新建会话" onClick={() => { setPage('chat');setSelectedArtifact(undefined);setPreviewExpanded(false);localStorage.removeItem('agentDesktop.selectedThreadId'); setShowArchived(false);setOpenThreadMenuId(undefined); dispatch({ type: 'new-thread' }); }}><Icon name="plus"/><span>新建会话</span></button>
    <nav className="primary-nav" aria-label="功能导航"><button className={page==='skills'?'selected':''} title="技能广场" onClick={()=>setPage('skills')}><Icon name="skills"/><span>技能广场</span></button><button className={page==='mcp'?'selected':''} title="MCP服务" onClick={()=>setPage('mcp')}><Icon name="mcp"/><span>MCP服务</span></button><button className={page==='providers'?'selected':''} title="模型配置" onClick={()=>setPage('providers')}><Icon name="model"/><span>模型配置</span></button></nav>
    <section className="conversation-section"><button className="conversation-heading" aria-expanded={conversationListExpanded} onClick={()=>setConversationListExpanded(value=>!value)}><Icon name={conversationListExpanded?'chevronDown':'chevronRight'}/><span>{searchOpen&&normalizedSearch?'搜索结果':showArchived?'归档会话':'会话列表'}</span><small>{visibleThreads.length}</small></button>
      {conversationListExpanded&&<nav className="thread-list" aria-label="历史会话">{visibleThreads.map(thread => <div key={thread.id} className={`thread-row-wrap ${page==='chat'&&state.threadId===thread.id?'selected':''}`}>{renamingThreadId===thread.id?<form className="thread-rename-form" onSubmit={event=>{event.preventDefault();void rename(thread.id);}}><input autoFocus aria-label="会话名称" maxLength={120} value={renameTitle} onChange={event=>setRenameTitle(event.target.value)}/><button type="submit" title="保存">✓</button><button type="button" title="取消" onClick={()=>setRenamingThreadId(undefined)}>×</button></form>:<><button className="thread-row" onClick={() => {setPage('chat');setOpenThreadMenuId(undefined);void openThread(thread.id);}}><span>{thread.title}</span><small>{thread.preview||'暂无摘要'}</small>{searchOpen&&normalizedSearch&&<em>{thread.status==='ARCHIVED'?'已归档':'进行中'}</em>}</button><button className="thread-more" title="会话操作" aria-label={`${thread.title} 会话操作`} onClick={()=>setOpenThreadMenuId(value=>value===thread.id?undefined:thread.id)}><Icon name="more"/></button>{openThreadMenuId===thread.id&&<div className="thread-menu"><button onClick={()=>{setRenamingThreadId(thread.id);setRenameTitle(thread.title);setOpenThreadMenuId(undefined);}}><Icon name="rename"/>重命名</button>{thread.status==='ARCHIVED'?<button onClick={()=>void restore(thread.id)}><Icon name="restore"/>恢复</button>:<button onClick={()=>void archive(thread.id)}><Icon name="archive"/>归档</button>}</div>}</>}</div>)}</nav>}
    </section><div className="sidebar-spacer"/><div className="sidebar-footer"><div className={`host-state host-${host.status}`} title={`Host：${host.status}`}><span/><b>Host：{host.status}</b></div><div className="settings-wrap"><button className="settings-button" title="设置" aria-label="设置" aria-expanded={settingsOpen} onClick={()=>setSettingsOpen(value=>!value)}><Icon name="settings"/></button>{settingsOpen&&<div className="settings-menu"><button onClick={()=>{setPage('general');setSettingsOpen(false);}}><Icon name="settings"/>常规</button><button onClick={()=>{setPage('chat');setShowArchived(true);setSearchOpen(false);setSettingsOpen(false);setConversationListExpanded(true);}}><Icon name="archive"/>查看归档会话</button><button onClick={()=>{setPage('about');setSettingsOpen(false);}}><Icon name="about"/>关于版本</button></div>}</div></div></aside>
    {page==='general'?<section className="workspace"><header><div><h1>常规</h1><p>工作区、运行限制与本地隐私设置</p></div></header><GeneralSettings/></section>:page==='providers'?<section className="workspace"><header><div><h1>模型配置</h1><p>受信 Catalog · safeStorage 密钥 · 一次性 Host 会话</p></div></header><ProviderSettings hostReady={host.status==='ready'} currentThreadId={state.threadId} locked={state.activeTurnId!==undefined} selectedProfileId={state.modelProfileId??selectedProfileId} onSelected={profileId=>{setSelectedProfileId(profileId);if(state.threadId)void openThread(state.threadId);}}/></section>:page==='mcp'?<section className="workspace"><header><div><h1>MCP服务</h1><p>管理 STDIO 与 Streamable HTTP Server</p></div></header><McpSettings hostReady={host.status==='ready'}/></section>:page==='skills'?<section className="workspace"><header><div><h1>技能广场</h1><p>发现、诊断与渐进加载本地 Skill</p></div></header><SkillsSettings hostReady={host.status==='ready'}/></section>:page==='about'?<About release={release} recovery={recovery} host={host}/>:<div className={`chat-layout ${selectedArtifact&&!previewCollapsed?(previewExpanded?'preview-open preview-expanded':'preview-open'):''}`} style={{'--preview-width':`${previewWidth}px`} as CSSProperties}><section className="workspace"><header><div><h1>{state.threadId ? '当前会话' : '开始新会话'}</h1><p>本地优先 · {state.modelProfileId??selectedProfileId}{state.activeTurnStatus ? ` · ${state.activeTurnStatus}` : ''}</p></div><div className="workspace-header-actions">{state.threadId&&['FAILED','CANCELLED','INTERRUPTED'].includes(state.activeTurnStatus??'')&&<div className="thread-actions"><button onClick={()=>void retry()}>重试本轮</button></div>}{selectedArtifact&&previewCollapsed&&<button className="preview-toggle-button" title="展开预览" aria-label="展开预览" onClick={()=>setPreviewCollapsed(false)}><Icon name="expand"/></button>}</div></header>
      {host.status==='restarting'&&<div className="recovery-banner">Host 正在安全恢复。已保存内容会重新加载，未完成操作不会自动继续。</div>}
      {host.status==='crash-loop'&&<div className="recovery-banner error">Host 重复异常，已停止自动重启。{host.diagnosticCode&&<code>{host.diagnosticCode}</code>}{host.diagnosticId&&<button onClick={()=>void navigator.clipboard.writeText(host.diagnosticId??'')}>复制诊断编号</button>}</div>}
      {recovery?.recoveredFromUncleanExit&&host.status==='ready'&&<div className="recovery-banner">已恢复已保存内容；未完成操作未自动继续。</div>}
      <div className="timeline" ref={timelineRef} onScroll={event=>{const node=event.currentTarget;timelineFollowsLatest.current=node.scrollHeight-node.clientHeight-node.scrollTop<=24;}}>{visibleTimeline.length === 0 && <div className="empty-state"><div className="orb">A</div><h2>有什么可以一起完成？</h2><p>当前版本用于验证安全启动、流式输出、持久化与取消链路。</p></div>}
        {conversationGroups.map(group=>{const userMessages=group.entries.filter((entry):entry is Extract<VisibleEntry,{kind:'message'}>=>entry.kind==='message'&&entry.item.role==='user');const executionEntries=group.entries.filter(entry=>entry.kind!=='message');const assistantMessages=group.entries.filter((entry):entry is Extract<VisibleEntry,{kind:'message'}>=>entry.kind==='message'&&entry.item.role==='assistant');const artifacts=uniqueArtifacts(executionEntries);return <section className="conversation-turn" key={group.id}>{userMessages.map(entry=><MessageCard key={entry.item.id} message={entry.item}/>)}{executionEntries.length>0&&<ExecutionChain entries={executionEntries} onApprovalResolved={()=>state.threadId?openThread(state.threadId):Promise.resolve()}/>} {assistantMessages.map(entry=><MessageCard key={entry.item.id} message={entry.item}/>)}{artifacts.length>0?<OutputArtifacts artifacts={artifacts} selected={selectedArtifact} onPreview={artifact=>{setSelectedArtifact(artifact);setPreviewCollapsed(false);setPreviewExpanded(false);}}/>:null}</section>;})}
        {state.error && <div className="error-banner">{state.error}</div>}</div>
      <form className="composer" onSubmit={submit}><div className="skill-picker">{selectedSkillIds.map(id=>{const skill=availableSkills.find(item=>item.id===id);return <button type="button" className="skill-chip" key={id} onClick={()=>setSelectedSkillIds(values=>values.filter(value=>value!==id))}>{skill?.name??id} ×</button>})}<select aria-label="显式选择 Skill" value="" disabled={selectedSkillIds.length>=5||state.activeTurnId!==undefined} onChange={event=>{const id=event.target.value;if(id)setSelectedSkillIds(values=>values.includes(id)?values:[...values,id]);}}><option value="">＋ 选择 Skill</option>{availableSkills.filter(item=>!selectedSkillIds.includes(item.id)).map(item=><option key={item.id} value={item.id}>{item.name}</option>)}</select></div><textarea value={input} onChange={event => setInput(event.target.value)} placeholder={state.activeTurnId?'追加将在下一次工具安全点生效':'输入消息…'} disabled={host.status !== 'ready'} onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); } }} />{state.activeTurnId ? <><button type="submit" disabled={!input.trim()||state.activeTurnStatus==='CANCELLING'}>追加指令</button><button type="button" className="stop" onClick={() => void cancel()} disabled={state.activeTurnStatus === 'CANCELLING'}>{state.activeTurnStatus === 'CANCELLING' ? '正在停止' : '■ 停止'}</button></> : <button type="submit" disabled={!input.trim() || host.status !== 'ready'}>发送</button>}<label className="approval-mode" title={approvalMode==='AUTO'?'本次提交产生的审批将自动按“允许一次”处理':'每个敏感操作都等待你确认'}><span>安全授权</span><select aria-label="安全授权模式" value={approvalMode} disabled={state.activeTurnId!==undefined} onChange={event=>{const value=event.target.value as 'ASK'|'AUTO';setApprovalMode(value);localStorage.setItem('agentDesktop.approvalMode',value);}}><option value="ASK">请求批准</option><option value="AUTO">帮我批准</option></select></label></form>
    </section>{selectedArtifact&&!previewCollapsed&&!previewExpanded?<div className="preview-resizer" role="separator" aria-label="调整预览面板宽度" aria-orientation="vertical" aria-valuemin={PREVIEW_MIN_WIDTH} aria-valuemax={PREVIEW_MAX_WIDTH} aria-valuenow={previewWidth} tabIndex={0} onPointerDown={event=>{event.preventDefault();event.currentTarget.setPointerCapture(event.pointerId);}} onPointerMove={event=>{if(event.currentTarget.hasPointerCapture(event.pointerId))resizePreview(event.clientX,event.currentTarget);}} onPointerUp={event=>{if(event.currentTarget.hasPointerCapture(event.pointerId))event.currentTarget.releasePointerCapture(event.pointerId);localStorage.setItem('agentDesktop.previewWidth',String(previewWidthRef.current));}} onPointerCancel={event=>{if(event.currentTarget.hasPointerCapture(event.pointerId))event.currentTarget.releasePointerCapture(event.pointerId);}} onKeyDown={event=>{if(event.key!=='ArrowLeft'&&event.key!=='ArrowRight')return;event.preventDefault();const desiredWidth=previewWidth+(event.key==='ArrowLeft'?24:-24);const layoutRight=event.currentTarget.parentElement?.getBoundingClientRect().right;if(layoutRight!==undefined)resizePreview(layoutRight-desiredWidth,event.currentTarget);localStorage.setItem('agentDesktop.previewWidth',String(previewWidthRef.current));}}/>:null}{selectedArtifact&&!previewCollapsed?<ArtifactPreviewPanel artifact={selectedArtifact} expanded={previewExpanded} onCollapse={value=>{setPreviewCollapsed(value);if(value)setPreviewExpanded(false);}} onExpand={setPreviewExpanded}/>:null}</div>}</main>;
  }

function MessageCard({message}:{message:Message}){
  return <article className={`message ${message.role}`}><div className="message-label">{message.role==='user'?'你':'Agent'}</div>{message.role==='assistant'?<div className="message-body markdown-content"><ReactMarkdown remarkPlugins={[remarkGfm]} components={{a:props=><a {...props} target="_blank" rel="noreferrer"/>}}>{message.content||(message.status==='streaming'?'…':'')}</ReactMarkdown></div>:<div className="message-body">{message.content}</div>}{message.status==='cancelled'&&<small>已停止</small>}</article>;
}

function ExecutionChain({entries,onApprovalResolved}:{entries:VisibleEntry[];onApprovalResolved:()=>Promise<void>}){
  const toolCount=entries.filter(entry=>entry.kind==='tool').length;
  const running=entries.some(entry=>(entry.kind==='tool'&&(entry.item.status==='RUNNING'||entry.item.status==='WAITING_APPROVAL'))||(entry.kind==='approval'&&!entry.item.decision));
  const [expanded,setExpanded]=useState(running);
  return <details className={`execution-chain ${running?'running':''}`} open={expanded} onToggle={event=>setExpanded(event.currentTarget.open)}><summary><span className="execution-chain-title"><Icon name="chain"/><strong>执行过程</strong><small>{running?'执行中':`${toolCount} 个工具调用`}</small></span><span className="execution-toggle">展开 / 收起</span></summary><div className="execution-steps">{entries.map(entry=>entry.kind==='tool'?<ToolStep key={`tool:${entry.item.id}`} tool={entry.item}/>:entry.kind==='activation'?<div key={`skill:${entry.item.id}`} className="skill-activation">已{entry.item.reason==='EXPLICIT'?'显式':'隐式'}启用 <strong>{entry.item.name}</strong><span>{entry.item.scope}</span></div>:entry.kind==='approval'?<ApprovalCard key={`approval:${entry.item.id}`} approval={entry.item} onResolved={onApprovalResolved}/>:null)}</div></details>;
}

function ToolStep({tool}:{tool:Tool}){
  const active=tool.status==='RUNNING'||tool.status==='WAITING_APPROVAL';
  const [expanded,setExpanded]=useState(active);
  const statusLabel=tool.status==='WAITING_APPROVAL'?'等待批准':tool.status;
  return <details className={`tool-entry ${active?'running':''}`} open={expanded} onToggle={event=>setExpanded(event.currentTarget.open)}><summary><span className="tool-name"><Icon name="tool"/><strong>{tool.toolName}{tool.serverId?` · ${tool.serverId}`:''}</strong></span><span className="tool-status">{tool.status==='RUNNING'&&<i className="spinner" aria-label="工具执行中"/>}{statusLabel}{tool.elapsedMs!==undefined?` · ${tool.elapsedMs}ms`:''}<Icon name="chevronDown"/></span></summary><div className="tool-content">{tool.argumentsSummary&&<div><small>调用参数</small><pre>{tool.argumentsSummary}</pre></div>}{tool.resultSummary&&<div><small>调用结果</small><p>{tool.resultSummary}</p></div>}{tool.errorCode&&<div className="tool-error"><code>{tool.errorCode}</code><button type="button" onClick={()=>void navigator.clipboard.writeText(tool.errorCode??'')}>复制错误码</button></div>}</div></details>;
}

type IconName='search'|'collapse'|'expand'|'plus'|'skills'|'mcp'|'model'|'chevronDown'|'chevronRight'|'more'|'rename'|'archive'|'restore'|'settings'|'about'|'chain'|'tool';
const iconPaths:Record<IconName,string>={search:'M21 21l-4.35-4.35m2.35-5.65a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z',collapse:'M15 18l-6-6 6-6M21 4v16',expand:'M9 18l6-6-6-6M3 4v16',plus:'M12 5v14M5 12h14',skills:'M12 3l2.1 4.25L19 8l-3.5 3.4.83 4.8L12 14l-4.33 2.2.83-4.8L5 8l4.9-.75L12 3Z',mcp:'M8 7h8M8 17h8M7 7a2 2 0 1 1-4 0 2 2 0 0 1 4 0Zm14 10a2 2 0 1 1-4 0 2 2 0 0 1 4 0ZM5 9v3a5 5 0 0 0 5 5h7M19 15v-3a5 5 0 0 0-5-5h-1',model:'M12 3 4 7v10l8 4 8-4V7l-8-4Zm0 0v18M4 7l8 4 8-4',chevronDown:'m7 10 5 5 5-5',chevronRight:'m10 7 5 5-5 5',more:'M5 12h.01M12 12h.01M19 12h.01',rename:'M4 20h4l11-11-4-4L4 16v4Zm9-13 4 4',archive:'M4 7h16v13H4V7Zm-1-4h18v4H3V3Zm6 8h6',restore:'M4 12a8 8 0 1 0 2.34-5.66L4 8m0-5v5h5',settings:'M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm7.4-3.5a7.8 7.8 0 0 0-.1-1l2-1.55-2-3.46-2.4.97a8 8 0 0 0-1.75-1.02L14.8 3h-4l-.36 2.94a8 8 0 0 0-1.75 1.02l-2.4-.97-2 3.46 2 1.55a7.8 7.8 0 0 0 0 2l-2 1.55 2 3.46 2.4-.97a8 8 0 0 0 1.75 1.02L10.8 21h4l.36-2.94a8 8 0 0 0 1.75-1.02l2.4.97 2-3.46-2-1.55a7.8 7.8 0 0 0 .1-1Z',about:'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20Zm0-11v6m0-10h.01',chain:'M10 13a5 5 0 0 0 7.54.54l2-2a5 5 0 0 0-7.07-7.07l-1.15 1.15M14 11a5 5 0 0 0-7.54-.54l-2 2a5 5 0 0 0 7.07 7.07l1.15-1.15',tool:'M14.7 6.3a4 4 0 0 0-5 5L4 17l3 3 5.7-5.7a4 4 0 0 0 5-5l-2.4 2.4-3-3 2.4-2.4Z'};
function Icon({name}:{name:IconName}){return <svg className="ui-icon" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d={iconPaths[name]}/></svg>;}

function About({release,recovery,host}:{release?:ReleaseInfo;recovery?:RecoveryState;host:HostState}){
  const [diagnostic,setDiagnostic]=useState<DiagnosticPreview>();const [diagnosticStatus,setDiagnosticStatus]=useState<string>();
  async function preview(){setDiagnosticStatus(undefined);try{setDiagnostic(await window.agentDesktop.previewDiagnostics());}catch{setDiagnosticStatus('无法生成诊断预览。');}}
  async function exportLocal(){try{const result=await window.agentDesktop.exportDiagnostics();setDiagnosticStatus(result.exported?`已导出，SHA-256：${result.sha256}`:'已取消导出。');}catch{setDiagnosticStatus('诊断导出失败。');}}
  return <section className="workspace about-page"><header><div><h1>关于版本</h1><p>只读发布、恢复与脱敏诊断信息</p></div></header><div className="about-card"><h2>iWork</h2><dl><div><dt>应用版本</dt><dd>{release?.appVersion??'—'}</dd></div><div><dt>Host 版本</dt><dd>{release?.hostVersion??'—'}</dd></div><div><dt>发布通道</dt><dd>{release?.channel??'—'}</dd></div><div><dt>签名状态</dt><dd>{release?(release.signed?'已签名':'未签名，仅供内部验收'):'—'}</dd></div><div><dt>平台</dt><dd>{release?`${release.platform} · ${release.arch}`:'—'}</dd></div><div><dt>Host 状态</dt><dd>{host.status}</dd></div></dl><p>发布者：杭州烛光鱼有限公司</p><p>升级需退出应用后，显式运行来源可信且 SHA-256 已核验的新安装包。应用不会后台下载或自动安装更新。</p>{recovery?.diagnosticId&&<button onClick={()=>void navigator.clipboard.writeText(recovery.diagnosticId??'')}>复制诊断编号</button>}</div><div className="about-card"><h2>本地诊断包</h2><p>仅包含版本、粗粒度平台信息、Provider/MCP 状态、数据库计数与进程资源；不会包含聊天正文、路径、密钥、Tool 内容或 reasoning。</p><div className="thread-actions"><button disabled={host.status!=='ready'} onClick={()=>void preview()}>预览内容</button><button disabled={!diagnostic} onClick={()=>void exportLocal()}>选择位置并导出</button></div>{diagnostic&&<><dl><div><dt>生成时间</dt><dd>{diagnostic.generatedAt}</dd></div><div><dt>文件</dt><dd>{diagnostic.files.join('、')}</dd></div><div><dt>数据库完整性</dt><dd>{String(diagnostic.database.integrity??'—')}</dd></div></dl><details><summary>查看排除项与 allowlist JSON</summary><p>强制排除：{diagnostic.excluded.join('；')}</p><pre>{JSON.stringify(diagnostic,null,2)}</pre></details></>}{diagnosticStatus&&<small>{diagnosticStatus}</small>}</div></section>;
}

function ApprovalCard({approval,onResolved}:{approval:Approval;onResolved:()=>Promise<void>}){
  const [now,setNow]=useState(Date.now());const [busy,setBusy]=useState(false);const [error,setError]=useState<string>();
  useEffect(()=>{if(approval.decision)return;const timer=window.setInterval(()=>setNow(Date.now()),1000);return()=>window.clearInterval(timer);},[approval.decision]);
  const remaining=Math.max(0,Math.ceil((Date.parse(approval.expiresAt)-now)/1000));
  async function decide(decision:ApprovalDecision){setBusy(true);setError(undefined);try{await window.agentDesktop.resolveApproval({approvalId:approval.id,decision,expectedRequestFingerprint:approval.requestFingerprint});await onResolved();}catch{setError('审批已过期、失效或由另一操作处理。');await onResolved().catch(()=>undefined);}finally{setBusy(false);}}
  const labels:Record<string,string>={ALLOW_ONCE:'已允许一次',ALLOW_THREAD:'当前会话已允许',DENY:'已拒绝',EXPIRED:'已过期',CANCELLED:'已取消',INVALIDATED:'重启后已失效'};
  return <article className={`approval-card risk-${approval.risk}`}><div className="approval-heading"><div><small>{approval.capability} · {approval.risk}</small><strong>{approval.title}</strong></div><span>{approval.decision?labels[approval.decision]??approval.decision:`剩余 ${remaining}s`}</span></div><p>{approval.summary}</p>{approval.warning&&<div className="approval-warning">{approval.warning}</div>}<dl><div><dt>来源</dt><dd>{String(approval.source.displayName??approval.source.sourceId??'外部工具')}</dd></div><div><dt>目标</dt><dd>{String(approval.target.toolId??approval.target.displayPath??'已脱敏目标')}</dd></div></dl>{!approval.decision&&remaining>0&&<div className="approval-actions"><button disabled={busy} onClick={()=>void decide('DENY')}>拒绝</button><button disabled={busy} onClick={()=>void decide('ALLOW_ONCE')}>允许一次</button><button disabled={busy} onClick={()=>void decide('ALLOW_THREAD')}>本次应用运行期间，在当前会话允许</button></div>}{error&&<small className="approval-error">{error}</small>}</article>;
}
