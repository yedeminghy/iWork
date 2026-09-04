import { useEffect, useRef, useState } from 'react';
import { renderAsync } from 'docx-preview';

export default function WordPreview({ contentBase64 }: { contentBase64: string }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string>();
  useEffect(() => {
    let active = true;
    const container = containerRef.current;
    if (!container) return;
    container.replaceChildren();
    setError(undefined);
    const bytes = decodeBase64(contentBase64);
    void renderAsync(bytes.buffer as ArrayBuffer, container, container, {
      breakPages: true, ignoreWidth: false, ignoreHeight: false, ignoreFonts: false, useBase64URL: true,
      renderHeaders: true, renderFooters: true, renderFootnotes: true, renderEndnotes: true,
      renderComments: false, renderChanges: false, renderAltChunks: false, experimental: true
    }).catch(() => { if (active) setError('Word 快速预览渲染失败。'); });
    const blockNavigation = (event: MouseEvent) => { if ((event.target as Element | null)?.closest('a')) event.preventDefault(); };
    container.addEventListener('click', blockNavigation);
    return () => { active = false; container.removeEventListener('click', blockNavigation); container.replaceChildren(); };
  }, [contentBase64]);
  return error ? <div className="preview-empty">{error}</div> : <div className="word-preview" ref={containerRef} />;
}

function decodeBase64(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return bytes;
}
