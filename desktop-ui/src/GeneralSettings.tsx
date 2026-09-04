import { useEffect, useState } from 'react';

export default function GeneralSettings() {
  const [settings, setSettings] = useState<DesktopSettingsView>();
  const [iterations, setIterations] = useState('10');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string>();

  useEffect(() => { void window.agentDesktop.getSettings().then(value => { setSettings(value); setIterations(String(value.maxIterations)); }).catch(() => setMessage('无法读取常规设置。')); }, []);

  async function chooseWorkspace() {
    setBusy(true); setMessage(undefined);
    try { const value = await window.agentDesktop.selectDefaultWorkspace(); if (!('cancelled' in value)) { setSettings(value); setMessage('默认工作区已保存，重启 iWork 后生效。'); } }
    catch { setMessage('所选目录不可用，请选择本机可访问的文件夹。'); } finally { setBusy(false); }
  }
  async function saveIterations() {
    const value = Number(iterations); if (!Number.isSafeInteger(value) || value < 1 || value > 50) { setMessage('最大迭代限制必须是 1–50 的整数。'); return; }
    setBusy(true); setMessage(undefined);
    try { const updated = await window.agentDesktop.setMaxIterations(value); setSettings(updated); setIterations(String(updated.maxIterations)); setMessage('最大迭代限制已保存，将从下一轮对话开始生效。'); }
    catch { setMessage('常规设置保存失败。'); } finally { setBusy(false); }
  }

  async function updateRuntimeLogs(patch:Partial<RuntimeLogSettingsUpdate>) {
    if (!settings) return;
    setBusy(true); setMessage(undefined);
    try {
      const current=settings.runtimeLogs;
      const updated=await window.agentDesktop.setRuntimeLogs({enabled:current.enabled,main:current.main,host:current.host,renderer:current.renderer,...patch});
      setSettings(updated); setMessage('运行日志设置已保存并立即生效。');
    } catch { setMessage('运行日志设置保存失败。'); } finally { setBusy(false); }
  }

  async function chooseLogDirectory() {
    setBusy(true); setMessage(undefined);
    try { const value=await window.agentDesktop.selectLogDirectory();if(!('cancelled' in value)){setSettings(value);setMessage('运行日志目录已更新。');} }
    catch { setMessage('所选日志目录不可用，请选择本机可写文件夹。'); } finally { setBusy(false); }
  }

  async function openLogDirectory() {
    setBusy(true); setMessage(undefined);
    try { await window.agentDesktop.openLogDirectory(); }
    catch { setMessage('无法打开运行日志目录。'); } finally { setBusy(false); }
  }

  return <section className="general-settings"><article className="settings-card"><div><h2>默认工作区</h2><p>Agent 生成的报告、脚本与表格默认保存在这里。出于安全考虑，页面仅显示脱敏路径。</p></div><div className="workspace-setting"><div className="workspace-badge"><span aria-hidden="true">📁</span><div><strong>{settings?.defaultWorkspaceLabel ?? '默认工作区'}</strong><small>{settings?.defaultWorkspaceMaskedPath ?? '读取中…'}</small></div></div><button disabled={busy} onClick={() => void chooseWorkspace()}>选择目录</button></div>{settings?.workspaceRestartRequired ? <div className="settings-notice">工作区变更将在重启 iWork 后生效；重启会同步更新默认文件根并使旧授权失效。</div> : null}</article>
    <article className="settings-card"><div><h2>Agent 运行</h2><p>限制单轮 ReAct 循环次数，避免异常工具链长时间运行。默认值为 10。</p></div><label className="iteration-setting"><span>最大迭代限制</span><input aria-label="最大迭代限制" type="number" min="1" max="50" step="1" value={iterations} onChange={event => setIterations(event.target.value)} /><button disabled={busy || Number(iterations) === settings?.maxIterations} onClick={() => void saveIterations()}>保存</button></label><small className="setting-help">取值范围 1–50，从下一轮新对话或重试开始生效。</small></article>
    <article className="settings-card"><div><h2>运行日志</h2><p>按来源记录脱敏后的本地运行日志，用于排查 Host、主进程和页面错误。日志默认关闭。</p></div>
      <label className="log-master-setting"><span><strong>启用本地运行日志</strong><small>设置变更立即生效，不记录完整对话正文。</small></span><input aria-label="启用本地运行日志" type="checkbox" checked={settings?.runtimeLogs.enabled??false} disabled={busy||!settings} onChange={event=>void updateRuntimeLogs({enabled:event.target.checked})}/></label>
      <div className="log-source-settings">
        <label><input type="checkbox" checked={settings?.runtimeLogs.main??true} disabled={busy||!settings||!settings.runtimeLogs.enabled} onChange={event=>void updateRuntimeLogs({main:event.target.checked})}/><span><strong>Electron 主进程</strong><small>应用启动、窗口和 IPC 边界</small></span></label>
        <label><input type="checkbox" checked={settings?.runtimeLogs.host??true} disabled={busy||!settings||!settings.runtimeLogs.enabled} onChange={event=>void updateRuntimeLogs({host:event.target.checked})}/><span><strong>后端 Host</strong><small>Spring、运行时和工具调度</small></span></label>
        <label><input type="checkbox" checked={settings?.runtimeLogs.renderer??true} disabled={busy||!settings||!settings.runtimeLogs.enabled} onChange={event=>void updateRuntimeLogs({renderer:event.target.checked})}/><span><strong>Renderer 页面</strong><small>页面控制台与渲染进程错误</small></span></label>
      </div>
      <div className="log-directory-setting"><div className="workspace-badge"><span aria-hidden="true">📄</span><div><strong>{settings?.runtimeLogs.directoryLabel??'日志目录'}</strong><small>{settings?.runtimeLogs.directoryMaskedPath??'读取中…'}</small></div></div><div className="log-directory-actions"><button disabled={busy} onClick={()=>void chooseLogDirectory()}>选择目录</button><button className="secondary" disabled={busy} onClick={()=>void openLogDirectory()}>打开目录</button></div></div>
      <small className="setting-help">分别写入 main.log、host.log 和 renderer.log；单文件 {settings?.runtimeLogs.maxFileSizeMb??10} MB，最多保留 {settings?.runtimeLogs.maxFiles??5} 份。</small>
    </article>
    <article className="settings-card compact"><h2>本地运行与隐私</h2><dl><div><dt>数据存储</dt><dd>本机 SQLite 与默认工作区</dd></div><div><dt>文件路径</dt><dd>仅主进程持有，页面使用安全引用</dd></div><div><dt>敏感操作</dt><dd>继续遵循“请求批准 / 帮我批准”设置</dd></div></dl></article>{message ? <div className="provider-message" role="status">{message}</div> : null}</section>;
}
