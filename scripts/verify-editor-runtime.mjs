import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source=fs.readFileSync('src/main/webapp/js/common.js','utf8');
function environment() {
  const scripts=[],head=[];
  const document={createElement:()=>({setAttribute(name,value){this[name]=value;},remove(){this.removed=true;head.splice(head.indexOf(this),1);}}),
    getElementById:id=>head.find(script=>script.id===id),head:{appendChild(script){scripts.push(script);head.push(script);}}};
  const window={BoloNavigation:{setTimeout,clearTimeout}};
  const context=vm.createContext({window,document,Label:{servePath:'https://example.test/blog',localeString:'zh_CN'},console,setTimeout,clearTimeout});
  vm.runInContext(source,context);
  return {context,window,scripts,head,reload:()=>vm.runInContext(source,context)};
}
{
  const env=environment(),{context,window,scripts}=env;
  const first=context.Util.loadVditorRuntime(),second=context.Util.loadVditorRuntime();
  assert.equal(scripts.length,3,'Two editors share one constructor, parser and language download');
  assert(scripts.every(script=>script.async&&script['data-bolo-navigation']===''),'Shared downloads survive PJAX cleanup');
  context.Vditor=function(){};context.Vditor.prototype.getValue=function(){};
  window.Lute={New(){}};window.VditorI18n={undo:'撤销'};
  for(const script of [...scripts])script.onload();
  const result=await Promise.all([first,second]);
  assert.equal(result[0].i18n,window.VditorI18n);
  assert.equal(result[1].i18n,result[0].i18n);
  const constructor=context.Vditor;
  context.Vditor={preview(){}}; // Render-only skin bundles can overwrite the global after PJAX.
  env.reload();await context.Util.loadVditorRuntime();assert.equal(scripts.length,3,'Reloading page utilities does not download the parser again');
  assert.equal(context.Vditor,constructor,'Restore the cached editing constructor after a skin loads the renderer');
  env.head.splice(0);await context.Util.loadVditorRuntime();
  assert.equal(scripts.filter(script=>script.src).length,3,'Loaded globals can restore missing Vditor markers without transferring assets');
  assert(env.head.some(script=>script.id==='vditorLuteScript'));
  context.Label.localeString='en_US';
  const english=context.Util.loadVditorRuntime();
  const englishScript=scripts.find(script=>script.src?.endsWith('/en_US.js'));
  const en={undo:'Undo'};window.VditorI18n=en;englishScript.onload();
  assert.equal((await english).i18n,en);
  context.Label.localeString='zh_CN';
  assert.equal((await context.Util.loadVditorRuntime()).i18n,result[0].i18n,'Returning to a cached locale uses its own translation object');
}
{
  const {context,window,scripts}=environment();
  const first=context.Util.loadVditorRuntime();const rejected=assert.rejects(first,/load failed/);
  const core=scripts.find(script=>script.src.endsWith('/index.min.js'));
  const parser=scripts.find(script=>script.src.endsWith('/lute.min.js'));
  const language=scripts.find(script=>script.src.endsWith('/zh_CN.js'));
  context.Vditor=function(){};context.Vditor.prototype.getValue=function(){};core.onload();
  window.VditorI18n={};language.onload();parser.onerror();await rejected;
  const retry=context.Util.loadVditorRuntime();assert.equal(scripts.length,4,'Retry transfers only the failed parser');
  window.Lute={New(){}};scripts[3].onload();await retry;
  assert(parser.removed);
}
{
  const copied=[],menus=[];
  let selected,focuses=0,fail=false;
  function element(tag) {
    const node={tagName:tag.toUpperCase(),children:[],attributes:{},style:{maxHeight:''},classList:{contains:()=>false},value:'',
      setAttribute(key,value){this.attributes[key]=value;},removeAttribute(key){delete this.attributes[key];},
      appendChild(child){child.parentElement=this;this.children.push(child);},
      insertAdjacentElement(position,child){child.parentElement=this;this.children.unshift(child);},
      querySelector(selector){return this.children.find(child=>selector==='textarea'?child.tagName==='TEXTAREA':child.tagName==='SPAN');},
      remove(){if(this.parentElement){const children=this.parentElement.children;children.splice(children.indexOf(this),1);this.parentElement=null;}},
      focus(){focuses++;},select(){selected=this;},setSelectionRange(){},before(menu){menus.push(menu);},insertAdjacentHTML(){}};
    Object.defineProperty(node,'innerHTML',{set(html){
      this.textContent=html.replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&');
      if(html.startsWith('<span aria-label=')){const button=element('span');button.setAttribute('aria-label','复制');this.appendChild(button);}
    }});
    return node;
  }
  const body=element('body'),previous={focus(){focuses++;}};
  const document={body,activeElement:previous,createElement:element,getElementById:()=>null,
    execCommand(command){assert.equal(command,'copy');if(fail)throw Error('Clipboard unavailable');copied.push(selected.value);return true;}};
  const code=element('code'),text='server {\n    listen 8080;\n    absolute_redirect off;\n}';
  code.innerText=text+'\n';code.parentElement=element('pre');body.querySelectorAll=selector=>{assert.equal(selector,'pre > code');return [code];};
  const window={outerHeight:740,VditorI18n:{copy:'复制',copied:'已复制'},navigator:{clipboard:{writeText:async value=>copied.push(value)}}};
  const context=vm.createContext({window,document,navigator:{userAgent:''},Promise});
  vm.runInContext(source,context);
  vm.runInContext(fs.readFileSync('node_modules/vditor/dist/method.min.js','utf8'),context);
  context.Vditor.codeRender(body,{renderMenu:context.Util.prepareCodeCopyMenu});
  assert.equal(menus.length,1,'Use the real bundled Vditor copy renderer');
  assert(!menus[0].querySelector('textarea'),'Code copy controls must not leave form fields for Safari to restore');
  const button=menus[0].querySelector('span[aria-label]'),event={key:'Enter',preventDefault(){},stopPropagation(){}};
  button.onclick(event);await Promise.resolve();await Promise.resolve();
  assert.equal(copied.at(-1),text);assert.equal(button.attributes['role'],'button');assert.equal(button.tabIndex,0);
  assert.equal(button.attributes['aria-label'],'已复制');
  delete window.navigator.clipboard;button.onkeydown(event);await Promise.resolve();
  assert.equal(copied.at(-1),text,'Keyboard copy keeps the original code text');
  assert.equal(body.children.length,0,'The synchronous clipboard fallback removes its temporary textarea');
  fail=true;assert.equal(await context.Util.copyCodeText(text),false);assert.equal(body.children.length,0,'Failure also cleans the fallback');
  window.navigator.clipboard={writeText:()=>Promise.reject(Error('Permission denied'))};
  assert.equal(await context.Util.copyCodeText(text),false);assert.equal(body.children.length,0);
  assert(focuses>0);
}
console.log('Editor runtime checks passed: shared loading/retry, real Vditor copy menus, keyboard/clipboard fallback and removal of restored form fields.');
