import * as XLSX from 'xlsx';
import type { ExcelCellPreview, ExcelSheetPreview, ExcelWorkbookPreview } from './ExcelPreview';

const MAX_SHEETS = 32;
const MAX_ROWS = 300;
const MAX_COLUMNS = 50;

self.onmessage = (event: MessageEvent<{ bytes: ArrayBuffer }>) => {
  try {
    const workbook = XLSX.read(event.data.bytes, { type: 'array', cellStyles: true, cellDates: true, sheetRows: MAX_ROWS + 1 });
    const result: ExcelWorkbookPreview = { sheets: workbook.SheetNames.slice(0, MAX_SHEETS).map(name => previewSheet(name, workbook.Sheets[name])) };
    self.postMessage({ workbook: result });
  } catch {
    self.postMessage({ error: 'ARTIFACT_XLSX_INVALID' });
  }
};

function previewSheet(name: string, sheet: XLSX.WorkSheet): ExcelSheetPreview {
  const decoded = sheet['!ref'] ? XLSX.utils.decode_range(sheet['!ref']) : { s: { r: 0, c: 0 }, e: { r: 0, c: 0 } };
  const rowCount = Math.min(MAX_ROWS, decoded.e.r + 1);
  const columnCount = Math.min(MAX_COLUMNS, decoded.e.c + 1);
  const rows: ExcelCellPreview[][] = [];
  for (let row = 0; row < rowCount; row++) {
    const cells: ExcelCellPreview[] = [];
    for (let column = 0; column < columnCount; column++) {
      const cell = sheet[XLSX.utils.encode_cell({ r: row, c: column })] as (XLSX.CellObject & { s?: unknown }) | undefined;
      cells.push(cell ? { text: displayValue(cell), formula: cell.f, style: previewStyle(cell.s) } : { text: '' });
    }
    rows.push(cells);
  }
  const merges = (sheet['!merges'] ?? []).filter(merge => merge.s.r < MAX_ROWS && merge.s.c < MAX_COLUMNS).map(merge => ({ startRow: merge.s.r, startColumn: merge.s.c, endRow: Math.min(merge.e.r, MAX_ROWS - 1), endColumn: Math.min(merge.e.c, MAX_COLUMNS - 1) }));
  const columns = sheet['!cols'] ?? [];
  const sheetRows = sheet['!rows'] ?? [];
  return { name, rows, merges, columnWidths: Array.from({ length: columnCount }, (_, index) => clamp(Number(columns[index]?.wpx ?? (columns[index]?.wch ? columns[index]!.wch! * 7.5 : 96)), 56, 320)), rowHeights: Array.from({ length: rowCount }, (_, index) => clamp(Number(sheetRows[index]?.hpx ?? 0), 0, 160)), truncated: decoded.e.r >= MAX_ROWS || decoded.e.c >= MAX_COLUMNS || Boolean(sheet['!fullref'] && sheet['!fullref'] !== sheet['!ref']) };
}

function displayValue(cell: XLSX.CellObject): string {
  if (cell.w !== undefined) return String(cell.w);
  if (cell.v instanceof Date) return cell.v.toLocaleDateString();
  return cell.v === undefined || cell.v === null ? '' : String(cell.v);
}

function previewStyle(value: unknown): ExcelCellPreview['style'] {
  if (!value || typeof value !== 'object') return undefined;
  const style = value as { font?: { bold?: boolean; italic?: boolean; color?: { rgb?: string } }; fill?: { fgColor?: { rgb?: string } }; alignment?: { horizontal?: string } };
  const color = safeColor(style.font?.color?.rgb);
  const fill = safeColor(style.fill?.fgColor?.rgb);
  const horizontal = style.alignment?.horizontal;
  const align = horizontal === 'center' || horizontal === 'right' || horizontal === 'left' ? horizontal : undefined;
  return style.font?.bold || style.font?.italic || color || fill || align ? { bold: style.font?.bold, italic: style.font?.italic, color, fill, align } : undefined;
}

function safeColor(value?: string): string | undefined { if (!value) return undefined; const normalized = value.length === 8 ? value.slice(2) : value; return /^[0-9a-f]{6}$/i.test(normalized) ? normalized : undefined; }
function clamp(value: number, minimum: number, maximum: number): number { return Number.isFinite(value) ? Math.min(maximum, Math.max(minimum, value)) : minimum; }
