import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source=fs.readFileSync('src/main/webapp/js/error-page.js','utf8');
function fixture(destination='https://example.test/blog/',seconds='8') {
  let tick,cancel,leave,active=false;
  const visits=[],value={textContent:seconds};
  const notice={dataset:{homeCountdown:seconds,homeUrl:destination},textContent:'Countdown',querySelector:selector=>selector==='[data-countdown-value]'?value:{addEventListener:(type,callback)=>{cancel=callback;}}};
  const document={hidden:false,querySelector:()=>notice};
  vm.runInNewContext(source,{document,location:{href:'https://example.test/blog/error/403',origin:'https://example.test',assign:href=>visits.push(href)},URL,Number,
    window:{BoloNavigation:{onLeave:callback=>{leave=callback;}}},
    setInterval:callback=>{tick=callback;active=true;return 1;},clearInterval:()=>{active=false;}});
  return {document,notice,value,visits,tick:()=>{if(active)tick();},cancel:()=>cancel(),leave:()=>leave(),active:()=>active};
}
{
  const f=fixture();for(let i=0;i<7;i++)f.tick();assert.equal(f.visits.length,0);assert.equal(f.value.textContent,1);
  f.document.hidden=true;f.tick();assert.equal(f.value.textContent,1,'Hidden tabs must pause the countdown');
  f.document.hidden=false;f.tick();assert.deepEqual(f.visits,['https://example.test/blog/']);f.tick();assert.equal(f.visits.length,1);
}
for(const action of ['cancel','leave']) {
  const f=fixture();f.tick();f[action]();for(let i=0;i<10;i++)f.tick();assert(!f.active());assert.equal(f.visits.length,0,action+' must prevent later redirects');
}
for(const [destination,seconds]of [['https://other.test/','8'],['javascript:alert(1)','8'],['https://example.test/','0'],['https://example.test/','NaN']])assert(!fixture(destination,seconds).active(),'Unsafe or invalid countdown must not start');
console.log('Utility page checks passed: return-home deadline, hidden-tab pause, cancellation, PJAX cleanup and redirect origin checks');
