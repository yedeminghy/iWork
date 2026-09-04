import assert from 'node:assert/strict';
import { deflateRawSync } from 'node:zlib';
import { previewArtifact } from '../desktop-shell/dist/artifact-preview.js';

const markdown=previewArtifact('report.md',Buffer.from('# 标题\n\n正文','utf8'));assert.equal(markdown.kind,'markdown');assert.match(markdown.content,/正文/);
const html=previewArtifact('report.html',Buffer.from('<h1>HTML</h1>','utf8'));assert.equal(html.kind,'html');assert.match(html.content,/HTML/);

const docx=zip({'word/document.xml':'<w:document xmlns:w="w"><w:body><w:p><w:r><w:t>Word 预览</w:t></w:r></w:p></w:body></w:document>'});
const word=previewArtifact('report.docx',docx);assert.equal(word.kind,'word');assert.deepEqual(Buffer.from(word.contentBase64,'base64'),docx);

const xlsx=zip({
  'xl/workbook.xml':'<workbook><sheets><sheet name="净值序列" sheetId="1"/></sheets></workbook>',
  'xl/sharedStrings.xml':'<sst><si><t>日期</t></si><si><t>单位净值</t></si></sst>',
  'xl/worksheets/sheet1.xml':'<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>2025-01-02</t></is></c><c r="B2"><v>1.0234</v></c></row></sheetData></worksheet>'
});
const excel=previewArtifact('report.xlsx',xlsx);assert.equal(excel.kind,'excel');assert.deepEqual(Buffer.from(excel.contentBase64,'base64'),xlsx);
const pptx=zip({'ppt/presentation.xml':'<p:presentation xmlns:p="p"><p:sldIdLst/></p:presentation>'});
const presentation=previewArtifact('report.pptx',pptx);assert.equal(presentation.kind,'presentation');
assert.throws(()=>previewArtifact('missing.pptx',zip({'[Content_Types].xml':'<Types/> '})),/ARTIFACT_PREVIEW_INVALID/);
assert.throws(()=>previewArtifact('escape.docx',zip({'word/document.xml':'<w:document/>','../escape.xml':'<x/>'})),/ARTIFACT_PREVIEW_INVALID/);
assert.throws(()=>previewArtifact('entity.docx',zip({'word/document.xml':'<!DOCTYPE x [<!ENTITY y "z">]><w:document>&y;</w:document>'})),/ARTIFACT_PREVIEW_INVALID_XML/);
assert.throws(()=>previewArtifact('missing.docx',zip({'content.xml':'<x/>'})),/ARTIFACT_PREVIEW_INVALID/);
assert.throws(()=>previewArtifact('large.md',Buffer.alloc(2*1024*1024+1)),/ARTIFACT_PREVIEW_TOO_LARGE/);
assert.throws(()=>previewArtifact('ratio.docx',zip({'word/document.xml':{data:`<w:document>${' '.repeat(1_100_000)}</w:document>`,deflate:true}})),/ARTIFACT_PREVIEW_TOO_LARGE/);
assert.throws(()=>previewArtifact('complex.docx',zip({'word/document.xml':`<w:document>${'<x/>'.repeat(1_000_001)}</w:document>`})),/ARTIFACT_PREVIEW_TOO_COMPLEX/);
const manyEntries={'word/document.xml':'<w:document/>'};for(let index=0;index<4096;index++)manyEntries[`word/media/${index}.bin`]='';
assert.throws(()=>previewArtifact('entries.docx',zip(manyEntries)),/ARTIFACT_PREVIEW_INVALID/);
assert.throws(()=>previewArtifact('total.docx',zip({
  'word/document.xml':'<w:document/>',
  'word/media/1.bin':{data:Buffer.alloc(64*1024),flags:8,uncompressedSize:30*1024*1024},
  'word/media/2.bin':{data:Buffer.alloc(64*1024),flags:8,uncompressedSize:30*1024*1024},
  'word/media/3.bin':{data:Buffer.alloc(64*1024),flags:8,uncompressedSize:30*1024*1024},
  'word/media/4.bin':{data:Buffer.alloc(64*1024),flags:8,uncompressedSize:30*1024*1024},
  'word/media/5.bin':{data:Buffer.alloc(64*1024),flags:8,uncompressedSize:30*1024*1024}
})),/ARTIFACT_PREVIEW_TOO_LARGE/);
assert.throws(()=>previewArtifact('encrypted.docx',zip({'word/document.xml':{data:'<w:document/>',flags:1}})),/ARTIFACT_PREVIEW_INVALID/);
assert.throws(()=>previewArtifact('damaged.docx',docx.subarray(0,docx.length-8)),/ARTIFACT_PREVIEW_INVALID/);
console.log('Artifact preview verification passed');

function zip(entries){
  const locals=[];const centrals=[];let offset=0;
  for(const [name,value] of Object.entries(entries)){const descriptor=value&&typeof value==='object'&&!Buffer.isBuffer(value)?value:{data:value};const nameBytes=Buffer.from(name);const source=Buffer.from(descriptor.data);const data=descriptor.deflate?deflateRawSync(source):source;const method=descriptor.deflate?8:0;const flags=descriptor.flags??0;const uncompressedSize=descriptor.uncompressedSize??source.length;const local=Buffer.alloc(30);local.writeUInt32LE(0x04034b50,0);local.writeUInt16LE(20,4);local.writeUInt16LE(flags,6);local.writeUInt16LE(method,8);local.writeUInt32LE(0,10);local.writeUInt32LE(0,14);local.writeUInt32LE((flags&8)===0?data.length:0,18);local.writeUInt32LE((flags&8)===0?uncompressedSize:0,22);local.writeUInt16LE(nameBytes.length,26);local.writeUInt16LE(0,28);locals.push(local,nameBytes,data);
    const central=Buffer.alloc(46);central.writeUInt32LE(0x02014b50,0);central.writeUInt16LE(20,4);central.writeUInt16LE(20,6);central.writeUInt16LE(flags,8);central.writeUInt16LE(method,10);central.writeUInt32LE(0,12);central.writeUInt32LE(0,16);central.writeUInt32LE(data.length,20);central.writeUInt32LE(uncompressedSize,24);central.writeUInt16LE(nameBytes.length,28);central.writeUInt32LE(0,30);central.writeUInt32LE(0,34);central.writeUInt32LE(0,38);central.writeUInt32LE(offset,42);centrals.push(central,nameBytes);offset+=local.length+nameBytes.length+data.length;}
  const centralBytes=Buffer.concat(centrals);const end=Buffer.alloc(22);end.writeUInt32LE(0x06054b50,0);end.writeUInt32LE(0,4);end.writeUInt16LE(Object.keys(entries).length,8);end.writeUInt16LE(Object.keys(entries).length,10);end.writeUInt32LE(centralBytes.length,12);end.writeUInt32LE(offset,16);return Buffer.concat([...locals,centralBytes,end]);
}
