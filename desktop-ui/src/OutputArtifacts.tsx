import { useState } from 'react';

export default function OutputArtifacts({ artifacts, selected, onPreview }: { artifacts: OutputArtifact[]; selected?: OutputArtifact; onPreview: (artifact: OutputArtifact) => void }) {
  const [menu, setMenu] = useState<string>(); const [status, setStatus] = useState<string>();
  async function action(kind: 'open' | 'folder' | 'save', artifact: OutputArtifact) {
    setMenu(undefined); setStatus(undefined);
    try {
      if (kind === 'open') await window.agentDesktop.openArtifact(artifact);
      else if (kind === 'folder') await window.agentDesktop.showArtifactInFolder(artifact);
      else { const result = await window.agentDesktop.saveArtifactAs(artifact); if (!result.saved) return; }
      setStatus(kind === 'save' ? `已另存 ${artifact.fileName}` : undefined);
    } catch { setStatus(`无法操作 ${artifact.fileName}，文件可能已被移动或删除。`); }
  }
  return <div className="artifact-output"><div className="artifact-output-heading"><strong>输出文件</strong><small>{artifacts.length} 个</small></div>{artifacts.map(artifact => { const key = `${artifact.rootId}:${artifact.relativePath}`; return <article className={`artifact-card ${selected?.relativePath === artifact.relativePath ? 'selected' : ''}`} key={key}><button className="artifact-main" onClick={() => onPreview(artifact)}><span className={`artifact-icon artifact-${fileKind(artifact.fileName)}`}>{fileGlyph(artifact.fileName)}</span><span><strong>{artifact.fileName}</strong><small>{fileLabel(artifact.fileName)} · {formatBytes(artifact.byteSize)}</small></span></button><div className="artifact-actions"><button aria-label={`${artifact.fileName} 打开方式`} aria-expanded={menu === key} onClick={() => setMenu(value => value === key ? undefined : key)}>打开方式⌄</button>{menu === key ? <div className="artifact-menu"><button onClick={() => void action('open', artifact)}>默认应用</button><button onClick={() => void action('folder', artifact)}>打开文件夹</button><button onClick={() => void action('save', artifact)}>另存为</button></div> : null}</div></article>; })}{status ? <small className="artifact-status">{status}</small> : null}</div>;
}

function fileKind(name: string) { const lower = name.toLocaleLowerCase(); if (lower.endsWith('.docx')) return 'word'; if (lower.endsWith('.xlsx') || lower.endsWith('.xls')) return 'excel'; if (lower.endsWith('.pptx')) return 'presentation'; if (lower.endsWith('.html') || lower.endsWith('.htm')) return 'html'; return 'markdown'; }
function fileGlyph(name: string) { return fileKind(name) === 'word' ? 'W' : fileKind(name) === 'excel' ? 'X' : fileKind(name) === 'presentation' ? 'P' : fileKind(name) === 'html' ? '<>' : 'M'; }
function fileLabel(name: string) { return ({ word: '文档 · DOCX', excel: name.toLocaleLowerCase().endsWith('.xls') ? '表格 · XLS' : '表格 · XLSX', presentation: '演示文稿 · PPTX', html: '网页 · HTML', markdown: '文档 · Markdown' })[fileKind(name)]; }
function formatBytes(value: number) { return value < 1024 ? `${value} B` : value < 1024 * 1024 ? `${(value / 1024).toFixed(1)} KB` : `${(value / 1024 / 1024).toFixed(1)} MB`; }
