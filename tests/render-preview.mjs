import fs from 'node:fs';import path from 'node:path';import {execFileSync} from 'node:child_process';import os from 'node:os';
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'life-ui-')),out=path.resolve('android/app/build/reports/ui');fs.mkdirSync(out,{recursive:true});
const html=fs.readFileSync('www/index.html','utf8');
for(const page of ['home','archive','prepare','settings']){
 const fixture=`<script>
state.all=Array.from({length:8},(_,i)=>({name:'LBB_'+ymd(Date.now()).replaceAll('-','')+'_12000'+i+'.m4a',uri:'content://sample/'+i,size:800000,created:Date.now(),durMs:270000}));
state.snapshot={date:state.date,cutoff:Date.now(),token:'preview',files:state.all};rebuildBatches();show('${page}');renderHome();
</script>`;
 const file=path.join(dir,page+'.html');fs.writeFileSync(file,html.replace('</body>',fixture+'</body>'));
 execFileSync('google-chrome',['--headless','--no-sandbox','--disable-gpu','--disable-dev-shm-usage','--hide-scrollbars','--window-size=390,844','--virtual-time-budget=1400','--screenshot='+path.join(out,page+'.png'),'file://'+file],{stdio:'pipe',timeout:60000});
 console.log('Rendered',page);
}
fs.rmSync(dir,{recursive:true,force:true});
