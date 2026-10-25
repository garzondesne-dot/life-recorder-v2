import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const elements=new Map();
const element=()=>({style:{},classList:{add(){},remove(){},toggle(){}},addEventListener(){},setAttribute(){},appendChild(){},querySelectorAll(){return[];},getBoundingClientRect(){return {left:0,top:0,width:320,height:205};}});
const calls=[];let running=false;
const BB={
 start:async o=>{calls.push(['start',o]);running=true;},stop:async()=>{calls.push(['stop']);running=false;},
 status:async()=>({running,db:-40,route:'휴대폰 내장 마이크'}),listRecordings:async()=>({files:[]}),
 driveStatus:async()=>({enabled:false,status:'연결되지 않음'}),configure:async()=>{},addListener(){}
};
const cap={isNativePlatform:()=>true,Plugins:{BlackboxService:BB}};
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
