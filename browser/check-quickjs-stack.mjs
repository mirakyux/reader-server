import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

// Run against an isolated API configured with the real QuickJS sidecar.
// The source returns local fixture values and performs no network requests.
const args = process.argv.slice(2);
if (args.includes('--help')) {
  process.stdout.write('node browser/check-quickjs-stack.mjs [--api http://127.0.0.1:18080]\n');
  process.exit(0);
}
const apiOption = args.find((value) => value.startsWith('--api='))?.slice(6)
  || (args.includes('--api') ? args[args.indexOf('--api') + 1] : args[0]);
const apiAddress = new URL(apiOption || 'http://127.0.0.1:18080');
assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(apiAddress.hostname), 'The test API must be local and isolated');
assert.notEqual(apiAddress.port || '80', '8080', 'The live API on port 8080 must not be used');
assert.ok(['http:', 'https:'].includes(apiAddress.protocol), 'The test API must use HTTP');
assert.equal(apiAddress.username + apiAddress.password, '', 'Credentials must not appear in the API URL');

const runId = randomUUID();
const sourceUrl = `${apiAddress.origin}/quickjs-source/${runId}`;
const bookUrl = `${apiAddress.origin}/quickjs-book/${runId}`;
const bookName = `QuickJS 联测 ${runId}`;
const createdSources = [];
const createdBooks = [];
const results = [];
let failure;
let cleanupErrors = [];
let registered;

