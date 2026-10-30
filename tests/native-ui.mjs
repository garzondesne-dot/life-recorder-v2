import fs from 'node:fs';import vm from 'node:vm';import assert from 'node:assert/strict';
const html=fs.readFileSync('www/index.html','utf8'),elements=new Map();
function el(){return {hidden:false,disabled:false,value:'',checked:false,textContent:'',innerHTML:'',style:{setProperty(){}},dataset:{},children:[],events:{},scrollTop:0,scrollIntoView(){},classList:{toggle(){},add(){},remove(){}},setAttribute(){},appendChild(x){this.children.push(x);},addEventListener(k,f){(this.events[k]??=[]).push(f);},dispatch(k,e={}){for(const f of this.events[k]||[])f({button:0,detail:1,preventDefault(){},...e});}};}
for(const m of html.matchAll(/id="([^"]+)"/g)){assert.ok(!elements.has(m[1]),'duplicate id '+m[1]);elements.set(m[1],el());}
for(const m of html.matchAll(/\$\('([^']+)'\)/g))assert.ok(elements.has(m[1]),'missing id '+m[1]);
elements.get('rangeStart').value='00:00';elements.get('rangeEnd').value='23:59';elements.get('purpose').value='brief';elements.get('batchSize').value='9';
let starts=0,micChecks=0;let running=false,now=0;const timers=new Map(),saved=new Map([["lbb_usage_v1","1"],["lbb_guide_v3","done"]]),frames=new Map();let seq=0,prepares=0,shares=0,stops=0;const calls=[];const day=new Date().toLocaleDateString('en-CA');
const date=()=>{const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;};
const files=Array.from({length:10},(_,i)=>({name:`LBB_${date().replaceAll('-','')}_1200${String(i).padStart(2,'0')}.m4a`,uri:`content://recording/${i}`,size:10000,created:Date.now()-1000,durMs:500,days:[date()]}));
const BB={status:async()=>({running,sleeping:false,db:-40,recMs:500}),listRecordings:async()=>({files}),configure:async()=>{},acceptUsage:async()=>{},requestMic:async()=>{micChecks++;return {allowed:true};},start:async()=>{starts++;running=true;},stop:async()=>{stops++;running=false;},notificationStatus:async()=>({allowed:false}),launchExport:async()=>({token:null})};
const EX={prepareSnapshot:async({date})=>{prepares++;return {date,token:'123',cutoff:Date.now(),files:[...files]};},shareBatch:async o=>{shares++;calls.push(o);return {direct:true};},copyPrompt:async()=>{}};
let driveConnections=0,driveSyncs=0,driveDisconnects=0,driveEnabled=false,driveEconomy=true;const DB={status:async()=>({enabled:driveEnabled,economy:driveEconomy,email:'test@example.invalid',lastSuccess:1,backedUp:2,pending:0,error:''}),connect:async({consent})=>{assert.equal(consent,true);driveConnections++;driveEnabled=true;},economy:async({enabled})=>{driveEconomy=enabled;},sync:async()=>{driveSyncs++;},disconnect:async()=>{driveDisconnects++;driveEnabled=false;}};
const cap={isNativePlatform:()=>true,Plugins:{BlackboxService:BB,RecordingExport:EX,DriveBackup:DB}};const main=el(),document={body:el(),activeElement:null,visibilityState:'visible',getElementById:id=>elements.get(id),querySelector:()=>main,querySelectorAll:()=>[],createElement:el,addEventListener(){}};
const context={console,window:{Capacitor:cap,confirm:()=>true},Capacitor:cap,document,Date,Map,Set,JSON,Math,Promise,Intl,performance:{now:()=>now},localStorage:{getItem:k=>saved.get(k)||null,setItem:(k,v)=>saved.set(k,v)},setTimeout:(fn,ms)=>{timers.set(++seq,{fn,ms});return seq;},clearTimeout:id=>timers.delete(id),requestAnimationFrame:fn=>{frames.set(++seq,fn);return seq;},cancelAnimationFrame:id=>frames.delete(id)};vm.createContext(context);vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1],context);
const run=code=>vm.runInContext(code,context),flush=()=>new Promise(r=>setImmediate(r));await flush();
await run('startRecord()');assert.equal(running,true);await run('stopRecord()');await run('poll()');assert.equal(running,false);
await run('prepareExport(state.date)');assert.equal(run('state.batches.length'),2);assert.equal(run('selectedBatch().length'),9);assert.equal(run('state.page'),'prepare');
await run('sendExport()');assert.equal(shares,1);assert.equal(calls[0].token,'123');assert.ok(calls[0].prompt.includes('사용자 분석 우선순위'));assert.equal(run('statusFor(state.date,"gemini").count'),0,'sharing must never auto-confirm');
elements.get('confirmBatch').onclick();assert.equal(run('statusFor(state.date,"gemini").count'),9);
elements.get('nextBatch').onclick();await run('sendExport()');elements.get('confirmBatch').onclick();assert.equal(run('statusFor(state.date,"gemini").complete'),true);
files.push({...files[0],uri:'content://recording/new',name:'new.m4a'});assert.equal(run('statusFor(state.date,"gemini").newCount'),1,'new audio must retain old confirmations');
const button=elements.get('exportBtn');run('show("home")');let initial=prepares;
button.dispatch('pointerdown');now=200;button.dispatch('pointerup');button.dispatch('click');await flush();assert.equal(run('state.page'),'archive');assert.equal(prepares,initial,'short tap must not rotate recording');
run('show("home")');button.dispatch('pointerdown');now+=1500;const hold=[...timers.values()].find(x=>x.ms===1500);assert.ok(hold);hold.fn();button.dispatch('pointerup');button.dispatch('click');await flush();assert.equal(prepares,initial+1);assert.equal(run('state.page'),'home','quick hold must not open intermediate screen');assert.equal(calls.at(-1).uris.length,9);assert.equal(calls.at(-1).target,'share');
run('show("home")');button.dispatch('pointerdown');button.dispatch('pointerleave');assert.ok(![...timers.values()].some(x=>x.ms===1500),'leaving cancels hold');button.dispatch('click',{detail:0});await flush();assert.equal(run('state.page'),'archive','assistive click must work');
console.log('UI IDs / native start-stop / snapshot token / no auto-confirm / stable history / new audio / tap-hold cancellation / assistive click: PASS');
context.localStorage.key=i=>[...saved.keys()][i];Object.defineProperty(context.localStorage,'length',{get:()=>saved.size});saved.delete('lbb_v23_migrated');run('history={}');
const oldKey='lbb_v22_sent_'+date()+'_gemini_'+JSON.stringify([files.slice(0,9).map(f=>f.uri),files.slice(9).map(f=>f.uri)])+'_brief_00:00_23:59';saved.set(oldKey,'[0]');run('migrateLegacyHistory()');assert.equal(run('statusFor(state.date,"gemini").count'),9);assert.ok(saved.has(oldKey),'legacy data is never deleted');console.log('v2.2 confirmation migration: PASS');

