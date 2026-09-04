import { FormEvent, useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

export default function SkillsSettings({hostReady}:{hostReady:boolean}) {
  const [scope,setScope]=useState<SkillScope|''>('');
  const [status,setStatus]=useState<SkillStatus|''>('');
  const [items,setItems]=useState<SkillSummary[]>([]);
  const [selected,setSelected]=useState<SkillDetail>();
  const [installation,setInstallation]=useState<SkillInstallationInfo>();
  const [resource,setResource]=useState<SkillResourceView>();
  const [viewerMode,setViewerMode]=useState<'source'|'preview'>('source');
  const [message,setMessage]=useState('');
  const [importOpen,setImportOpen]=useState(false);
  const [sourceType,setSourceType]=useState<'local'|'github'>('local');
  const [repositoryUrl,setRepositoryUrl]=useState('');
  const [requestedRef,setRequestedRef]=useState('');
  const [subdirectory,setSubdirectory]=useState('');
  const [manifest,setManifest]=useState<SkillImportManifest>();
  const [importBusy,setImportBusy]=useState(false);
  const [importError,setImportError]=useState('');
  const [replaceConfirmed,setReplaceConfirmed]=useState(false);

  async function load() {
    if(!hostReady)return;
    try { const page=await window.agentDesktop.listSkills({scope:scope||undefined,status:status||undefined});setItems(page.items);setMessage(''); }
    catch { setMessage('无法读取 Skill 目录。'); }
  }
  useEffect(()=>{void load();},[hostReady,scope,status]);

  async function open(id:string){
    try{
      setResource(undefined);setViewerMode('source');
      const detail=await window.agentDesktop.getSkill(id);setSelected(detail);
      setInstallation(await window.agentDesktop.getSkillInstallation(id));
    }catch{setInstallation(undefined);setMessage('无法安全读取该 Skill。');}
  }
  async function refresh(){try{const target=scope||'WORKSPACE';const result=await window.agentDesktop.refreshSkills(target);await load();setMessage(`${target} 已刷新：有效 ${result.valid}，无效 ${result.invalid}，被覆盖 ${result.shadowed}`);}catch{setMessage('刷新失败，请检查 Skill 根目录。');}}
  async function toggle(item:SkillSummary){try{await window.agentDesktop.setSkillEnabled({skillId:item.id,enabled:!item.enabled});await load();if(selected?.id===item.id)await open(item.id);}catch{setMessage('启停操作失败。');}}
  async function read(item:SkillResourceDescriptor){try{if(!selected)return;setViewerMode('source');setResource(await window.agentDesktop.readSkillResource({skillId:selected.id,resourceId:item.id}));}catch{setMessage('资源不可读取，可能已变更或超出安全边界。');}}

  async function inspectLocal(){
    setImportBusy(true);setImportError('');setManifest(undefined);setReplaceConfirmed(false);
    try{const result=await window.agentDesktop.selectAndInspectLocalSkill();if('cancelled'in result){setImportError('已取消，未产生任何变更。');return;}setManifest(result);}
    catch(error){setImportError(importMessage(error));}finally{setImportBusy(false);}
  }
  async function inspectGitHub(event:FormEvent){
    event.preventDefault();setImportBusy(true);setImportError('');setManifest(undefined);setReplaceConfirmed(false);
    try{setManifest(await window.agentDesktop.inspectGitHubSkill({repositoryUrl:repositoryUrl.trim(),ref:requestedRef.trim()||undefined,subdirectory:subdirectory.trim()||undefined}));}
    catch(error){setImportError(importMessage(error));}finally{setImportBusy(false);}
  }
  async function closeImport(){
    if(importBusy)try{await window.agentDesktop.cancelActiveSkillInspection();}catch{/* Request is already terminal. */}
    const sessionId=manifest?.sessionId;setImportOpen(false);setManifest(undefined);setImportError('');setReplaceConfirmed(false);
    if(sessionId)try{await window.agentDesktop.cancelSkillImport(sessionId);}catch{/* Host TTL cleanup remains authoritative. */}
  }
  async function commitImport(){
    if(!manifest)return;
    const action=manifest.conflict==='IDENTICAL'?'NO_OP':manifest.conflict==='REPLACE_REQUIRED'?'REPLACE':'INSTALL';
    if(action==='REPLACE'&&!replaceConfirmed)return;
    if(!window.confirm(action==='REPLACE'?`确认替换 ${manifest.name}？旧版本将保留用于一次回滚。`:`确认安装 ${manifest.name}？安装后默认停用。`))return;
    setImportBusy(true);setImportError('');
    try{
      const result=await window.agentDesktop.commitSkillImport({sessionId:manifest.sessionId,action,expectedExistingContentHash:manifest.expectedExistingContentHash??undefined});
      setImportOpen(false);setManifest(undefined);await load();setMessage(`${result.name} ${result.operation==='NO_OP'?'内容相同，无需重复安装':'已安装，当前未启用'}。`);await open(result.skillId);
    }catch(error){setImportError(importMessage(error));}finally{setImportBusy(false);}
  }
  async function removeInstalled(){
    if(!selected||!installation?.managed||!window.confirm(`删除由本应用管理的 ${selected.name}？外部来源不会被修改。`))return;
    try{await window.agentDesktop.removeInstalledSkill(selected.id);setSelected(undefined);setInstallation(undefined);await load();setMessage('受管 USER Skill 已删除。');}catch(error){setMessage(importMessage(error));}
  }
  async function rollbackInstalled(){
    if(!selected||!installation?.rollbackAvailable||!window.confirm(`回滚 ${selected.name} 到上一个受管版本？回滚后保持停用。`))return;
    try{const result=await window.agentDesktop.rollbackInstalledSkill(selected.id);await load();await open(result.skillId);setMessage('已回滚到上一个版本并保持停用。');}catch(error){setMessage(importMessage(error));}
  }

  const installBlocked=manifest?.conflict==='CONFLICTED'||(manifest?.conflict==='REPLACE_REQUIRED'&&!replaceConfirmed);
  const activeDocumentIsMarkdown=resource?isMarkdownResource(resource):Boolean(selected);
  const activeDocumentContent=resource?(resource.binary?'二进制资源仅提供描述，不进入上下文。':resource.content??''):(selected?.instructions??'该 Skill 当前不可加载。');

  return <div className="skills-settings">
    <section className="skill-list">
      <div className="section-heading"><div><h2>本地 Skill</h2><p>仅显示经过 Java Runtime 校验的目录元数据</p></div><div className="skill-heading-actions"><button onClick={()=>{setImportError('');setImportOpen(true);}} disabled={!hostReady}>导入 Skill</button><button className="secondary" onClick={()=>void refresh()} disabled={!hostReady}>刷新</button></div></div>
      <div className="skill-filters"><select aria-label="来源" value={scope} onChange={event=>setScope(event.target.value as SkillScope|'')}><option value="">全部来源</option><option value="WORKSPACE">工作区</option><option value="USER">用户</option><option value="BUILTIN">内置</option></select><select aria-label="状态" value={status} onChange={event=>setStatus(event.target.value as SkillStatus|'')}><option value="">全部状态</option><option value="VALID">有效</option><option value="INVALID">诊断</option><option value="SHADOWED">被覆盖</option><option value="MISSING">已移除</option></select></div>
      {items.map(item=><article key={item.id} className={`skill-card ${selected?.id===item.id?'selected':''}`}><button className="skill-open" onClick={()=>void open(item.id)}><span><strong>{item.name}</strong><small>{item.scope} · {item.status}</small></span><p>{item.description||item.lastErrorCode||'无可用描述'}</p></button><button className="skill-toggle" onClick={()=>void toggle(item)} disabled={item.status==='MISSING'}>{item.enabled?'停用':'启用'}</button></article>)}
      {message&&<div className="validation-message">{message}</div>}
    </section>
    <section className="skill-viewer">
      {!selected?<div className="empty-panel">选择一个 Skill 查看只读内容与资源。</div>:<>
        <div className="section-heading"><div><h2>{selected.name}</h2><p>{selected.scope} · {selected.status} · {selected.contentHash.slice(0,12)}</p></div><div className="skill-heading-actions">{activeDocumentIsMarkdown&&<DocumentModeSwitch mode={viewerMode} onChange={setViewerMode}/>} {installation?.managed&&<>{installation.rollbackAvailable&&<button className="secondary" onClick={()=>void rollbackInstalled()}>回滚</button>}<button className="danger" onClick={()=>void removeInstalled()}>删除安装</button></>}</div></div>
        {installation?.managed&&<div className="installation-card"><strong>由本应用管理</strong><span>{installation.sourceType==='GITHUB'?installation.repositoryUrl:'本地目录'}{installation.resolvedCommitSha?` · ${installation.resolvedCommitSha.slice(0,12)}`:''}</span><small>{installation.installedAt?new Date(installation.installedAt).toLocaleString():''}</small></div>}
        {selected.lastErrorCode&&<div className="error-banner"><code>{selected.lastErrorCode}</code></div>}
        {selected.allowedTools&&<p className="skill-note">allowed-tools：{selected.allowedTools}（仅展示，不授权）</p>}
        <h3>SKILL.md</h3>{resource?null:<DocumentViewer content={activeDocumentContent} markdown={activeDocumentIsMarkdown} mode={viewerMode}/>} 
        <h3>资源</h3><div className="skill-resources">{selected.resources.map(item=><button key={item.id} onClick={()=>void read(item)}><span>{item.relativePath}</span><small>{item.kind} · {item.byteSize} B{item.executableCandidate?' · 脚本候选（禁止执行）':''}</small></button>)}</div>
        {resource&&<><h3>{resource.relativePath}</h3><DocumentViewer content={activeDocumentContent} markdown={activeDocumentIsMarkdown} mode={viewerMode}/></>}
      </>}
    </section>
    {importOpen&&<div className="modal-backdrop" role="presentation"><section className="skill-import-dialog" role="dialog" aria-modal="true" aria-labelledby="skill-import-title">
      <div className="section-heading"><div><h2 id="skill-import-title">导入 Skill</h2><p>检查不会安装；确认后仅写入 USER scope，且默认停用。</p></div><button className="dialog-close" onClick={()=>void closeImport()}>关闭</button></div>
      {!manifest&&<><div className="source-tabs"><button className={sourceType==='local'?'selected':''} onClick={()=>setSourceType('local')}>本地目录</button><button className={sourceType==='github'?'selected':''} onClick={()=>setSourceType('github')}>公开 GitHub</button></div>
        {sourceType==='local'?<div className="local-source"><p>系统目录选择器会直接把所选目录交给本地 Host；页面不会收到绝对路径。</p><button onClick={()=>void inspectLocal()} disabled={importBusy}>{importBusy?'正在检查…':'选择并检查目录'}</button></div>:<form className="github-source" onSubmit={event=>void inspectGitHub(event)}><label>仓库 URL<input required type="url" placeholder="https://github.com/owner/repo" value={repositoryUrl} maxLength={512} onChange={event=>setRepositoryUrl(event.target.value)}/></label><label>ref（可选）<input placeholder="branch、tag 或 commit" value={requestedRef} maxLength={256} onChange={event=>setRequestedRef(event.target.value)}/></label><label>Skill 子目录（可选）<input placeholder="skills/example-skill" value={subdirectory} maxLength={512} onChange={event=>setSubdirectory(event.target.value)}/></label><button type="submit" disabled={importBusy||!repositoryUrl.trim()}>{importBusy?'正在获取并校验…':'检查 GitHub Skill'}</button></form>}
      </>}
      {manifest&&<div className="import-preview"><div className="preview-title"><div><strong>{manifest.name}</strong><p>{manifest.description}</p></div><span>{manifest.conflict}</span></div><dl><div><dt>来源</dt><dd>{manifest.source.type==='GITHUB'?manifest.source.repositoryUrl:'本地目录'}</dd></div>{manifest.source.resolvedCommitSha&&<div><dt>固定 commit</dt><dd><code>{manifest.source.resolvedCommitSha}</code></dd></div>}<div><dt>包摘要</dt><dd><code>{manifest.packageHash.slice(0,16)}</code> · {formatBytes(manifest.totalBytes)}</dd></div>{manifest.license&&<div><dt>许可证</dt><dd>{manifest.license}</dd></div>}{manifest.compatibility&&<div><dt>兼容性</dt><dd>{manifest.compatibility}</dd></div>}</dl>
        <h3>资源与脚本候选</h3><div className="manifest-resources">{manifest.resources.map(item=><span key={item.id}>{item.relativePath}<small>{item.kind} · {formatBytes(item.byteSize)}{item.executableCandidate?' · 禁止执行':''}</small></span>)}</div>
        {manifest.dependencies.length>0&&<><h3>MCP 依赖提示（不自动安装或授权）</h3><div className="dependency-list">{manifest.dependencies.map((item,index)=><span key={`${item.value}:${index}`}>{item.value}<small>{item.type} · {item.status}</small></span>)}</div></>}
        {manifest.warningCodes.length>0&&<div className="warning-list">{manifest.warningCodes.map(code=><code key={code}>{code}</code>)}</div>}
        {manifest.conflict==='REPLACE_REQUIRED'&&<label className="replace-confirm"><input type="checkbox" checked={replaceConfirmed} onChange={event=>setReplaceConfirmed(event.target.checked)}/>我确认替换现有 USER Skill，并保留最近版本用于回滚。</label>}
        {manifest.conflict==='CONFLICTED'&&<div className="validation-message">现有 USER 目录无效或冲突，请先人工清理，当前检查不能提交。</div>}
        <div className="import-actions"><button className="secondary" onClick={()=>void closeImport()} disabled={importBusy}>取消</button><button onClick={()=>void commitImport()} disabled={importBusy||installBlocked}>{importBusy?'正在提交…':manifest.conflict==='IDENTICAL'?'完成（无需写入）':manifest.conflict==='REPLACE_REQUIRED'?'确认替换':'确认安装'}</button></div>
      </div>}
      {importError&&<div className="error-banner"><code>{importError}</code></div>}
    </section></div>}
  </div>;
}

function importMessage(error:unknown):string{
  const code=error instanceof Error?error.message:'SKILL_IMPORT_FAILED';
  if(code==='SKILL_IMPORT_PACKAGE_TOO_LARGE')return 'Skill 包超出安全限制：最多 256 个资源、目录深度 8、单文件 256 KiB、总大小 20 MiB。（SKILL_IMPORT_PACKAGE_TOO_LARGE）';
  return code;
}
function formatBytes(value:number):string{return value<1024?`${value} B`:value<1024*1024?`${(value/1024).toFixed(1)} KiB`:`${(value/1024/1024).toFixed(1)} MiB`;}

function isMarkdownResource(resource:SkillResourceView):boolean{return resource.mediaType==='text/markdown'||/\.md(?:own)?$/i.test(resource.relativePath);}

function DocumentModeSwitch({mode,onChange}:{mode:'source'|'preview';onChange:(mode:'source'|'preview')=>void}){
  return <div className="document-mode-switch" aria-label="Markdown 显示模式"><button className={mode==='source'?'active':''} aria-pressed={mode==='source'} onClick={()=>onChange('source')}>源码</button><button className={mode==='preview'?'active':''} aria-pressed={mode==='preview'} onClick={()=>onChange('preview')}>预览</button></div>;
}

function DocumentViewer({content,markdown,mode}:{content:string;markdown:boolean;mode:'source'|'preview'}){
  return markdown&&mode==='preview'?<div className="markdown-content skill-markdown-viewer"><ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown></div>:<pre className="plain-viewer">{content}</pre>;
}
