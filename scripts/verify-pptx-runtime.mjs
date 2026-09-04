import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { previewArtifact } from '../desktop-shell/dist/artifact-preview.js';
import { ArtifactLayoutConverter } from '../desktop-shell/dist/artifact-layout-preview.js';
import { probeNodeModule } from '../desktop-shell/dist/host-supervisor.js';

const runtimeRoot=path.resolve('desktop-shell/dist/runtime');
const require=createRequire(path.join(runtimeRoot,'agent-desktop-runtime-test.cjs'));
assert.equal(probeNodeModule(runtimeRoot,'pptxgenjs'),true,'managed pptxgenjs preflight failed');
const managedPackage=path.join(runtimeRoot,'pptxgenjs');
const resolved=require.resolve(managedPackage);
assert.equal(path.relative(runtimeRoot,resolved).startsWith('..'),false,'pptxgenjs resolved outside the managed runtime');
const PptxGenJS=require(managedPackage);
assert.equal(typeof PptxGenJS,'function','bundled pptxgenjs entrypoint is unavailable');

const temporary=await mkdtemp(path.join(os.tmpdir(),'agent-desktop-pptx-test-'));
const output=path.join(temporary,'single-slide.pptx');
const converter=new ArtifactLayoutConverter();
try{
  const deck=new PptxGenJS();
  deck.layout='LAYOUT_WIDE';
  deck.author='Agent Desktop runtime verification';
  deck.subject='PPTX runtime and preview smoke test';
  const slide=deck.addSlide();
  slide.background={color:'F4F7F8'};
  slide.addText('PPTX 预览验证',{x:0.8,y:0.8,w:5.8,h:0.7,fontFace:'Arial',fontSize:28,bold:true,color:'174B45',margin:0});
  slide.addText('生成运行时、OOXML 安全校验和 PDF 版式预览均可用。',{x:0.8,y:1.8,w:8.8,h:0.6,fontFace:'Arial',fontSize:18,color:'425B57',margin:0});
  await deck.writeFile({fileName:output});

  const bytes=await readFile(output);
  assert.ok(bytes.length>1_000,'generated presentation is unexpectedly small');
  assert.equal(previewArtifact(path.basename(output),bytes).kind,'presentation');

  const layout=await converter.generate(output);
  const pdf=await readFile(layout.pdfPath);
  assert.equal(pdf.subarray(0,5).toString('ascii'),'%PDF-');
  assert.ok((await stat(layout.pdfPath)).size>5,'layout preview PDF is empty');
  await rm(layout.tempRoot,{recursive:true,force:true});
  process.stdout.write('PPTX runtime and preview verification passed\n');
}finally{
  converter.dispose();
  await rm(temporary,{recursive:true,force:true});
}
