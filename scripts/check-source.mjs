import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {execFileSync} from 'node:child_process';
const html=fs.readFileSync('www/index.html','utf8');
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'life-check-'));
try {
  let i=0;
  for(const m of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script\s*>/gi)) {
    if(!m[1].trim()) continue;
    const file=path.join(tmp,`script-${i++}.js`);fs.writeFileSync(file,m[1]);execFileSync(process.execPath,['--check',file],{stdio:'inherit'});
  }
  execFileSync(process.execPath,['--check','scripts/patch-android.mjs'],{stdio:'inherit'});
  console.log('JavaScript syntax: PASS');
} finally {fs.rmSync(tmp,{recursive:true,force:true});}
