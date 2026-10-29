import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

// Delayed responses must update only the article that initiated them.
const nodes = new Map(), requests = [], cleanups = [];
function element() { return {isConnected:true, removed:false, children:[]}; }
function $(selector, attrs) {
  const node = typeof selector === 'string' && selector.startsWith('#') ? nodes.get(selector) : element();
  return {0:node,length:node?1:0,attrs,
    text(value){node.text=value;return this;},
    append(...values){node.children.push(...values);return this;},
    empty(){node.children=[];return this;},
    remove(){node.removed=true;node.isConnected=false;return this;}};
}
$.extend=Object.assign;
$.ajax=options=>{const request={options,readyState:1,aborted:false,abort(){this.aborted=true;options.error?.(this,'abort');}};requests.push(request);return request;};
const context={$,window:{BoloNavigation:{onLeave:cleanup=>cleanups.push(cleanup)}},Label:{servePath:'http://localhost'},console};
vm.runInNewContext(fs.readFileSync('src/main/webapp/js/page.js','utf8'),context);
const page=new context.Page({randomArticles1Label:'随机阅读'});
const initial=element();nodes.set('#randomArticles',initial);
page.loadRandomArticles();const old=requests.at(-1);
cleanups.splice(0).forEach(cleanup=>cleanup());assert(old.aborted);
initial.isConnected=false;
const replacement=element();nodes.set('#randomArticles',replacement);
old.options.success({randomArticles:[{articleTitle:'Old',articlePermalink:'/old'}]});
assert.equal(replacement.children.length,0,'A previous article must not overwrite the current list');
page.loadRandomArticles();const fresh=requests.at(-1);
fresh.options.success({randomArticles:[{articleTitle:'<script>unsafe</script>',articlePermalink:'/new'}]});
assert.equal(replacement.children[1][0].children[0][0].children[0][0].text,'<script>unsafe</script>');
assert(!replacement.removed);
const relevant=element();nodes.set('#relevantArticles',relevant);page.loadRelevantArticles('id', '<h4>相关</h4>');
requests.at(-1).options.success({relevantArticles:[]});assert(relevant.removed);
nodes.delete('#relevantArticles');const count=requests.length;page.loadRelevantArticles('id');assert.equal(requests.length,count);
console.log('Recommendation checks passed: cancellation, stale replies, escaped titles, empty results and absent containers.');
