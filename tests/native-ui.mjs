import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const elements=new Map();
const element=()=>({style:{},classList:{add(){},remove(){},toggle(){}},addEventListener(){},setAttribute(){},appendChild(){},querySelectorAll(){return[];},getBoundingClientRect(){return {left:0,top:0,width:320,height:205};}});
const calls=[];let running=false;
const BB={
 start:async o=>{calls.push(['start',o]);running=true;},stop:async()=>{calls.push(['stop']);running=false;},
 status:async()=>({running,db:-40,route:'휴대폰 내장 마이크'}),listRecordings:async()=>({files:[]}),
 configure:async()=>{},addListener(){}
};
const exportFiles=Array.from({length:27},(_,i)=>({name:`LBB_${String(i).padStart(3,'0')}.m4a`,uri:`content://recordings/${i}`,size:2000,durMs:30000}));
const EX={currentRecordings:async()=>({files:exportFiles,folder:'앱 녹음 전체'}),chooseFolder:async()=>{throw Error('폴더 선택을 취소했습니다');},shareBatch:async v=>{calls.push(['share',v]);return {direct:false,count:v.uris.length};}};
const cap={isNativePlatform:()=>true,Plugins:{BlackboxService:BB,RecordingExport:EX}};
const context={console,Date,Math,Promise,URL,Blob,performance:{now:()=>1},
 window:{Capacitor:cap},Capacitor:cap,navigator:{mediaDevices:{getUserMedia(){throw Error('Native mode must never request WebView audio');}}},
 document:{visibilityState:'visible',getElementById(id){if(!elements.has(id))elements.set(id,element());return elements.get(id);},createElement:element,querySelectorAll:()=>[],addEventListener(){}},
 localStorage:{getItem:()=>null,setItem(){}},setTimeout:()=>1,clearTimeout(){},setInterval(){},requestAnimationFrame:()=>1,cancelAnimationFrame(){}};
vm.createContext(context);
const html=fs.readFileSync('www/index.html','utf8');vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1],context);
await new Promise(resolve=>setImmediate(resolve));
await vm.runInContext('startAll()',context);
assert.equal(calls.filter(c=>c[0]==='start').length,1);
assert.equal(calls[0][1].config.splitMin,30);
assert.equal(vm.runInContext('R.running',context),true);
await vm.runInContext('startAll()',context);
assert.equal(calls.filter(c=>c[0]==='start').length,1,'duplicate start must be ignored');
vm.runInContext('stopAll()',context);await vm.runInContext('pollNative()',context);
assert.equal(calls.filter(c=>c[0]==='stop').length,1);
assert.equal(vm.runInContext('R.running',context),false);
console.log('Native UI start / status / duplicate-start / stop: PASS');


await vm.runInContext('loadExport(false)',context);
assert.equal(vm.runInContext('exportState.files.length',context),27);
assert.equal(vm.runInContext('exportState.batches.length',context),3);
assert.equal(vm.runInContext('exportState.batches[2].length',context),7);
await vm.runInContext('sendExport(true)',context);
assert.equal(calls.find(c=>c[0]==='share')[1].uris.length,10);
assert.equal(vm.runInContext('exportState.index',context),0,'handoff must not imply upload success or advance');
await vm.runInContext('loadExport(true)',context);
assert.equal(vm.runInContext('exportState.files.length',context),27,'cancelled picker preserves existing queue');
assert.equal(vm.runInContext('makeExportBatches([{uri:"a",name:"x"},{uri:"a",name:"x"},{uri:"b",name:"y"}],1).length',context),2);
assert.equal(vm.runInContext('makeExportBatches([]).length',context),0);
console.log('Whole-folder batching / deduplication / cancellation / explicit handoff: PASS');
