import { useEffect, useMemo, useState } from 'react';

export type ExcelCellPreview = { text: string; formula?: string; style?: { bold?: boolean; italic?: boolean; color?: string; fill?: string; align?: 'left' | 'center' | 'right' } };
export type ExcelMergePreview = { startRow: number; startColumn: number; endRow: number; endColumn: number };
export type ExcelSheetPreview = { name: string; rows: ExcelCellPreview[][]; merges: ExcelMergePreview[]; columnWidths: number[]; rowHeights: number[]; truncated: boolean };
export type ExcelWorkbookPreview = { sheets: ExcelSheetPreview[] };

export default function ExcelPreview({ contentBase64 }: { contentBase64: string }) {
  const [workbook, setWorkbook] = useState<ExcelWorkbookPreview>();
  const [error, setError] = useState<string>();
  const [activeSheet, setActiveSheet] = useState(0);
  useEffect(() => {
    const worker = new Worker(new URL('./xlsx-preview.worker.ts', import.meta.url), { type: 'module' });
    const bytes = decodeBase64(contentBase64);
    const timer = window.setTimeout(() => { worker.terminate(); setError('Excel 快速预览解析超时。'); }, 15_000);
    worker.onmessage = (event: MessageEvent<{ workbook?: ExcelWorkbookPreview; error?: string }>) => {
      window.clearTimeout(timer);
      if (event.data.workbook) { setWorkbook(event.data.workbook); setActiveSheet(0); }
      else setError('Excel 快速预览解析失败。');
      worker.terminate();
    };
    worker.onerror = () => { window.clearTimeout(timer); setError('Excel 快速预览解析失败。'); worker.terminate(); };
    worker.postMessage({ bytes: bytes.buffer }, [bytes.buffer]);
    return () => { window.clearTimeout(timer); worker.terminate(); };
  }, [contentBase64]);
  if (error) return <div className="preview-empty">{error}</div>;
  if (!workbook) return <div className="preview-empty">正在解析工作簿…</div>;
  const sheet = workbook.sheets[activeSheet];
  if (!sheet) return <div className="preview-empty">工作簿没有可显示的工作表。</div>;
  return <div className="excel-preview"><div className="sheet-tabs" role="tablist">{workbook.sheets.map((item, index) => <button role="tab" aria-selected={index === activeSheet} className={index === activeSheet ? 'active' : ''} key={`${index}:${item.name}`} onClick={() => setActiveSheet(index)}>{item.name}</button>)}</div>{sheet.truncated ? <div className="sheet-limit-notice">快速预览最多显示 300 行、50 列。</div> : null}<ExcelTable sheet={sheet} /></div>;
}

function ExcelTable({ sheet }: { sheet: ExcelSheetPreview }) {
  const mergeMap = useMemo(() => buildMergeMap(sheet.merges), [sheet.merges]);
  const columnCount = Math.max(1, ...sheet.rows.map(row => row.length));
  return <table><colgroup><col className="row-number-column" />{Array.from({ length: columnCount }, (_, index) => <col key={index} style={{ width: sheet.columnWidths[index] || 96 }} />)}</colgroup><thead><tr><th aria-hidden="true" />{Array.from({ length: columnCount }, (_, index) => <th key={index}>{columnLabel(index)}</th>)}</tr></thead><tbody>{sheet.rows.map((row, rowIndex) => <tr key={rowIndex} style={sheet.rowHeights[rowIndex] ? { height: sheet.rowHeights[rowIndex] } : undefined}><th>{rowIndex + 1}</th>{Array.from({ length: columnCount }, (_, columnIndex) => {
    const merge = mergeMap.get(`${rowIndex}:${columnIndex}`);
    if (merge?.covered) return null;
    const cell = row[columnIndex] ?? { text: '' };
    return <td key={columnIndex} title={cell.formula ? `=${cell.formula}` : cell.text} colSpan={merge?.colSpan} rowSpan={merge?.rowSpan} style={cellStyle(cell)}>{cell.text}</td>;
  })}</tr>)}</tbody></table>;
}

function buildMergeMap(merges: ExcelMergePreview[]) {
  const result = new Map<string, { covered?: boolean; colSpan?: number; rowSpan?: number }>();
  for (const merge of merges) for (let row = merge.startRow; row <= merge.endRow; row++) for (let column = merge.startColumn; column <= merge.endColumn; column++) result.set(`${row}:${column}`, row === merge.startRow && column === merge.startColumn ? { colSpan: merge.endColumn - merge.startColumn + 1, rowSpan: merge.endRow - merge.startRow + 1 } : { covered: true });
  return result;
}

function cellStyle(cell: ExcelCellPreview): React.CSSProperties | undefined {
  if (!cell.style) return undefined;
  return { fontWeight: cell.style.bold ? 700 : undefined, fontStyle: cell.style.italic ? 'italic' : undefined, color: cell.style.color ? `#${cell.style.color}` : undefined, backgroundColor: cell.style.fill ? `#${cell.style.fill}` : undefined, textAlign: cell.style.align };
}

function columnLabel(index: number): string { let value = index + 1; let result = ''; while (value > 0) { value--; result = String.fromCharCode(65 + value % 26) + result; value = Math.floor(value / 26); } return result; }
function decodeBase64(value: string): Uint8Array { const binary = atob(value); const bytes = new Uint8Array(binary.length); for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index); return bytes; }