run("profile.priorities=['todo','speech'];profile.extra='가족 부탁 표현';state.customPrompt=null");const prompt=run('buildPrompt()');assert.ok(prompt.indexOf('1순위: 할 일·약속')<prompt.indexOf('2순위: 언어습관·말하기'));assert.ok(!prompt.includes('서환'));assert.ok(prompt.includes('후속 묶음'));assert.ok(prompt.includes('전사문이 아니'));
run("show('home');state.snapshot.files=[...state.all];state.batches=makeBatches(state.all,9);state.index=0;state.quick=true");const beforeShares=shares;await elements.get('quickNext').onclick();assert.equal(shares,beforeShares+1);assert.equal(calls.at(-1).uris.length,2);assert.ok(!calls.at(-1).uris.includes(calls.at(-2).uris[0]));
run("cfg.restSchedules=[{name:'취침',start:'23:00',end:'07:00',days:[1,2],enabled:true}];editRest(0)");assert.equal(elements.get('restStart').value,'23:00');elements.get('restEnd').value='08:00';elements.get('saveRest').onclick();assert.equal(run('cfg.restSchedules[0].end'),'08:00');
console.log('Priority prompt / no private-name leakage / direct handoff / next batch / recurring schedule editing: PASS');

// Drive the actual gesture clock: partial hold shows progress, cancellation clears it, completion stops once.
await run('startRecord()');run("show('home')");frames.clear();const rec=elements.get('recBtn');const stopBefore=stops;now=5000;rec.dispatch('pointerdown');now=5500;let frame=[...frames.values()].at(-1);assert.ok(frame);frame();assert.equal(elements.get('stopHoldArc').style.strokeDashoffset,'50');assert.equal(stops,stopBefore);rec.dispatch('pointerleave');assert.equal(elements.get('stopHoldArc').style.strokeDashoffset,'100');assert.equal(stops,stopBefore);
now=6000;rec.dispatch('pointerdown');now=7000;const completion=[...timers.values()].filter(t=>t.ms===1000).at(-1);assert.ok(completion);completion.fn();rec.dispatch('pointerup');rec.dispatch('click');await flush();assert.equal(stops,stopBefore+1);assert.equal(elements.get('stopHoldArc').style.strokeDashoffset,'100');
await run('poll()');run('setStopProgress(.5)');assert.equal(elements.get('stopHoldArc').style.strokeDashoffset,'50','idle button now shows start progress');run('setStopProgress(0)');console.log('Stop hold ring / 500ms midpoint / leave cancellation / one-second stop once / idle reset: PASS');

