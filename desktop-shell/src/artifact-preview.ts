import { inflateRawSync } from 'node:zlib';

const MAX_TEXT_BYTES = 2 * 1024 * 1024;
const MAX_ARCHIVE_ENTRIES = 4_096;
const MAX_ENTRY_BYTES = 32 * 1024 * 1024;
const MAX_XML_ENTRY_BYTES = 16 * 1024 * 1024;
const MAX_TOTAL_UNCOMPRESSED_BYTES = 128 * 1024 * 1024;
const MAX_XML_TAGS = 1_000_000;
const MAX_COMPRESSION_RATIO = 500;

export type ArtifactPreview =
  | { kind: 'markdown'; content: string }
  | { kind: 'html'; content: string }
  | { kind: 'word'; contentBase64: string }
  | { kind: 'excel'; contentBase64: string }
  | { kind: 'presentation' }
  | { kind: 'unsupported'; message: string };

export function previewArtifact(fileName: string, bytes: Buffer): ArtifactPreview {
  const lower = fileName.toLocaleLowerCase();
  if (lower.endsWith('.md') || lower.endsWith('.markdown')) return { kind: 'markdown', content: boundedText(bytes) };
  if (lower.endsWith('.html') || lower.endsWith('.htm')) return { kind: 'html', content: boundedText(bytes) };
  if (lower.endsWith('.docx')) { validateOfficeArchive(bytes, 'word/document.xml'); return { kind: 'word', contentBase64: bytes.toString('base64') }; }
  if (lower.endsWith('.xlsx')) { validateOfficeArchive(bytes, 'xl/workbook.xml'); return { kind: 'excel', contentBase64: bytes.toString('base64') }; }
  if (lower.endsWith('.pptx')) { validateOfficeArchive(bytes, 'ppt/presentation.xml'); return { kind: 'presentation' }; }
  if (lower.endsWith('.xls')) return { kind: 'unsupported', message: '旧版 XLS 暂不支持快速预览，可生成版式预览或使用默认应用打开。' };
  return { kind: 'unsupported', message: '此文件类型暂不支持内嵌预览。' };
}

function boundedText(bytes: Buffer): string {
  if (bytes.length > MAX_TEXT_BYTES) throw new Error('ARTIFACT_PREVIEW_TOO_LARGE');
  return bytes.toString('utf8');
}

type ZipEntry = { name: string; method: number; compressedSize: number; uncompressedSize: number; dataStart: number };

function validateOfficeArchive(zip: Buffer, requiredEntry: string): void {
  const entries = readCentralDirectory(zip);
  if (!entries.some(entry => entry.name === requiredEntry)) throw new Error('ARTIFACT_PREVIEW_INVALID');
  let xmlTags = 0;
  for (const entry of entries) {
    if (!entry.name.endsWith('.xml') && !entry.name.endsWith('.rels')) continue;
    if (entry.uncompressedSize > MAX_XML_ENTRY_BYTES) throw new Error('ARTIFACT_PREVIEW_TOO_COMPLEX');
    const compressed = zip.subarray(entry.dataStart, entry.dataStart + entry.compressedSize);
    const value = entry.method === 0 ? compressed : inflateRawSync(compressed, { maxOutputLength: MAX_XML_ENTRY_BYTES });
    if (value.length !== entry.uncompressedSize) throw new Error('ARTIFACT_PREVIEW_INVALID');
    const xml = value.toString('utf8');
    if (/<!DOCTYPE|<!ENTITY/i.test(xml)) throw new Error('ARTIFACT_PREVIEW_INVALID_XML');
    for (let index = 0; index < xml.length; index++) if (xml.charCodeAt(index) === 60) xmlTags++;
    if (xmlTags > MAX_XML_TAGS) throw new Error('ARTIFACT_PREVIEW_TOO_COMPLEX');
  }
}

