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
console.log('Editor runtime checks passed: parallel loading, shared requests, PJAX reuse, locale switching, marker recovery and failed-parser retry.');
