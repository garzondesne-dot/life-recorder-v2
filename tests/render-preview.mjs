import fs from 'node:fs';import path from 'node:path';import {execFileSync} from 'node:child_process';import os from 'node:os';import assert from 'node:assert/strict';
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'life-ui-')),out=path.resolve('android/app/build/reports/ui');fs.mkdirSync(out,{recursive:true});
const html=fs.readFileSync('www/index.html','utf8');
const cases=[['home',390,844],['home-hold',390,844],['home-small',320,740],['home-wide',840,900],['home-large',390,940],['archive',390,940],['archive-priorities',390,940],['archive-wide',840,900],['archive-large',390,940],['settings',390,940],['settings-wide',840,900],['settings-large',390,940],['rests',390,940],['prepare',390,940],['guide',390,940],['guide-preferences',320,900],['guide-large',390,940]];
for(const [name,width,height] of cases){const page=name.split('-')[0];
 const fixture=`<script>
state.all=Array.from({length:11},(_,i)=>({name:'LBB_'+ymd(Date.now()).replaceAll('-','')+'_12000'+i+'.m4a',uri:'content://sample/'+i,size:800000,created:Date.now(),durMs:270000}));
cfg.restSchedules=[{name:'취침',start:'22:00',end:'07:00',days:[1,2,3,4,5,6,7],enabled:true},{name:'점심 휴식',start:'12:00',end:'13:00',days:[1,2,3,4,5],enabled:true}];settingsUI();
profile.priorities=['todo','speech'];renderProfile();state.running=true;state.voice=true;state.sessionStart=Date.now()-8316000;state.db=-40;state.levels=Array.from({length:48},(_,i)=>.1+.6*Math.abs(Math.sin(i*.7)));state.snapshot={date:state.date,cutoff:Date.now(),token:'preview',files:state.all};rebuildBatches();show('${page}');if('${page}'==='guide'){guide.step='${name}'==='guide-preferences'?4:0;showGuide();}if('${name}'==='home-hold')setStopProgress(.55);if('${name}'==='archive-priorities')$('priorityDetails').open=true;renderHome();if('${name}'.includes('large')){const sizes=[...document.querySelectorAll('button,input,select,label,span,b,small,time,h1,h2,p,summary,textarea')].map(e=>[e,parseFloat(getComputedStyle(e).fontSize)]);for(const [e,size] of sizes)e.style.fontSize=(size*1.5)+'px';}
setTimeout(()=>{const main=document.querySelector('main');let bad=main.scrollWidth>main.clientWidth+2;for(const id of ['recBtn','exportBtn'])if(!['rgba(0, 0, 0, 0)','transparent'].includes(getComputedStyle(document.getElementById(id)).webkitTapHighlightColor))bad=true;for(const id of ['recBtn','exportBtn','elapsed']){const e=document.getElementById(id);if(!e.closest('[hidden]')){const r=e.getBoundingClientRect();if(r.left<0||r.right>innerWidth+1)bad=true;}}document.body.setAttribute('data-layout-check',bad?'FAIL':'PASS');},50);
</script>`;
 const file=path.join(dir,name+'.html');fs.writeFileSync(file,html.replace('</body>',fixture+'</body>'));
 const args=['--headless','--no-sandbox','--disable-gpu','--disable-dev-shm-usage','--hide-scrollbars',`--window-size=${width},${height}`,'--virtual-time-budget=700'];
 const dom=execFileSync('google-chrome',[...args,'--dump-dom','file://'+file],{encoding:'utf8',stdio:['ignore','pipe','pipe'],timeout:60000});assert.ok(dom.includes('data-layout-check="PASS"'),'layout overflow or JS error: '+name);
 execFileSync('google-chrome',[...args,'--screenshot='+path.join(out,name+'.png'),'file://'+file],{stdio:'pipe',timeout:60000});console.log('Rendered and checked',name,width,height);
}
fs.rmSync(dir,{recursive:true,force:true});
