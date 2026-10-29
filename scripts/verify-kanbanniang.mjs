import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import vm from 'node:vm';
const directory='src/main/webapp/plugins/kanbanniang',assets=path.resolve(directory,'assets');
const exactPaths=new Set();
function paths(dir){for(const entry of fs.readdirSync(dir,{withFileTypes:true})){const file=path.join(dir,entry.name);if(entry.isDirectory())paths(file);else exactPaths.add(path.relative(assets,file).split(path.sep).join('/'))}}
paths(assets);
let references=0;
function resource(value){assert.equal(typeof value,'string');assert(!/^https?:|^\/|\\|(?:^|\/)\.\.(?:\/|$)/i.test(value),value);const file=path.resolve(assets,value);assert(file.startsWith(assets+path.sep));assert(exactPaths.has(value),'Resource name must also match on Linux: '+value);assert(fs.statSync(file).isFile(),value);references++}
function walkValue(value,key,variants=false){if(Array.isArray(value)){for(const child of value)walkValue(child,key,variants)}else if(value&&typeof value==='object'){for(const [name,child]of Object.entries(value))walkValue(child,name,variants)}else if(variants||['model','textures','physics','pose','file','sound'].includes(key))resource(value)}
function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const file=path.join(dir,e.name);if(e.isDirectory())walk(file);else if(e.name==='index.json'||e.name==='textures.json')walkValue(JSON.parse(fs.readFileSync(file,'utf8')),'',e.name==='textures.json')}}
walk(path.join(assets,'model'));
const names=JSON.parse(fs.readFileSync(path.join(assets,'model-list.json'),'utf8'));assert.equal(names.length,26);assert.equal(new Set(names).size,names.length);
const source=fs.readFileSync(directory+'/loader.js','utf8');
function fixture(width=1440,blockedStorage=false){
 const timers=new Map(),leaves=[],calls=[],urls=[],storage=new Map();let next=0,disposed=0,clones=0;
 function target(){const listeners=new Map();return {listeners,addEventListener(type,fn){let a=listeners.get(type);if(!a)listeners.set(type,a=[]);a.push(fn)},removeEventListener(type,fn){listeners.set(type,(listeners.get(type)||[]).filter(x=>x!==fn))},emit(type,event={}){for(const fn of [...listeners.get(type)||[]])fn(event)}}}
 const window=target(),document=target(),tip={style:{},textContent:''};
 Object.defineProperty(tip,'innerHTML',{set(){throw Error('Tip content was interpreted as HTML')}});
 const classes=new Set(),tools={};for(const name of ['Home','RSS','Github','Chat','Change','Photo','Close']){const button=target();tools[name]={button,closest:()=>button}}
 let canvas={cloneNode(){return {...this}},replaceWith(next){canvas=next;clones++}};
 const host=Object.assign(target(),{isConnected:true,hidden:true,style:{},dataset:{},classList:{add:x=>classes.add(x),contains:x=>classes.has(x)},
  offsetWidth:284,offsetHeight:254,querySelector(selector){if(selector==='.solo-kanbanniang__tip')return tip;if(selector==='#soloKanbanniang')return canvas;return tools[selector.replace('#soloKanbanniang','')]},
  getBoundingClientRect:()=>({left:16,top:470}),setPointerCapture(){},hasPointerCapture:()=>false});
 document.querySelector=()=>host;document.readyState='complete';document.hidden=false;
 window.Label={servePath:'https://example.test/blog',staticServePath:'https://example.test/blog'};
 window.BoloNavigation={onLeave:fn=>leaves.push(fn)};
 window.loadlive2d=(...args)=>calls.push(args);window.loadlive2d.dispose=()=>disposed++;
 Object.defineProperty(window,'sessionStorage',{get(){if(blockedStorage)throw Error('Storage unavailable');return {getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value)}}});
 const sandbox={window,document,innerWidth:width,innerHeight:740,AbortController,Promise,console,
  setTimeout(fn){const id=++next;timers.set(id,fn);return id},clearTimeout:id=>timers.delete(id),
  setInterval(fn){const id=++next;timers.set(id,fn);return id},clearInterval:id=>timers.delete(id),
  fetch:async url=>{urls.push(url);return {ok:true,json:async()=>({chat:['本地提示语'],mouseover:[{selector:'a',text:'阅读 {text}'}]})}}};
 vm.runInNewContext(source,sandbox);
 return {window,host,tip,tools,storage,timers,calls,urls,classes,leaves,disposed:()=>disposed,clones:()=>clones,
  ready(){window.emit('volo:kanban-ready',{detail:{model:'Potion-Maker/Pio'}})},
  destroy(){leaves.forEach(fn=>fn())},async flush(){await new Promise(resolve=>setImmediate(resolve))}};
}
{
 const f=fixture();await f.flush();assert.equal(f.calls.length,1);assert.equal(f.clones(),1);assert(f.calls[0][1].startsWith('https://example.test/blog/plugins/kanbanniang/assets/model?'));
 assert(f.host.hidden,'The widget must wait for a real model before becoming visible');f.ready();assert(!f.host.hidden);assert(f.window.VoloKanban.isReady());
 f.window.VoloKanban.showMessage('<img src=x onerror=alert(1)>');assert.equal(f.tip.textContent,'<img src=x onerror=alert(1)>');
 f.tools.Change.button.emit('click');assert(f.calls.at(-1)[1].includes('/assets/absoluteRandomModel?'));f.ready();
 assert(f.urls.every(url=>url.startsWith('https://example.test/blog/plugins/kanbanniang/')));
 f.destroy();assert(f.host.hidden);assert.equal(f.timers.size,0);assert.equal(f.disposed(),1);f.ready();assert(f.host.hidden,'A stale ready event must not reopen the old page');
}
{
 const f=fixture(390);await f.flush();assert.equal(f.calls.length,0);assert.equal(f.urls.length,0);assert(f.host.hidden);f.destroy();
}
{
 const f=fixture(1440,true);await f.flush();f.ready();assert(f.window.VoloKanban.isReady());f.tools.Close.button.emit('click');assert(f.classes.has('is-closed'));f.destroy();assert.equal(f.timers.size,0);
}
console.log(`Kanbanniang checks passed: 26 local models, ${references} resource references, mobile skip, safe text/storage and PJAX cleanup.`);