for(const id of ['recBtn','exportBtn']){let prevented=false;elements.get(id).dispatch('contextmenu',{preventDefault(){prevented=true;}});assert.equal(prevented,true,'hold control must suppress native long-press menu');}console.log('Native long-press context menu suppressed on hold buttons: PASS');

run("Object.assign(state,{running:true,sleeping:false,interrupted:true,interruptionReason:'통화로 녹음 대기 중'});renderHome()");
assert.equal(elements.get('recStatus').textContent,'● 통화로 녹음 대기 중');
assert.equal(elements.get('db').textContent,'— dB');
assert.equal(elements.get('recBtn').disabled,false,'manual stop remains available during calls');
run("Object.assign(state,{sleeping:true});renderHome()");
assert.ok(elements.get('recStatus').textContent.includes('녹음 휴식 중'),'scheduled rest takes UI priority');
console.log('Call wait status / manual stop availability / scheduled rest priority: PASS');
assert.equal(elements.has('split'),false,'fixed two-hour organization replaces user interval selection');
assert.equal(run("Object.hasOwn(cfg,'splitMin')"),false,'old interval config is migrated away');
assert.ok(!fs.readFileSync('native/BlackboxServicePlugin.java','utf8').includes('saveAudio('));
assert.equal(JSON.parse(fs.readFileSync('package.json','utf8')).dependencies['@capacitor/filesystem'],undefined);
console.log('Fixed interval migration / retired saver and unused plugin removed: PASS');
assert.match(run("recordingLabel({name:'LBB_20261026_100000_000_W1792976400000_Mrevision.m4a'})"),/\d{2}:\d{2}–\d{2}:\d{2} 기록/);
run("state.all=[{name:'revision.m4a',uri:'content://new/revision',size:200,created:Date.now()}];history[state.date]={gemini:{ids:{old:123},everComplete:true}};renderArchive()");
assert.ok(elements.get('historyList').innerHTML.includes('새 녹음 1개 · 다시 확인 필요'));
console.log('Window range labels / superseded export confirmation requests review: PASS');

assert.equal(driveConnections,0,'startup must never request Google authorization');
context.window.confirm=()=>false;elements.get('driveConnect').onclick();assert.equal(driveConnections,0,'upload/deletion disclosure must be acknowledged');
context.window.confirm=()=>true;await elements.get('driveConnect').onclick();assert.equal(driveConnections,1);assert.equal(elements.get('driveSync').hidden,false);assert.ok(elements.get('driveStatus').textContent.includes('백업 확인됨'));
await elements.get('driveSync').onclick();assert.equal(driveSyncs,1);
context.window.confirm=()=>false;elements.get('driveDisconnect').onclick();assert.equal(driveDisconnects,0);
context.window.confirm=()=>true;await elements.get('driveDisconnect').onclick();assert.equal(driveDisconnects,1);assert.equal(elements.get('driveConnect').hidden,false);
console.log('Drive opt-in disclosure / no startup consent / explicit backup request / disconnect confirmation: PASS');