function readCentralDirectory(zip: Buffer): ZipEntry[] {
  const eocd = findEocd(zip);
  const diskNumber = zip.readUInt16LE(eocd + 4);
  const centralDisk = zip.readUInt16LE(eocd + 6);
  const entriesOnDisk = zip.readUInt16LE(eocd + 8);
  const entryCount = zip.readUInt16LE(eocd + 10);
  const centralSize = zip.readUInt32LE(eocd + 12);
  let offset = zip.readUInt32LE(eocd + 16);
  if (diskNumber !== 0 || centralDisk !== 0 || entriesOnDisk !== entryCount || entryCount === 0 || entryCount > MAX_ARCHIVE_ENTRIES || entryCount === 0xffff || offset === 0xffffffff || centralSize === 0xffffffff || offset + centralSize > eocd) throw new Error('ARTIFACT_PREVIEW_INVALID');
  const entries: ZipEntry[] = [];
  const names = new Set<string>();
  const localOffsets = new Set<number>();
  let totalUncompressed = 0;
  for (let index = 0; index < entryCount; index++) {
    if (offset + 46 > eocd || zip.readUInt32LE(offset) !== 0x02014b50) throw new Error('ARTIFACT_PREVIEW_INVALID');
    const flags = zip.readUInt16LE(offset + 8);
    const method = zip.readUInt16LE(offset + 10);
    const compressedSize = zip.readUInt32LE(offset + 20);
    const uncompressedSize = zip.readUInt32LE(offset + 24);
    const nameLength = zip.readUInt16LE(offset + 28);
    const extraLength = zip.readUInt16LE(offset + 30);
    const commentLength = zip.readUInt16LE(offset + 32);
    const localOffset = zip.readUInt32LE(offset + 42);
    const nextOffset = offset + 46 + nameLength + extraLength + commentLength;
    if (nextOffset > eocd || localOffset === 0xffffffff || (flags & 1) !== 0 || (method !== 0 && method !== 8)) throw new Error('ARTIFACT_PREVIEW_INVALID');
    const name = zip.subarray(offset + 46, offset + 46 + nameLength).toString('utf8').replaceAll('\\', '/');
    if (!name || name.startsWith('/') || /^[a-z]:/i.test(name) || name.includes('\0') || name.split('/').some(segment => segment === '..' || segment === '.') || names.has(name) || localOffsets.has(localOffset)) throw new Error('ARTIFACT_PREVIEW_INVALID');
    names.add(name);
    localOffsets.add(localOffset);
    if (uncompressedSize > MAX_ENTRY_BYTES || compressedSize > zip.length || (compressedSize === 0 && uncompressedSize > 0) || (compressedSize > 0 && uncompressedSize / compressedSize > MAX_COMPRESSION_RATIO)) throw new Error('ARTIFACT_PREVIEW_TOO_LARGE');
    totalUncompressed += uncompressedSize;
    if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) throw new Error('ARTIFACT_PREVIEW_TOO_LARGE');
    if (localOffset + 30 > zip.length || zip.readUInt32LE(localOffset) !== 0x04034b50) throw new Error('ARTIFACT_PREVIEW_INVALID');
    const localFlags = zip.readUInt16LE(localOffset + 6);
    const localMethod = zip.readUInt16LE(localOffset + 8);
    const localCompressedSize = zip.readUInt32LE(localOffset + 18);
    const localUncompressedSize = zip.readUInt32LE(localOffset + 22);
    const localNameLength = zip.readUInt16LE(localOffset + 26);
    const localExtraLength = zip.readUInt16LE(localOffset + 28);
    const dataStart = localOffset + 30 + localNameLength + localExtraLength;
    const localName = zip.subarray(localOffset + 30, localOffset + 30 + localNameLength).toString('utf8').replaceAll('\\', '/');
    if (localName !== name || localFlags !== flags || localMethod !== method || ((flags & 8) === 0 && (localCompressedSize !== compressedSize || localUncompressedSize !== uncompressedSize)) || dataStart + compressedSize > offset) throw new Error('ARTIFACT_PREVIEW_INVALID');
    entries.push({ name, method, compressedSize, uncompressedSize, dataStart });
    offset = nextOffset;
  }
  if (offset !== eocd || offset - zip.readUInt32LE(eocd + 16) !== centralSize) throw new Error('ARTIFACT_PREVIEW_INVALID');
  return entries;
}

function findEocd(zip: Buffer): number {
  if (zip.length < 22) throw new Error('ARTIFACT_PREVIEW_INVALID');
  for (let index = zip.length - 22, lower = Math.max(0, zip.length - 65_557); index >= lower; index--) {
    if (zip.readUInt32LE(index) === 0x06054b50 && index + 22 + zip.readUInt16LE(index + 20) === zip.length) return index;
  }
  throw new Error('ARTIFACT_PREVIEW_INVALID');
}
