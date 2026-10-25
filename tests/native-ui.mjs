import fs from 'node:fs';import vm from 'node:vm';import assert from 'node:assert/strict';
const html=fs.readFileSync('www/index.html','utf8'),elements=new Map();
function el(){return {hidden:false,disabled:false,value:'',checked:false,textContent:'',innerHTML:'',style:{},dataset:{},children:[],events:{},scrollTop:0,classList:{toggle(){},add(){},remove(){}},setAttribute(){},appendChild(x){this.children.push(x);},addEventListener(k,f){(this.events[k]??=[]).push(f);},dispatch(k,e={}){for(const f of this.events[k]||[])f({button:0,detail:1,preventDefault(){},...e});}};}
for(const m of html.matchAll(/id="([^"]+)"/g)){assert.ok(!elements.has(m[1]),'duplicate id '+m[1]);elements.set(m[1],el());}
for(const m of html.matchAll(/\$\('([^']+)'\)/g))assert.ok(elements.has(m[1]),'missing id '+m[1]);
elements.get('rangeStart').value='00:00';elements.get('rangeEnd').value='23:59';elements.get('purpose').value='brief';elements.get('batchSize').value='9';
let running=false,now=0;const timers=new Map(),saved=new Map();let seq=0,prepares=0,shares=0;const calls=[];const day=new Date().toLocaleDateString('en-CA');
const date=()=>{const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;};
const files=Array.from({length:10},(_,i)=>({name:`LBB_${date().replaceAll('-','')}_1200${String(i).padStart(2,'0')}.m4a`,uri:`content://recording/${i}`,size:10000,created:Date.now()-1000,durMs:500,days:[date()]}));
const BB={status:async()=>({running,sleeping:false,db:-40,recMs:500}),listRecordings:async()=>({files}),configure:async()=>{},start:async()=>{running=true;},stop:async()=>{running=false;},notificationStatus:async()=>({allowed:false}),launchExport:async()=>({token:null})};
const EX={prepareSnapshot:async({date})=>{prepares++;return {date,token:'123',cutoff:Date.now(),files:[...files]};},shareBatch:async o=>{shares++;calls.push(o);return {direct:true};},copyPrompt:async()=>{}};
const cap={isNativePlatform:()=>true,Plugins:{BlackboxService:BB,RecordingExport:EX}};const main=el(),document={visibilityState:'visible',getElementById:id=>elements.get(id),querySelector:()=>main,querySelectorAll:()=>[],createElement:el,addEventListener(){}};
const context={console,window:{Capacitor:cap},Capacitor:cap,document,Date,Map,Set,JSON,Math,Promise,performance:{now:()=>now},localStorage:{getItem:k=>saved.get(k)||null,setItem:(k,v)=>saved.set(k,v)},setTimeout:(fn,ms)=>{timers.set(++seq,{fn,ms});return seq;},clearTimeout:id=>timers.delete(id),requestAnimationFrame:()=>++seq,cancelAnimationFrame(){}};vm.createContext(context);vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1],context);
const run=code=>vm.runInContext(code,context),flush=()=>new Promise(r=>setImmediate(r));await flush();
await run('startRecord()');assert.equal(running,true);await run('stopRecord()');await run('poll()');assert.equal(running,false);
await run('prepareExport(state.date)');assert.equal(run('state.batches.length'),2);assert.equal(run('selectedBatch().length'),9);assert.equal(run('state.page'),'prepare');
await run('sendExport()');assert.equal(shares,1);assert.equal(calls[0].token,'123');assert.ok(calls[0].prompt.includes('5대 프레임워크'));assert.equal(run('statusFor(state.date,"gemini").count'),0,'sharing must never auto-confirm');
elements.get('confirmBatch').onclick();assert.equal(run('statusFor(state.date,"gemini").count'),9);
elements.get('nextBatch').onclick();await run('sendExport()');elements.get('confirmBatch').onclick();assert.equal(run('statusFor(state.date,"gemini").complete'),true);
files.push({...files[0],uri:'content://recording/new',name:'new.m4a'});assert.equal(run('statusFor(state.date,"gemini").newCount'),1,'new audio must retain old confirmations');
const button=elements.get('exportBtn');run('show("home")');let initial=prepares;
button.dispatch('pointerdown');now=200;button.dispatch('pointerup');button.dispatch('click');await flush();assert.equal(run('state.page'),'archive');assert.equal(prepares,initial,'short tap must not rotate recording');
run('show("home")');button.dispatch('pointerdown');now+=1500;const hold=[...timers.values()].find(x=>x.ms===1500);assert.ok(hold);hold.fn();button.dispatch('pointerup');button.dispatch('click');await flush();assert.equal(prepares,initial+1);assert.equal(run('state.page'),'prepare','long hold must not also run short-tap navigation');
run('show("home")');button.dispatch('pointerdown');button.dispatch('pointerleave');assert.ok(![...timers.values()].some(x=>x.ms===1500),'leaving cancels hold');button.dispatch('click',{detail:0});await flush();assert.equal(run('state.page'),'archive','assistive click must work');
console.log('UI IDs / native start-stop / snapshot token / no auto-confirm / stable history / new audio / tap-hold cancellation / assistive click: PASS');
