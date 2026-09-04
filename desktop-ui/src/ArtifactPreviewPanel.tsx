import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import ExcelPreview from './ExcelPreview';
const PdfPreview = lazy(() => import('./PdfPreview'));
const WordPreview = lazy(() => import('./WordPreview'));

type ArtifactPreviewPanelProps = {
  artifact: OutputArtifact;
  expanded: boolean;
  onCollapse: (value: boolean) => void;
  onExpand: (value: boolean) => void;
};

export default function ArtifactPreviewPanel({ artifact, expanded, onCollapse, onExpand }: ArtifactPreviewPanelProps) {
  const [preview, setPreview] = useState<ArtifactPreview>();
  const [error, setError] = useState<string>();
  const [layoutPreview, setLayoutPreview] = useState<ArtifactLayoutPreview>();
  const [layoutError, setLayoutError] = useState<string>();
  const [layoutLoading, setLayoutLoading] = useState(false);
  const [viewMode, setViewMode] = useState<'quick' | 'layout'>('quick');
  const artifactKey = `${artifact.rootId}:${artifact.relativePath}`;
  const isPresentation = /\.pptx$/i.test(artifact.fileName);
  const artifactKeyRef = useRef(artifactKey);
  const automaticLayoutKeyRef = useRef<string | undefined>(undefined);
  artifactKeyRef.current = artifactKey;

  useEffect(() => {
    let active = true;
    let resourcePreviewId: string | undefined;
    setPreview(undefined);
    setError(undefined);
    setLayoutPreview(undefined);
    setLayoutError(undefined);
    setLayoutLoading(false);
    setViewMode(isPresentation ? 'layout' : 'quick');
    void window.agentDesktop.previewArtifact(artifact).then(value => {
      if (value.kind === 'html') resourcePreviewId = value.previewId;
      if (active) setPreview(value);
      else if (resourcePreviewId) void window.agentDesktop.releaseArtifactPreview(resourcePreviewId);
    }).catch(() => { if (active) setError('无法预览文件。文件可能过大、过于复杂、损坏或已被移动。'); });
    return () => {
      active = false;
      if (resourcePreviewId) void window.agentDesktop.releaseArtifactPreview(resourcePreviewId);
    };
  }, [artifactKey]);

  useEffect(() => () => {
    if (layoutPreview) void window.agentDesktop.releaseArtifactPreview(layoutPreview.previewId);
  }, [layoutPreview]);

  const generateLayoutPreview = async () => {
    const requestedKey = artifactKey;
    setLayoutLoading(true);
    setLayoutError(undefined);
    try {
      const result = await window.agentDesktop.generateArtifactLayoutPreview(artifact);
      if (artifactKeyRef.current !== requestedKey) {
        await window.agentDesktop.releaseArtifactPreview(result.previewId);
        return;
      }
      setLayoutPreview(previous => {
        if (previous) void window.agentDesktop.releaseArtifactPreview(previous.previewId);
        return result;
      });
      setViewMode('layout');
    } catch (reason) {
      const message = String(reason);
      setLayoutError(message.includes('CONVERTER_UNAVAILABLE') ? '未检测到 LibreOffice，暂时无法生成版式预览。仍可使用默认应用打开。' : message.includes('TIMEOUT') ? '版式转换超时，文件可能过于复杂。' : '无法生成版式预览，请使用默认应用检查文件。');
    } finally {
      if (artifactKeyRef.current === requestedKey) setLayoutLoading(false);
    }
  };

  useEffect(() => {
    if (preview?.kind !== 'presentation' || automaticLayoutKeyRef.current === artifactKey) return;
    automaticLayoutKeyRef.current = artifactKey;
    void generateLayoutPreview();
  }, [artifactKey, preview?.kind]);

  const supportsLayout = /\.(docx|xlsx|xls|pptx)$/i.test(artifact.fileName);
  const subtitle = isPresentation ? '幻灯片预览 · PDF' : viewMode === 'layout' ? '版式预览 · PDF' : '快速预览 · 与默认应用可能有差异';
  const body = error ? <div className="preview-empty">{error}</div> : viewMode === 'layout'
    ? layoutPreview ? <PdfPreview url={layoutPreview.url} /> : layoutLoading ? <div className="preview-empty">正在生成幻灯片预览…</div> : layoutError ? <div className="preview-empty">无法显示版式预览。</div> : <div className="preview-empty">正在准备版式预览…</div>
    : !preview ? <div className="preview-empty">正在加载快速预览…</div> : <PreviewContent preview={preview} />;
  return <aside className="artifact-preview-panel"><header><div className="artifact-preview-heading"><strong>{artifact.fileName}</strong><small>{subtitle}</small></div><div className="artifact-preview-header-actions"><button title={expanded ? '退出展开' : '展开面板'} aria-label={expanded ? '退出展开' : '展开面板'} aria-pressed={expanded} onClick={() => onExpand(!expanded)}><PanelSizeIcon contracted={expanded} /></button><button title="收起预览" aria-label="收起预览" onClick={() => onCollapse(true)}>›</button></div></header><div className="artifact-preview-body">{layoutError ? <div className="preview-notice" role="status">{layoutError}</div> : null}<Suspense fallback={<div className="preview-empty">正在加载预览组件…</div>}>{body}</Suspense></div><footer>{supportsLayout && !isPresentation ? <div className="preview-mode-actions"><button className={viewMode === 'quick' ? 'secondary active' : 'secondary'} onClick={() => setViewMode('quick')}>快速预览</button><button className={viewMode === 'layout' ? 'secondary active' : 'secondary'} disabled={layoutLoading} onClick={() => layoutPreview ? setViewMode('layout') : void generateLayoutPreview()}>{layoutLoading ? '正在转换…' : layoutPreview ? '版式预览' : '生成版式预览'}</button></div> : null}<button onClick={() => void window.agentDesktop.openArtifact(artifact)}>使用默认应用打开</button></footer></aside>;
}

function PanelSizeIcon({ contracted }: { contracted: boolean }) {
  const iconPath = contracted ? 'M3 8h5V3M21 8h-5V3M3 16h5v5M21 16h-5v5' : 'M8 3H3v5M16 3h5v5M8 21H3v-5M16 21h5v-5';
  return <svg className="ui-icon" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d={iconPath} /></svg>;
}

function PreviewContent({ preview }: { preview: ArtifactPreview }) {
  if (preview.kind === 'markdown') return <div className="markdown-content preview-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{preview.content}</ReactMarkdown></div>;
  if (preview.kind === 'html') return <div className="html-preview-frame"><iframe className="html-preview" title={preview.fileName} sandbox="allow-scripts" src={preview.url} /></div>;
  if (preview.kind === 'word') return <WordPreview contentBase64={preview.contentBase64} />;
  if (preview.kind === 'excel') return <ExcelPreview contentBase64={preview.contentBase64} />;
  if (preview.kind === 'presentation') return <div className="preview-empty">正在准备幻灯片版式预览…</div>;
  return <div className="preview-empty">{preview.message}</div>;
}
