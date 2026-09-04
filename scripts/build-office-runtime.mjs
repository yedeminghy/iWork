import { copyFileSync, rmSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { build } from 'esbuild';

const runtimeRoot = resolve('desktop-shell/dist/runtime');
const docxRoot = resolve(runtimeRoot, 'docx');
const pptxRoot = resolve(runtimeRoot, 'pptxgenjs');
const echartsRoot = resolve(runtimeRoot, 'echarts');
const tailwindRoot = resolve(runtimeRoot, 'tailwind');
const lucideRoot = resolve(runtimeRoot, 'lucide');

rmSync(runtimeRoot, { recursive: true, force: true });
mkdirSync(docxRoot, { recursive: true });
mkdirSync(pptxRoot, { recursive: true });
mkdirSync(echartsRoot, { recursive: true });
mkdirSync(tailwindRoot, { recursive: true });
mkdirSync(resolve(tailwindRoot, 'css'), { recursive: true });
mkdirSync(lucideRoot, { recursive: true });

await build({
  entryPoints: [resolve('node_modules/docx/dist/index.cjs')],
  outfile: resolve(docxRoot, 'index.cjs'),
  bundle: true,
  platform: 'node',
  format: 'cjs',
  logLevel: 'info'
});

writeFileSync(
  resolve(docxRoot, 'package.json'),
  `${JSON.stringify({ name: 'docx', private: true, type: 'commonjs', main: 'index.cjs' }, null, 2)}\n`,
  'utf8'
);

await build({
  entryPoints: [resolve('node_modules/pptxgenjs/dist/pptxgen.cjs.js')],
  outfile: resolve(pptxRoot, 'index.cjs'),
  bundle: true,
  platform: 'node',
  format: 'cjs',
  logLevel: 'info'
});

writeFileSync(
  resolve(pptxRoot, 'package.json'),
  `${JSON.stringify({ name: 'pptxgenjs', private: true, type: 'commonjs', main: 'index.cjs' }, null, 2)}\n`,
  'utf8'
);

copyFileSync(resolve('node_modules/echarts/dist/echarts.min.js'), resolve(echartsRoot, 'echarts.min.js'));
copyFileSync(resolve('node_modules/lucide/dist/umd/lucide.min.js'), resolve(lucideRoot, 'lucide.min.js'));
copyFileSync(resolve('node_modules/tailwindcss/lib/css/preflight.css'), resolve(tailwindRoot, 'css', 'preflight.css'));

await build({
  entryPoints: [resolve('scripts/tailwind-preview-runtime.cjs')],
  outfile: resolve(tailwindRoot, 'index.cjs'),
  bundle: true,
  platform: 'node',
  format: 'cjs',
  logLevel: 'info'
});
