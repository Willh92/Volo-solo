import fs from 'node:fs';
import path from 'node:path';

// OSV checks public package coordinates only; application files and credentials are never uploaded.
const treeFile = process.argv[2] || 'target/dependency-tree.txt';
const reportFile = process.argv[3] || 'target/dependency-audit.json';
const queries = [];
const tree = fs.readFileSync(treeFile, 'utf8');
for (const match of tree.matchAll(/([\w.-]+):([\w.-]+):jar:(?:[\w.-]+:)?([^\s:]+):(compile|runtime|test|provided)/g)) {
  queries.push({package: {ecosystem: 'Maven', name: match[1] + ':' + match[2]}, version: match[3], scope: match[4]});
}
function npmPackages(directory) {
  if (!fs.existsSync(directory)) return;
  for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
    if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
    const location = path.join(directory, entry.name);
    if (entry.name.startsWith('@')) {
      npmPackages(location);
      continue;
    }
    const manifest = path.join(location, 'package.json');
    if (fs.existsSync(manifest)) {
      const pkg = JSON.parse(fs.readFileSync(manifest, 'utf8'));
      queries.push({package: {ecosystem: 'npm', name: pkg.name}, version: pkg.version, scope: 'frontend/build'});
    }
    npmPackages(path.join(location, 'node_modules'));
  }
}
npmPackages('node_modules');
const unique = [...new Map(queries.map(query => [query.package.ecosystem + ':' + query.package.name + ':' + query.version, query])).values()];
const findings = [];
for (let index = 0; index < unique.length; index += 100) {
  const batch = unique.slice(index, index + 100);
  const response = await fetch('https://api.osv.dev/v1/querybatch', {
    method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({queries: batch.map(({package: pkg, version}) => ({package: pkg, version}))}),
    signal: AbortSignal.timeout(30000),
  });
  if (!response.ok) throw new Error('OSV request failed: ' + response.status);
  const results = (await response.json()).results;
  for (let offset = 0; offset < batch.length; offset++) {
    findings.push({...batch[offset], vulnerabilities: results[offset].vulns || []});
  }
}
const affected = findings.filter(result => result.vulnerabilities.length);
fs.mkdirSync(path.dirname(reportFile), {recursive:true});
fs.writeFileSync(reportFile, JSON.stringify({checkedAt: new Date().toISOString(), packages: findings.length, affected, results: findings}, null, 2));
console.log('Checked ' + findings.length + ' resolved Maven and npm packages; affected packages: ' + affected.length);
for (const result of affected) console.log(result.package.ecosystem, result.package.name + '@' + result.version, result.vulnerabilities.map(v => v.id).join(', '));
if (affected.length) process.exitCode = 1;
