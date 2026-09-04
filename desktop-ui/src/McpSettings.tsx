import { FormEvent, useEffect, useMemo, useState } from 'react';
import FileRootSettings from './FileRootSettings';

type Draft = {
  id?: string;
  name: string;
  transport: 'STDIO' | 'STREAMABLE_HTTP';
  enabled: boolean;
  endpoint: string;
  executable: string;
  argumentsText: string;
  workingDirectory: string;
  environmentText: string;
  bearerToken: string;
};

type Diagnostic = { serverId: string; status: string; errorCode?: string | null; summary?: string | null; recentStderr: string[] };

const emptyDraft: Draft = { name: '', transport: 'STREAMABLE_HTTP', enabled: true, endpoint: '', executable: '', argumentsText: '', workingDirectory: '', environmentText: '', bearerToken: '' };
const environmentNamePattern = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/;
const windowsAbsolutePathPattern = /^[A-Za-z]:[\\/]/;

function validationMessage(draft: Draft): string | undefined {
  if (!draft.name.trim()) return '请输入名称。';
  if (Array.from(draft.name.trim()).length > 80) return '名称不能超过 80 个字符。';
  if (draft.transport === 'STREAMABLE_HTTP') {
    try {
      const endpoint = new URL(draft.endpoint);
      if (endpoint.protocol !== 'http:' && endpoint.protocol !== 'https:') return 'Endpoint 必须使用 HTTP 或 HTTPS。';
      if (endpoint.username || endpoint.password || endpoint.hash) return 'Endpoint 不能包含账号、密码或 fragment。';
    } catch { return '请输入有效的 HTTP Endpoint。'; }
    if (draft.bearerToken.includes('\r') || draft.bearerToken.includes('\n')) return 'Bearer Token 不能包含换行。';
    return undefined;
  }
  const executable = draft.executable.trim();
  if (!windowsAbsolutePathPattern.test(executable) && !executable.startsWith('/')) return '可执行文件必须是绝对路径。';
  if (/(^|[\\/])(cmd|powershell|pwsh|sh|bash|zsh)(\.exe)?$/i.test(executable)) return '不允许把 Shell 作为 MCP 可执行文件。';
  const workingDirectory = draft.workingDirectory.trim();
  if (!windowsAbsolutePathPattern.test(workingDirectory) && !workingDirectory.startsWith('/')) return '工作目录必须是绝对路径。';
  const invalidEnvironment = draft.environmentText.split(/\r?\n/).map(value => value.trim()).filter(Boolean).find(value => !environmentNamePattern.test(value));
  if (invalidEnvironment) return `环境变量名不合法：${invalidEnvironment}`;
  const argumentsList = draft.argumentsText.split(/\r?\n/).filter(Boolean);
  if (argumentsList.length > 64 || argumentsList.some(value => value.length > 4096)) return '参数最多 64 个，单项不能超过 4096 字符。';
  return undefined;
}

