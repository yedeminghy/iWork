import { useEffect, useState } from 'react';

export default function FileRootSettings({hostReady}:{hostReady:boolean}){
  const [roots,setRoots]=useState<FileRootSummary[]>([]);const [mode,setMode]=useState<FileRootMode>('READ_ONLY');const [busy,setBusy]=useState(false);const [error,setError]=useState<string>();
  async function refresh(){if(hostReady)setRoots(await window.agentDesktop.listFileRoots('ws_default'));}
  useEffect(()=>{void refresh().catch(()=>setError('无法读取文件访问范围。'));},[hostReady]);
  async function add(){setBusy(true);setError(undefined);try{const result=await window.agentDesktop.selectAndAddFileRoot({workspaceId:'ws_default',mode});if(!('cancelled' in result))await refresh();}catch{setError('目录不可用、受保护或与现有根冲突。');}finally{setBusy(false);}}
  async function update(root:FileRootSummary,patch:Partial<Pick<FileRootSummary,'mode'|'enabled'>>){
    if((patch.mode==='READ_ONLY'||patch.enabled===false)&&!window.confirm('收窄或停用文件范围会立即使相关会话授权失效，继续吗？'))return;
    setBusy(true);setError(undefined);try{await window.agentDesktop.updateFileRoot({rootId:root.id,mode:patch.mode??root.mode,enabled:patch.enabled??root.enabled,expectedVersion:root.version});await refresh();}catch{setError('文件根已变化，请刷新后重试。');}finally{setBusy(false);}
  }
  async function remove(root:FileRootSummary){if(root.defaultRoot||!window.confirm(`删除文件范围“${root.label}”？`))return;setBusy(true);try{await window.agentDesktop.removeFileRoot({rootId:root.id,expectedVersion:root.version});await refresh();}catch{setError('删除失败，文件根可能已变化。');}finally{setBusy(false);}}
  return <section className="file-roots"><div className="section-heading"><div><h2>文件访问范围</h2><p>目录只是访问上界；根内读取和写入仍需单独审批。Renderer 不会收到目录绝对路径。</p></div><div className="root-add"><select aria-label="新增根模式" value={mode} onChange={event=>setMode(event.target.value as FileRootMode)}><option value="READ_ONLY">只读</option><option value="READ_WRITE">读写上界</option></select><button disabled={busy||!hostReady} onClick={()=>void add()}>选择目录</button></div></div>
    {error&&<div className="error-banner" role="alert">{error}</div>}<div className="root-list">{roots.map(root=><article key={root.id}><div><strong>{root.label}</strong><small>{root.maskedPath} · v{root.version}{root.defaultRoot?' · 默认根':''}</small></div><select aria-label={`${root.label} 模式`} disabled={busy} value={root.mode} onChange={event=>void update(root,{mode:event.target.value as FileRootMode})}><option value="READ_ONLY">只读</option><option value="READ_WRITE">读写上界</option></select><button className={root.enabled?'secondary':'enable'} disabled={busy} onClick={()=>void update(root,{enabled:!root.enabled})}>{root.enabled?'停用':'启用'}</button><button className="danger" disabled={busy||root.defaultRoot} onClick={()=>void remove(root)}>删除</button></article>)}</div>
  </section>;
}
