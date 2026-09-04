import { useEffect, useRef, useState } from 'react';
import { getDocument, GlobalWorkerOptions, type PDFDocumentLoadingTask, type PDFDocumentProxy, type RenderTask } from 'pdfjs-dist';
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';

GlobalWorkerOptions.workerSrc = pdfWorkerUrl;
const MAX_PAGES = 200;

export default function PdfPreview({ url }: { url: string }) {
  const [document, setDocument] = useState<PDFDocumentProxy>();
  const [error, setError] = useState<string>();
  useEffect(() => {
    setDocument(undefined);
    setError(undefined);
    const controller = new AbortController();
    let task: PDFDocumentLoadingTask | undefined;
    let active = true;
    void fetch(url, { credentials: 'omit', signal: controller.signal }).then(async response => {
      const declaredSize = Number(response.headers.get('content-length') ?? 0);
      if (!response.ok || declaredSize > 80 * 1024 * 1024) throw new Error('ARTIFACT_PDF_INVALID');
      const data = await response.arrayBuffer();
      if (!active || data.byteLength > 80 * 1024 * 1024) return;
      task = getDocument({ data, enableXfa: false, maxImageSize: 16_000_000, disableAutoFetch: true, disableStream: true });
      return task.promise;
    }).then(value => { if (active && value) setDocument(value); }).catch(reason => { if (active && !(reason instanceof DOMException && reason.name === 'AbortError')) setError('PDF 版式预览加载失败。'); });
    return () => { active = false; controller.abort(); void task?.destroy(); };
  }, [url]);
  if (error) return <div className="preview-empty">{error}</div>;
  if (!document) return <div className="preview-empty">正在加载版式预览…</div>;
  const pageCount = Math.min(document.numPages, MAX_PAGES);
  return <div className="pdf-preview">{Array.from({ length: pageCount }, (_, index) => <PdfPage document={document} pageNumber={index + 1} key={index + 1} />)}{document.numPages > MAX_PAGES ? <div className="preview-notice">版式预览最多显示前 {MAX_PAGES} 页。</div> : null}</div>;
}

function PdfPage({ document, pageNumber }: { document: PDFDocumentProxy; pageNumber: number }) {
  const hostRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    const observer = new IntersectionObserver(entries => { if (entries.some(entry => entry.isIntersecting)) { setVisible(true); observer.disconnect(); } }, { rootMargin: '800px' });
    observer.observe(host);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (!visible) return;
    let cancelled = false;
    let renderTask: RenderTask | undefined;
    void document.getPage(pageNumber).then(page => {
      if (cancelled || !canvasRef.current) return;
      const viewport = page.getViewport({ scale: Math.min(1.5, window.devicePixelRatio || 1) });
      const canvas = canvasRef.current;
      canvas.width = Math.floor(viewport.width);
      canvas.height = Math.floor(viewport.height);
      canvas.style.aspectRatio = `${viewport.width}/${viewport.height}`;
      const context = canvas.getContext('2d');
      if (!context) return;
      renderTask = page.render({ canvas, canvasContext: context, viewport });
      return renderTask.promise;
    }).catch(() => undefined);
    return () => { cancelled = true; renderTask?.cancel(); };
  }, [document, pageNumber, visible]);
  return <div className="pdf-page" ref={hostRef}><canvas ref={canvasRef} aria-label={`第 ${pageNumber} 页`} /><span>{pageNumber}</span></div>;
}