export default function McpSettings({ hostReady }: { hostReady: boolean }) {
  const [servers, setServers] = useState<McpServerSummary[]>([]);
  const [tools, setTools] = useState<ToolDescriptor[]>([]);
  const [draft, setDraft] = useState<Draft>(emptyDraft);
  const [selected, setSelected] = useState<string>();
  const [diagnostic, setDiagnostic] = useState<Diagnostic>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const validation = useMemo(() => validationMessage(draft), [draft]);

  async function refresh(serverId = selected): Promise<void> {
    if (!hostReady) return;
    const page = await window.agentDesktop.listMcpServers();
    setServers(page.items);
    if (serverId && page.items.some(server => server.id === serverId)) {
      const [nextTools, nextDiagnostic] = await Promise.all([
        window.agentDesktop.listMcpTools(serverId),
        window.agentDesktop.getMcpDiagnostic(serverId)
      ]);
      setTools(nextTools);
      setDiagnostic(nextDiagnostic);
    }
  }

  useEffect(() => {
    if (!hostReady) return;
    void refresh().catch(() => setError('无法读取 MCP 配置。'));
  }, [hostReady]);

  async function choose(server: McpServerSummary): Promise<void> {
    setSelected(server.id);
    setError(undefined);
    setDraft({ id: server.id, name: server.name, transport: server.transport, enabled: server.enabled, endpoint: server.endpoint ?? '', executable: server.executable ?? '', argumentsText: server.arguments.join('\n'), workingDirectory: server.workingDirectory ?? '', environmentText: server.allowedEnvironmentNames.join('\n'), bearerToken: '' });
    const [nextTools, nextDiagnostic] = await Promise.all([
      window.agentDesktop.listMcpTools(server.id),
      window.agentDesktop.getMcpDiagnostic(server.id)
    ]);
    setTools(nextTools);
    setDiagnostic(nextDiagnostic);
  }

  async function save(event: FormEvent): Promise<void> {
    event.preventDefault();
    if (validation) { setError(validation); return; }
    setBusy(true); setError(undefined);
    try {
      const payload: McpServerDraft = { id: draft.id, name: draft.name.trim(), transport: draft.transport, enabled: draft.enabled };
      if (draft.transport === 'STDIO') {
        payload.executable = draft.executable.trim();
        payload.arguments = draft.argumentsText.split(/\r?\n/).filter(Boolean);
        payload.workingDirectory = draft.workingDirectory.trim();
        payload.allowedEnvironmentNames = draft.environmentText.split(/\r?\n/).map(value => value.trim()).filter(Boolean);
      } else payload.endpoint = draft.endpoint.trim();
      const saved = await window.agentDesktop.saveMcpServer(payload);
      setSelected(saved.id);
      setDraft(value => ({ ...value, id: saved.id }));
      await refresh(saved.id);
    } catch { setError('配置不合法或保存失败。'); }
    finally { setBusy(false); }
  }

  async function connect(): Promise<void> {
    if (!draft.id) return;
    setBusy(true); setError(undefined);
    try {
      await window.agentDesktop.connectMcpServer({ serverId: draft.id, bearerToken: draft.bearerToken || undefined });
      setDraft(value => ({ ...value, bearerToken: '' }));
      await refresh(draft.id);
    } catch {
      await refresh(draft.id).catch(() => undefined);
      setError('连接失败，请根据下方稳定错误码检查配置或凭据。');
    } finally { setBusy(false); }
  }

  async function disconnect(): Promise<void> {
    if (!draft.id) return;
    setBusy(true); setError(undefined);
    try { await window.agentDesktop.disconnectMcpServer(draft.id); await refresh(draft.id); }
    catch { setError('断开失败。'); }
    finally { setBusy(false); }
  }

  async function remove(): Promise<void> {
    if (!draft.id || !window.confirm(`确定删除 MCP Server“${draft.name}”吗？\n连接会断开，Tool 将停用，已保存的 Bearer 凭据会被清理；历史 Tool Card 会保留。`)) return;
    setBusy(true); setError(undefined);
    try {
      await window.agentDesktop.deleteMcpServer(draft.id);
      const page = await window.agentDesktop.listMcpServers();
      setServers(page.items);
      setSelected(undefined);
      setDraft(emptyDraft);
      setTools([]);
      setDiagnostic(undefined);
    } catch { setError('删除失败，请重试。'); }
    finally { setBusy(false); }
  }

  return <><div className="mcp-settings">
    <section className="mcp-list">
      <div className="section-heading"><div><h2>MCP Server</h2><p>仅保存非敏感配置；Bearer 由系统安全存储保管。</p></div><button onClick={() => { setSelected(undefined); setTools([]); setDiagnostic(undefined); setError(undefined); setDraft(emptyDraft); }}>新建</button></div>
      {servers.map(server => <button key={server.id} className={`server-card ${selected === server.id ? 'selected' : ''}`} onClick={() => void choose(server)}><span><strong>{server.name}</strong><small>{server.transport}</small></span><span className={`status status-${server.status.toLowerCase()}`}>{server.status}</span><small>{server.lastErrorCode ?? (server.enabled ? '已启用' : '已停用')}</small></button>)}
    </section>
    <section className="mcp-editor">
      <form onSubmit={save} noValidate>
        <div className="section-heading"><div><h2>{draft.id ? '编辑 Server' : '新建 Server'}</h2><p>STDIO 仅接受 executable 与逐行参数；HTTP Endpoint 支持 HTTP 和 HTTPS。</p></div></div>
        <label>名称<input required maxLength={80} value={draft.name} onChange={event => setDraft(value => ({ ...value, name: event.target.value }))} /></label>
        <label>传输<select value={draft.transport} onChange={event => setDraft(value => ({ ...value, transport: event.target.value as Draft['transport'] }))}><option value="STREAMABLE_HTTP">Streamable HTTP</option><option value="STDIO">STDIO</option></select></label>
        {draft.transport === 'STREAMABLE_HTTP' ? <>
          <label>Endpoint<input required placeholder="http://example.com/mcp 或 https://example.com/mcp" value={draft.endpoint} onChange={event => setDraft(value => ({ ...value, endpoint: event.target.value }))} /></label>
          <label>Bearer Token<input type="password" autoComplete="off" placeholder={draft.id ? '留空则使用已保存凭据' : '可选'} value={draft.bearerToken} onChange={event => setDraft(value => ({ ...value, bearerToken: event.target.value }))} /></label>
        </> : <>
          <label>可执行文件绝对路径<input required value={draft.executable} onChange={event => setDraft(value => ({ ...value, executable: event.target.value }))} /></label>
          <label>参数（每行一个）<textarea value={draft.argumentsText} onChange={event => setDraft(value => ({ ...value, argumentsText: event.target.value }))} /></label>
          <label>工作目录<input required value={draft.workingDirectory} onChange={event => setDraft(value => ({ ...value, workingDirectory: event.target.value }))} /></label>
          <label>允许的环境变量名（每行一个）<textarea value={draft.environmentText} onChange={event => setDraft(value => ({ ...value, environmentText: event.target.value }))} /></label>
        </>}
        <label className="check"><input type="checkbox" checked={draft.enabled} onChange={event => setDraft(value => ({ ...value, enabled: event.target.checked }))} />启用</label>
        {validation ? <div className="validation-message" role="status">{validation}</div> : null}
        {error ? <div className="error-banner" role="alert">{error}</div> : null}
        <div className="form-actions"><button type="submit" disabled={busy || Boolean(validation)}>保存</button>{draft.id ? <button type="button" onClick={() => void connect()} disabled={busy || !draft.enabled}>连接 / 重试</button> : null}{draft.id ? <button type="button" className="secondary" onClick={() => void disconnect()} disabled={busy}>断开</button> : null}{draft.id ? <button type="button" className="danger" onClick={() => void remove()} disabled={busy}>删除</button> : null}</div>
      </form>
      {diagnostic ? <section className="mcp-diagnostic" aria-live="polite"><div><h3>安全诊断</h3><span className={`status status-${diagnostic.status.toLowerCase()}`}>{diagnostic.status}</span></div>{diagnostic.errorCode ? <p><code>{diagnostic.errorCode}</code><button type="button" onClick={() => void navigator.clipboard.writeText(diagnostic.errorCode ?? '')}>复制错误码</button></p> : null}<small>{diagnostic.summary ?? '暂无错误。'}</small>{diagnostic.recentStderr.length > 0 ? <details><summary>最近的脱敏 stderr</summary><pre>{diagnostic.recentStderr.join('\n')}</pre></details> : null}</section> : null}
      <div className="tool-catalog"><h3>Tool 目录</h3>{tools.length === 0 ? <p>连接成功后显示工具目录。</p> : tools.map(tool => <article key={tool.id}><div><strong>{tool.name}</strong><small>{tool.riskClass}</small></div><p>{tool.description || '无描述'}</p><code>{tool.id}</code><span>{tool.enabled ? '可用于本周安全演示' : tool.unavailableReason ?? '不可调用'}</span></article>)}</div>
    </section>
  </div><FileRootSettings hostReady={hostReady}/></>;
}