async function exchange(endpoint, body, allowFailure = false) {
  const response = await fetch(`${apiAddress.origin}/reader3/${endpoint}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    signal: AbortSignal.timeout(60000),
  });
  const text = await response.text();
  let result;
  try { result = JSON.parse(text); } catch { throw new Error(`${endpoint}: HTTP ${response.status}, invalid JSON ${text}`); }
  if (allowFailure) return { status: response.status, result, text };
  assert.equal(response.status, 200, `${endpoint}: HTTP ${response.status} ${text}`);
  assert.equal(result.isSuccess, true, `${endpoint}: ${result.errorMsg || text}`);
  return result.data;
}

async function register(script) {
  const parsed = await exchange('parseBookSource', { content: script });
  assert.equal(parsed.length, 1, 'Single-file JavaScript must import exactly one source');
  createdSources.push(parsed[0].bookSourceUrl);
  await exchange('saveBookSource', parsed[0]);
  return parsed[0];
}

function passed(name) { results.push({ case: name, passed: true }); }
async function search(key) { return exchange('searchBook', { bookSourceUrl: sourceUrl, key, page: 2 }); }
async function assertRecovery(key) {
  const books = await search(`recovery-${key}`);
  assert.equal(books.length, 1);
  assert.equal(books[0].name, bookName);
  assert.equal(books[0].kind, `recovery-${key}|2|8`);
}

// The host source uses FU's Rhino contract. Modern code executes only inside
// java.getQuickJsSandbox().evalString and results cross its string-only bridge.
const config = {
  bookSourceUrl: sourceUrl, bookSourceName: `QuickJS fixture ${runId}`, enabled: true,
  exploreUrl: [{ title: 'QuickJS discovery', url: 'fixture-discovery' }],
};
const sourceScript = `var config=${JSON.stringify(config)};
function quick(code){return String(java.getQuickJsSandbox().evalString(code));}
function quickJson(code){return JSON.parse(quick(code));}
function search(key,page){
  var failures={
    'non-string':'42',
    'throw':"throw new Error('fixture throw')",
    'await-rejection':"await Promise.reject(new Error('fixture await rejection')); 'unreachable'",
    'unhandled-rejection':"Promise.reject(new Error('fixture rejection')); 'must not succeed'",
    'no-rhino-fallback':"if(typeof java!=='undefined') 'Rhino fallback would succeed'; else 42;"
  };
  if(Object.prototype.hasOwnProperty.call(failures,key)){
    return [{name:quick(failures[key]),author:'fixture',bookUrl:${JSON.stringify(bookUrl)}}];
  }
  if(key==='isolation'){
    return [{name:quick('String([typeof process, typeof require, typeof java, typeof fetch].join("|"))'),author:'fixture',bookUrl:${JSON.stringify(bookUrl)}}];
  }
  if(key==='fresh-context'){
    quick('globalThis.readerFixtureLeak=123; "set"');
    return [{name:quick('typeof globalThis.readerFixtureLeak'),author:'fixture',bookUrl:${JSON.stringify(bookUrl)}}];
  }
  return quickJson('const input = await Promise.resolve('+JSON.stringify({key:key,page:page})+'); '+
    'const modern={...input}; JSON.stringify([{name:'+${JSON.stringify(JSON.stringify(bookName))}+', '+
    'author:"fixture",bookUrl:'+${JSON.stringify(JSON.stringify(bookUrl))}+', '+
    'kind:(modern?.key ?? "missing")+"|"+modern.page+"|"+(2n**3n).toString()}])');
}
function getBookInfo(book){
  return quickJson('const book='+JSON.stringify(book)+'; '+
    'const title=await Promise.resolve('+${JSON.stringify(JSON.stringify(bookName))}+'); '+
    'JSON.stringify({name:title,author:"fixture",intro:"QuickJS modern details",tocUrl:book?.bookUrl ?? "missing"})');
}
function getChapters(book){
  return quickJson('const book='+JSON.stringify(book)+'; '+
    'const chapters=await Promise.resolve([0,1]); '+
    'JSON.stringify(chapters.map(index=>({title:"第"+(index+1)+"章 QuickJS",url:book.bookUrl+"/"+index})))');
}
function getContent(chapter,book,next){
  return quick('const chapter='+JSON.stringify(chapter)+'; const next='+JSON.stringify(next)+'; '+
    'await Promise.resolve("QuickJS 第"+chapter.index+"章正文。😀\\\\n完整内容；下一章："+(next ?? "结束"))');
}
function explore(url,page){
  return quickJson('const input='+JSON.stringify({url:url,page:page})+'; '+
    'await Promise.resolve(); JSON.stringify([{name:"QuickJS discovery",author:"fixture",bookUrl:'+${JSON.stringify(JSON.stringify(bookUrl))}+',kind:input.url+"|"+input.page}])');
}
function loginUi(state){
  return quickJson('const state='+JSON.stringify(state)+'; await Promise.resolve(); '+
    'JSON.stringify({rows:[{name:"QuickJS user",key:"user",type:"text"},{name:"QuickJS login",type:"button",action:"login"}]})');
}
function loginAction(action,state,form){
  if(action==='cleanup'){
    java.getSource().removeLoginInfo();
    return quickJson('await Promise.resolve(); JSON.stringify({close:true,state:{cleaned:true}})');
  }
  return quickJson('const form='+JSON.stringify(form)+'; '+
    'const user=await Promise.resolve(form?.user ?? "missing"); '+
    'JSON.stringify({close:true,state:{quick:"login-ready"},login:{user:user,token:"fixture-only"}})');
}
`;

try {
  const health = await fetch(`${apiAddress.origin}/health`, { signal: AbortSignal.timeout(10000) });
  assert.equal(health.status, 200, `Isolated API is not healthy: ${apiAddress.origin}`);
  registered = await register(sourceScript);
  assert.equal(registered.bookSourceUrl, sourceUrl);
  assert.equal(registered.loginUi, '{"version":2}');
  passed('single-file FU JS import accepts QuickJS host functions and dynamic optional contracts');

  const searched = await search('modern');
  assert.equal(searched.length, 1);
  assert.equal(searched[0].name, bookName);
  assert.equal(searched[0].bookUrl, bookUrl);
  assert.equal(searched[0].kind, 'modern|2|8');
  const book = await exchange('getBookInfo', { url: bookUrl, bookSourceUrl: sourceUrl });
  assert.equal(book.name, bookName);
  assert.equal(book.author, 'fixture');
  assert.equal(book.intro, 'QuickJS modern details');
  assert.equal(book.tocUrl, bookUrl);
  createdBooks.push(book);
  await exchange('saveBook', book);
  const chapters = await exchange('getChapterList', { url: bookUrl, bookSourceUrl: sourceUrl, refresh: 1 });
  assert.equal(chapters.length, 2);
  assert.deepEqual(chapters.map((chapter) => chapter.index), [0, 1]);
  assert.equal(chapters[0].url, `${bookUrl}/0`);
  for (const index of [0, 1]) {
    const chapter = await exchange('getBookContentDetails', { url: bookUrl, bookSourceUrl: sourceUrl, index, refresh: 1 });
    const expectedNext = index === 0 ? `${bookUrl}/1` : '结束';
    assert.equal(chapter.content, `QuickJS 第${index}章正文。😀\n完整内容；下一章：${expectedNext}`);
  }
  passed('real Java bridge search → details → directory → both chapter bodies with await, optional chaining, spread and BigInt');

  const discovered = await exchange('exploreBook', { bookSourceUrl: sourceUrl, ruleFindUrl: 'fixture-discovery', page: 3 });
  assert.equal(discovered[0].name, 'QuickJS discovery');
  assert.equal(discovered[0].kind, 'fixture-discovery|3');
  passed('optional explore function executes real QuickJS and retains its inputs');
  const loginUi = await exchange('getBookSourceLoginUi', { bookSourceUrl: sourceUrl, state: { initial: true } });
  assert.equal(loginUi.version, 2);
  assert.equal(loginUi.rows[0].key, 'user');
  const loggedIn = await exchange('loginBookSource', { bookSourceUrl: sourceUrl, action: 'login', state: {}, form: { user: `fixture-${runId}` } });
  assert.equal(loggedIn.success, true);
  assert.equal(loggedIn.close, true);
  assert.equal(loggedIn.state.quick, 'login-ready');
  assert.equal(loggedIn.form.user, `fixture-${runId}`);
  assert.equal(loggedIn.form.token, 'fixture-only');
  passed('optional dynamic loginUi and loginAction use QuickJS and persist returned source login values');

  assert.equal((await search('isolation'))[0].name, 'undefined|undefined|undefined|undefined');
  assert.equal((await search('fresh-context'))[0].name, 'undefined');
  passed('sandbox has no Node, HTTP or Java bindings and each evaluation has a fresh context');
  for (const key of ['non-string', 'throw', 'await-rejection', 'unhandled-rejection', 'no-rhino-fallback']) {
    const rejected = await exchange('searchBook', { bookSourceUrl: sourceUrl, key }, true);
    assert.ok(rejected.status >= 400 || rejected.result.isSuccess === false, `${key}: failure must reach the API caller`);
    assert.match(rejected.text, /QuickJS sandbox failed: EVALUATION_FAILED/, `${key}: source must receive the sandbox error`);
    await assertRecovery(key);
    passed(`${key} fails without Rhino fallback and the next real QuickJS request recovers`);
  }

  const optionalUrl = `${apiAddress.origin}/quickjs-source-optional/${runId}`;
  const optionalBook = `${apiAddress.origin}/quickjs-book-optional/${runId}`;
  const optional = await register(`var config=${JSON.stringify({ bookSourceUrl: optionalUrl, bookSourceName: `QuickJS minimal ${runId}` })};
    function search(key,page){return [];}
    function getChapters(book){return [];}
    function getContent(chapter,book,next){return java.getQuickJsSandbox().evalString('await Promise.resolve("optional body")');}`);
  assert.ok(!optional.exploreUrl || optional.exploreUrl === '[]');
  assert.ok(!optional.loginUi || optional.loginUi === '[]');
  const optionalInfo = await exchange('getBookInfo', { url: optionalBook, bookSourceUrl: optionalUrl });
  assert.equal(optionalInfo.origin, optionalUrl);
  assert.equal(optionalInfo.tocUrl, optionalBook);
  passed('sources may omit getBookInfo, discovery and login functions when no corresponding contract is declared');
} catch (error) {
  failure = error;
} finally {
  if (registered) {
    await exchange('loginBookSource', { bookSourceUrl: sourceUrl, action: 'cleanup', state: {}, form: {} })
      .catch((error) => cleanupErrors.push(error));
  }
  for (const book of createdBooks.reverse()) {
    const cleanup = await exchange('deleteBook', book, true).catch((error) => { cleanupErrors.push(error); return null; });
    if (cleanup && (cleanup.status !== 200 || cleanup.result.isSuccess !== true) && !cleanup.text.includes('书架书籍不存在')) {
      cleanupErrors.push(new Error(`deleteBook: ${cleanup.text}`));
    }
  }
  for (const bookSourceUrl of createdSources.reverse()) {
    await exchange('deleteBookSource', { bookSourceUrl }).catch((error) => cleanupErrors.push(error));
  }
  if (cleanupErrors.length) failure ||= new AggregateError(cleanupErrors, 'Fixture cleanup failed');
}

process.stdout.write(`${JSON.stringify({ api: apiAddress.origin, runId, passed: !failure, results,
  cleanup: { completed: cleanupErrors.length === 0, errors: cleanupErrors.map((error) => error.message) },
  error: failure?.message }, null, 2)}\n`);
if (failure) throw failure;
