import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { RuntimeLogService } from '../desktop-shell/dist/runtime-log.js';
import { SettingsStore } from '../desktop-shell/dist/settings-store.js';

const temporary=mkdtempSync(path.join(os.tmpdir(),'agent-runtime-log-'));
try{
  const appHome=path.join(temporary,'app-home');
  mkdirSync(path.join(appHome,'config'),{recursive:true});
  writeFileSync(path.join(appHome,'config','settings.json'),JSON.stringify({schemaVersion:1,defaultWorkspacePath:path.join(appHome,'workspace'),maxIterations:12}));
  const migrated=new SettingsStore(appHome).load();
  assert.equal(migrated.schemaVersion,2);
  assert.equal(migrated.runtimeLogs.enabled,false);
  assert.equal(migrated.runtimeLogs.directory,path.join(appHome,'logs'));

  const directory=path.join(temporary,'logs');
  const service=new RuntimeLogService({enabled:true,main:true,host:false,renderer:true,directory},{maxFileBytes:240,maxFiles:3});
  service.write('main','ERROR','authorization: Bearer private-value apiKey=secret-value C:\\Users\\person\\private\\file.txt');
  const first=readFileSync(path.join(directory,'main.log'),'utf8');
  assert.doesNotMatch(first,/private-value|secret-value|person/);
  assert.match(first,/\[REDACTED\]|\[PRIVATE_PATH\]/);

  service.write('host','INFO','disabled source');
  assert.equal(existsSync(path.join(directory,'host.log')),false);
  for(let index=0;index<20;index++)service.write('main','INFO',`rotation-${index}-${'x'.repeat(50)}`);
  assert.equal(existsSync(path.join(directory,'main.1.log')),true);
  assert.equal(existsSync(path.join(directory,'main.2.log')),true);
  assert.equal(existsSync(path.join(directory,'main.3.log')),false);

  service.configure({enabled:false,main:true,host:true,renderer:true,directory});
  const before=readFileSync(path.join(directory,'main.log'),'utf8');
  service.write('main','ERROR','must not be written');
  assert.equal(readFileSync(path.join(directory,'main.log'),'utf8'),before);
  process.stdout.write('Runtime logging verification passed.\n');
}finally{
  rmSync(temporary,{recursive:true,force:true});
}