// 0.5s start hold, cancellation and mode changes must never become a short-tap start.
await run('poll()');run("Object.assign(state,{running:false,busy:false,stopping:false});show('home');renderHome()");const startBefore=starts;now=10000;rec.dispatch('pointerdown');now=10100;rec.dispatch('pointerup');rec.dispatch('click');await flush();assert.equal(starts,startBefore);
frames.clear();now=11000;rec.dispatch('pointerdown');now=11250;[...frames.values()].at(-1)();assert.equal(elements.get('stopHoldArc').style.strokeDashoffset,'50');rec.dispatch('pointerleave');assert.equal(elements.get('stopHoldArc').style.strokeDashoffset,'100');assert.equal(starts,startBefore);
now=12000;rec.dispatch('pointerdown');now=12500;[...timers.values()].filter(t=>t.ms===500).at(-1).fn();rec.dispatch('pointerup');rec.dispatch('click');await flush();assert.equal(starts,startBefore+1);await run('stopRecord()');await run('poll()');
console.log('Half-second start hold / short-tap ignored / progress / leave cancellation / start once: PASS');
// No first-launch permission request or recording before the usage acknowledgement.
saved.delete('lbb_usage_v1');saved.delete('lbb_guide_v3');run('openGuide()');assert.equal(run('state.page'),'guide');assert.equal(run('guide.step'),0);const beforeNotice=starts;await run('startRecord()');assert.equal(starts,beforeNotice);await elements.get('guideNext').onclick();assert.equal(run('guide.step'),0);
elements.get('usageAccept').checked=true;await elements.get('guideNext').onclick();assert.equal(saved.get('lbb_usage_v1'),'1');assert.equal(run('guide.step'),1);assert.equal(micChecks,0);run('guide.step=2;showGuide()');assert.equal(micChecks,0);await elements.get('guideMic').onclick();assert.equal(micChecks,1);
run('guide.step=4;showGuide()');elements.get('guideLength').value='short';elements.get('guideLength').onchange();assert.equal(run('profile.length'),'short');assert.ok(run('buildPrompt()').includes('핵심만 짧게'));run('guide.step=6;showGuide()');await elements.get('guideNext').onclick();assert.equal(saved.get('lbb_guide_v3'),'done');assert.ok(saved.has('lbb_v24_profile'));
elements.get('driveEconomy').checked=false;context.window.confirm=()=>false;elements.get('driveEconomy').onchange();assert.equal(elements.get('driveEconomy').checked,true);context.window.confirm=()=>true;elements.get('driveEconomy').checked=false;await elements.get('driveEconomy').onchange();await flush();assert.equal(driveEconomy,false);
console.log('Usage gate / optional permissions / tutorial preference persistence / economy opt-out confirmation: PASS');

// Report phase 1: no fake analysis, imported text is escaped and original versions remain separate.
assert.equal(run('reportDateOK("2026-02-30")'),false);assert.equal(run('reportDateOK("2026-10-30")'),true);
run("profile.priorities=['todo','speech'];profile.extra='부탁 표현';profile.length='short'");
const parsed=run('parseReportInput(JSON.stringify({summary:["핵심"],sections:[{topic:"speech",text:"<img src=x onerror=alert(1)>"}],todos:[{title:"확인",due:""}],speakers:[{label:"A",name:"이름 확인 필요"}]}))');assert.equal(parsed.priorities.join(','),'todo,speech');assert.equal(parsed.origin,'manual-import');
assert.throws(()=>run('parseReportInput(JSON.stringify({sample:true,summary:["sample"]}))'));
assert.throws(()=>run('parseReportInput(JSON.stringify({summary:["x"],todos:[{title:"x",due:"2026-02-30"}]}))'));
run('reports.report=parseReportInput(JSON.stringify({summary:["<script>bad</script>"],sections:[{topic:"speech",text:"<img src=x>"}]}));reports.sample=false;renderReport()');assert.ok(elements.get('reportSummary').innerHTML.includes('&lt;script&gt;'));assert.ok(!elements.get('reportSections').innerHTML.includes('<img'));assert.ok(elements.get('reportSections').innerHTML.includes('이 항목의 분석이 없습니다'));
await run('openReports(state.date)');elements.get('reportSample').onclick();assert.equal(run('reports.sample'),true);assert.equal(elements.get('reportDelete').hidden,true);assert.equal(elements.get('reportSaveReview').disabled,true);assert.ok(elements.get('reportBadge').textContent.includes('실제 녹음 분석 아님'));
elements.get('reportIncludePeople').checked=false;run('reports.people=[{id:"x",name:"PRIVATE",relation:"private",note:"secret"}]');assert.ok(!run('reportFormatRequest()').includes('PRIVATE'));elements.get('reportIncludePeople').checked=true;assert.ok(run('reportFormatRequest()').includes('PRIVATE'));assert.ok(run('reportFormatRequest()').includes('1. 할 일·약속'));assert.ok(run('reportFormatRequest()').includes('음성의 동일 화자라는 보장은 없음'));
run('show("reports")');assert.equal(elements.get('reports').hidden,false);assert.equal(elements.get('home').hidden,true);
console.log('Reports sample isolation / manual source / invalid dates / HTML escaping / missing analysis disclosure / explicit person inclusion: PASS');
