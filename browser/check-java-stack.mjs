import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { request } from 'playwright';

// Use an isolated API configured with the real browser sidecar; never target the user's 8080 service.
const args = process.argv.slice(2);
if (args.includes('--help')) {
  process.stdout.write('node browser/check-java-stack.mjs [--api http://127.0.0.1:18080]\n');
  process.exit(0);
}
const apiOption = args.find((value) => value.startsWith('--api='))?.slice(6)
  || (args.includes('--api') ? args[args.indexOf('--api') + 1] : args[0]);
const apiAddress = new URL(apiOption || 'http://127.0.0.1:18080');
assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(apiAddress.hostname), 'The test API must be local and isolated');
assert.notEqual(apiAddress.port || '80', '8080', 'The live API on port 8080 must not be used');
const client = await request.newContext({ baseURL: apiAddress.origin, timeout: 60000 });
const runId = randomUUID();
const requests = [];
const createdSources = [];
const results = [];
let sessionId;
let awaitingRequest;
let failure;

async function call(endpoint, body, allowFailure = false) {
  const response = await client.post(`/reader3/${endpoint}`, { data: body });
  const text = await response.text();
  assert.equal(response.status(), 200, `${endpoint}: HTTP ${response.status()} ${text}`);
  let result;
  try { result = JSON.parse(text); } catch { throw new Error(`${endpoint}: invalid JSON ${text}`); }
  if (!allowFailure) assert.equal(result.isSuccess, true, `${endpoint}: ${result.errorMsg || text}`);
  return allowFailure ? result : result.data;
}

async function saveFixtureSource(source) {
  // Register cleanup before the mutation, including a response failure after the server writes.
  createdSources.push(source.bookSourceUrl);
  await call('saveBookSource', source);
}

function screenshot(state) {
  assert.equal(typeof state.id, 'string');
  assert.equal(state.width, 1024);
  assert.equal(state.height, 768);
  assert.equal(typeof state.screenshot, 'string', 'The browser session must return a screenshot');
  const bytes = Buffer.from(state.screenshot, 'base64');
  assert.equal(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
  assert.ok(bytes.length > 1000, 'The rendered screenshot must have actual content');
  assert.equal(Object.hasOwn(state, 'storageState'), false, 'Private browser storage must stay server-side');
  return bytes;
}

const fixture = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://fixture.invalid');
  const entry = { method: req.method, path: url.pathname, header: req.headers['x-stack-fixture'],
    option: req.headers['x-url-option'], cookie: req.headers.cookie || '', agent: req.headers['user-agent'] || '' };
  requests.push(entry);
  const send = (status, body, headers = {}) => {
    entry.status = status;
    res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8', ...headers });
    res.end(body);
  };
  try {
    if (req.headers['x-stack-fixture'] !== runId) return send(403, 'Missing source header');
    if (url.pathname === '/js-start') return send(302, '', { Location: '/js-final', 'Set-Cookie': 'redirectToken=from302; Path=/; HttpOnly' });
    if (url.pathname === '/js-final') {
      if (!entry.cookie.includes('redirectToken=from302')) return send(403, 'Missing redirected cookie');
      return send(200, `<!doctype html><html><head><title>DOM fixture</title></head><body><h1 id="value">Static placeholder</h1>
        <script>document.getElementById('value').textContent='Java browser DOM ready';
        document.cookie='domToken=executed; path=/';localStorage.setItem('reader-stack','${runId}');</script></body></html>`);
    }
    if (url.pathname === '/get-start') {
      if (entry.option !== 'get-override') return send(403, 'Missing URL option header');
      return send(302, '', { Location: '/get-final/page' });
    }
    if (url.pathname === '/get-final/page') {
      if (entry.option !== 'get-override') return send(403, 'Missing redirected URL option header');
      return send(200, `<!doctype html><html><body><ul id="books"></ul><script>
        document.getElementById('books').innerHTML='<li><a href="next-book">GET dynamic book</a><b>Browser fixture</b></li>';
        document.cookie='getToken=option-cookie; path=/';</script></body></html>`);
    }
    if (url.pathname === '/submit') {
      if (req.method !== 'POST' || entry.option !== 'post-override') return send(403, 'POST options were not retained');
      let body = '';
      for await (const chunk of req) body += chunk;
      entry.body = body;
      if (new URLSearchParams(body).get('q') !== 'post-fixture') return send(400, 'Wrong POST body');
      return send(200, `<!doctype html><html><body><ul id="books"></ul><script>
        document.getElementById('books').innerHTML='<li><a href="post-book">POST dynamic book</a><b>Browser fixture</b></li>';
        document.cookie='postToken=from-html; path=/';</script></body></html>`);
    }
    if (url.pathname === '/protected') return send(200, entry.cookie.includes('verified=yes') ? 'ready' : 'locked');
    if (url.pathname === '/verify') return send(200, 'verified', { 'Set-Cookie': 'verified=yes; Path=/; HttpOnly' });
    if (url.pathname === '/captcha.svg') return send(200, '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="100"><rect width="240" height="100" fill="#eef2ff"/><text x="24" y="70" font-size="52" font-family="monospace">A9K4</text></svg>', { 'Content-Type': 'image/svg+xml' });
    if (url.pathname === '/interaction-page' || url.pathname === '/wrong-login') return send(500, 'Stored action HTML should supply this page');
    return send(404, 'Fixture route not found');
  } catch (error) { send(500, error.message); }
});

try {
  const health = await client.get('/health');
  assert.equal(health.status(), 200, `Isolated API is not healthy: ${apiAddress.origin}`);
  await new Promise((resolve) => fixture.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${fixture.address().port}`;
  const header = JSON.stringify({ 'X-Stack-Fixture': runId });
  const jsSourceUrl = `${base}/source-js/${runId}`;
  const loginHtml = `<!doctype html><html><head><title>Stored interaction login</title></head><body>
    <button style="position:fixed;left:24px;top:24px;width:240px;height:64px" onclick="verify()">Login fixture</button>
    <p id="status" style="position:fixed;left:24px;top:112px">Waiting for verification</p>
    <script>async function verify(){var response=await fetch('/verify');if(!response.ok)throw new Error('Login failed');
      document.getElementById('status').textContent=await response.text();document.title='Verified interaction';}</script></body></html>`;
  const config = { bookSourceUrl: jsSourceUrl, bookSourceName: `Java stack ${runId}`, header,
    loginUrl: `${base}/wrong-login`, enabledCookieJar: true };
  const script = `var config=${JSON.stringify(config)};
    function search(key,page){
      if(key==='verification-code'){
        var code=java.getVerificationCode('/captcha.svg?fixture=1&variant=2');
        return [{name:'Captcha resumed ready',author:String(code),bookUrl:${JSON.stringify(`${base}/captcha-book`)}}];
      }
      if(key==='verify-await'){
        var verified=java.startBrowserAwait(${JSON.stringify(`${base}/interaction-page`)},'Await verification',false,${JSON.stringify(loginHtml)});
        if(String(verified.body()).indexOf('verified')<0)throw new Error('The resumed script did not receive the verified page');
        if(String(verified.url())!==${JSON.stringify(`${base}/interaction-page`)})throw new Error('The resumed script did not receive the final URL');
        return [{name:'Await resumed ready',author:'fixture',bookUrl:${JSON.stringify(`${base}/await-book`)}}];
      }
      if(key==='verify'){
        var response=java.connect(${JSON.stringify(`${base}/protected`)});
        if(String(response.body())!=='ready'){
          java.startBrowser(${JSON.stringify(`${base}/interaction-page`)},'Stored verification',${JSON.stringify(loginHtml)});return [];
        }
        return [{name:'Authorized retry ready',author:'fixture',bookUrl:${JSON.stringify(`${base}/verified-book`)}}];
      }
      var text=java.webView(null,${JSON.stringify(`${base}/js-start`)},'JSON.stringify({value:document.getElementById("value").textContent,agent:navigator.userAgent,storage:localStorage.getItem("reader-stack")})');
      var page=JSON.parse(String(text));
      return [{name:page.value,author:page.agent,kind:page.storage,bookUrl:${JSON.stringify(`${base}/js-book`)}}];
    }
    function getChapters(book){return [{title:'Chapter',url:${JSON.stringify(`${base}/chapter`)}}];}
    function getContent(chapter,book,next){return 'Fixture content';}
  `;
  const parsed = await call('parseBookSource', { content: script });
  assert.equal(parsed.length, 1);
  assert.equal(parsed[0].bookSourceUrl, jsSourceUrl);
  await saveFixtureSource(parsed[0]);

  const jsBooks = await call('searchBook', { bookSourceUrl: jsSourceUrl, key: 'dom' });
  assert.equal(jsBooks.length, 1);
  assert.equal(jsBooks[0].name, 'Java browser DOM ready');
  assert.equal(jsBooks[0].kind, runId, 'Browser scripts must access origin-local web storage');
  assert.match(jsBooks[0].author, process.platform === 'win32' ? /Edg\// : /Chrome\//, 'navigator.userAgent must identify the real browser');
  const jsCookies = await call('getBookSourceCookies', { bookSourceUrl: jsSourceUrl });
  assert.match(jsCookies.cookie, /redirectToken=from302/);
  assert.match(jsCookies.cookie, /domToken=executed/);
  const domRequests = requests.filter((item) => item.path === '/js-start' || item.path === '/js-final');
  assert.equal(domRequests.length, 2);
  assert.ok(domRequests.every((item) => item.header === runId));
  results.push({ case: 'JavaScript source -> BrowserBridge -> real DOM, headers, redirect cookies', passed: true });

  const rules = { bookList: '@css:li', name: '@css:a@text', author: '@css:b@text', bookUrl: '@css:a@href' };
  const getSourceUrl = `${base}/source-get/${runId}`;
  await saveFixtureSource({ bookSourceUrl: getSourceUrl, bookSourceName: `GET browser ${runId}`, header,
    searchUrl: `${base}/get-start,${JSON.stringify({ webView: true, webJs: 'document.documentElement.outerHTML',
      webViewDelayTime: 5, headers: { 'X-Url-Option': 'get-override' } })}`, ruleSearch: rules });
  const getBooks = await call('searchBook', { bookSourceUrl: getSourceUrl, key: 'get-fixture' });
  assert.equal(getBooks.length, 1);
  assert.equal(getBooks[0].name, 'GET dynamic book');
  assert.equal(getBooks[0].bookUrl, `${base}/get-final/next-book`, 'Relative links must use the browser final URL');
  assert.match((await call('getBookSourceCookies', { bookSourceUrl: getSourceUrl })).cookie, /getToken=option-cookie/);
  assert.equal(requests.find((item) => item.path === '/get-final/page').option, 'get-override');
  results.push({ case: 'AnalyzeUrl GET webView/webJs/options and final URL', passed: true });

  const postSourceUrl = `${base}/source-post/${runId}`;
  await saveFixtureSource({ bookSourceUrl: postSourceUrl, bookSourceName: `POST browser ${runId}`, header,
    searchUrl: `${base}/submit,${JSON.stringify({ method: 'POST', body: 'q={{key}}', webView: true,
      webJs: 'document.documentElement.outerHTML', headers: { 'X-Url-Option': 'post-override' } })}`, ruleSearch: rules });
  const postBooks = await call('searchBook', { bookSourceUrl: postSourceUrl, key: 'post-fixture' });
  assert.equal(postBooks.length, 1);
  assert.equal(postBooks[0].name, 'POST dynamic book');
  assert.equal(postBooks[0].bookUrl, `${base}/post-book`);
  assert.match((await call('getBookSourceCookies', { bookSourceUrl: postSourceUrl })).cookie, /postToken=from-html/);
  assert.equal(requests.filter((item) => item.path === '/submit').length, 1, 'The browser must load the POST response HTML without refetching with GET');
  assert.equal(requests.find((item) => item.path === '/submit').method, 'POST');
  results.push({ case: 'AnalyzeUrl POST form -> returned HTML -> browser webJs', passed: true });

  assert.deepEqual(await call('searchBook', { bookSourceUrl: jsSourceUrl, key: 'verify' }), []);
  const delivered = await call('drainSourceInteractions', { bookSourceUrl: jsSourceUrl });
  assert.equal(delivered.actions.length, 1);
  const action = delivered.actions[0];
  assert.equal(action.type, 'browser');
  assert.equal(action.bookSourceUrl, jsSourceUrl);
  assert.equal(action.url, `${base}/interaction-page`);
  assert.equal(action.title, 'Stored verification');
  assert.equal(Object.hasOwn(action, 'html'), false);
  assert.equal((await call('drainSourceInteractions', { bookSourceUrl: jsSourceUrl })).actions.length, 0);
  const opened = await call('openSourceBrowser', { bookSourceUrl: jsSourceUrl, interactionId: action.id });
  sessionId = opened.id;
  const initialScreenshot = screenshot(opened);
  assert.equal(opened.url, `${base}/interaction-page`);
  assert.equal(opened.title, 'Stored interaction login');
  let clicked = await call('sourceBrowserAction', { id: sessionId, action: 'click', x: 140, y: 56 });
  for (let attempt = 0; clicked.title !== 'Verified interaction' && attempt < 10; attempt++) {
    clicked = await call('sourceBrowserAction', { id: sessionId, action: 'refresh' });
  }
  assert.equal(clicked.title, 'Verified interaction');
  assert.notDeepEqual(screenshot(clicked), initialScreenshot, 'Clicking login must change the visible page');
  await call('closeSourceBrowser', { id: sessionId });
  sessionId = undefined;
  assert.match((await call('getBookSourceCookies', { bookSourceUrl: jsSourceUrl })).cookie, /verified=yes/);
  const retry = await call('searchBook', { bookSourceUrl: jsSourceUrl, key: 'verify' });
  assert.equal(retry.length, 1);
  assert.equal(retry[0].name, 'Authorized retry ready');
  assert.equal((await call('drainSourceInteractions', { bookSourceUrl: jsSourceUrl })).actions.length, 0);
  assert.equal(requests.filter((item) => item.path === '/interaction-page' || item.path === '/wrong-login').length, 0);
  results.push({ case: 'startBrowser -> drain -> interactionId -> screenshot login click -> cookie return -> retry', passed: true });

  await call('clearBookSourceCookies', { bookSourceUrl: jsSourceUrl });
  let awaitingSettled = false;
  awaitingRequest = call('searchBook', { bookSourceUrl: jsSourceUrl, key: 'verify-await' })
    .then((data) => { awaitingSettled = true; return data; }, (error) => { awaitingSettled = true; throw error; });
  // Attach a rejection handler while the independent browser interaction is being performed.
  awaitingRequest.catch(() => {});
  let waitingAction;
  const deadline = Date.now() + 10000;
  while (!waitingAction && !awaitingSettled && Date.now() < deadline) {
    const waiting = await call('drainSourceInteractions', { bookSourceUrl: jsSourceUrl });
    waitingAction = waiting.actions.find((item) => item.title === 'Await verification');
    if (!waitingAction) await new Promise((resolve) => setTimeout(resolve, 100));
  }
  if (awaitingSettled) await awaitingRequest;
  assert.ok(waitingAction, 'The suspended script must deliver its verification action');
  assert.equal(waitingAction.waiting, true);
  assert.equal(awaitingSettled, false, 'The source call must wait for the manual verification result');
  const waitingSession = await call('openSourceBrowser', { bookSourceUrl: jsSourceUrl, interactionId: waitingAction.id });
  sessionId = waitingSession.id;
  screenshot(waitingSession);
  let verifiedSession = await call('sourceBrowserAction', { id: sessionId, action: 'click', x: 140, y: 56 });
  for (let attempt = 0; verifiedSession.title !== 'Verified interaction' && attempt < 10; attempt++) {
    verifiedSession = await call('sourceBrowserAction', { id: sessionId, action: 'refresh' });
  }
  assert.equal(verifiedSession.title, 'Verified interaction');
  screenshot(verifiedSession);
  const closedWaitingSession = await call('closeSourceBrowser', { id: sessionId });
  assert.equal(Object.hasOwn(closedWaitingSession, 'body'), false, 'Verified HTML must be delivered to the source script only');
  sessionId = undefined;
  const resumed = await awaitingRequest;
  awaitingRequest = undefined;
  assert.equal(resumed.length, 1);
  assert.equal(resumed[0].name, 'Await resumed ready');
  assert.match((await call('getBookSourceCookies', { bookSourceUrl: jsSourceUrl })).cookie, /verified=yes/);
  results.push({ case: 'startBrowserAwait pauses original script and resumes with the verified page after session close', passed: true });

  let captchaSettled = false;
  awaitingRequest = call('searchBook', { bookSourceUrl: jsSourceUrl, key: 'verification-code' })
    .then((data) => { captchaSettled = true; return data; }, (error) => { captchaSettled = true; throw error; });
  awaitingRequest.catch(() => {});
  let captchaAction;
  const captchaDeadline = Date.now() + 10000;
  while (!captchaAction && !captchaSettled && Date.now() < captchaDeadline) {
    const waiting = await call('drainSourceInteractions', { bookSourceUrl: jsSourceUrl });
    captchaAction = waiting.actions.find((item) => item.title === '图片验证码');
    if (!captchaAction) await new Promise((resolve) => setTimeout(resolve, 100));
  }
  if (captchaSettled) await awaitingRequest;
  assert.ok(captchaAction, 'The source must queue an image-code interaction');
  assert.equal(captchaAction.waiting, true);
  assert.equal(captchaSettled, false);
  const captchaPage = await call('openSourceBrowser', { bookSourceUrl: jsSourceUrl, interactionId: captchaAction.id });
  sessionId = captchaPage.id;
  assert.equal(captchaPage.title, '图片验证码');
  const captchaScreenshot = screenshot(captchaPage);
  const typedCaptcha = await call('sourceBrowserAction', { id: sessionId, action: 'type', text: 'A9K4' });
  assert.notDeepEqual(screenshot(typedCaptcha), captchaScreenshot, 'Autofocused typing must update the visible captcha input');
  const captchaClosed = await call('closeSourceBrowser', { id: sessionId });
  sessionId = undefined;
  assert.equal(Object.hasOwn(captchaClosed, 'body'), false);
  const captchaResult = await awaitingRequest;
  awaitingRequest = undefined;
  assert.equal(captchaResult.length, 1);
  assert.equal(captchaResult[0].name, 'Captcha resumed ready');
  assert.equal(captchaResult[0].author, 'A9K4', 'The original source script must receive the typed verification code');
  const imageRequest = requests.find((item) => item.path === '/captcha.svg');
  assert.ok(imageRequest, 'The browser must load the actual captcha image');
  assert.equal(imageRequest.header, runId);
  assert.equal(imageRequest.status, 200);
  results.push({ case: 'getVerificationCode -> actual image -> autofocus type -> close -> original script receives code', passed: true });
  process.stdout.write(`${JSON.stringify({ api: apiAddress.origin, results, browserNavigatorUserAgent: jsBooks[0].author, fixtureRequests: requests.length }, null, 2)}\n`);
} catch (error) {
  failure = error;
  process.stderr.write(`${JSON.stringify({ results, fixtureRequests: requests }, null, 2)}\n`);
} finally {
  const cleanupErrors = [];
  if (sessionId) await call('closeSourceBrowser', { id: sessionId }).catch((error) => cleanupErrors.push(error));
  for (const sourceUrl of createdSources.reverse()) {
    await call('clearBookSourceCookies', { bookSourceUrl: sourceUrl }).catch((error) => cleanupErrors.push(error));
    await call('drainSourceInteractions', { bookSourceUrl: sourceUrl }).catch((error) => cleanupErrors.push(error));
    await call('deleteBookSource', { bookSourceUrl: sourceUrl }).catch((error) => cleanupErrors.push(error));
  }
  if (fixture.listening) await new Promise((resolve) => { fixture.close(resolve); fixture.closeAllConnections(); });
  await client.dispose();
  if (cleanupErrors.length) {
    process.stderr.write(`Fixture cleanup failed: ${cleanupErrors.map((error) => error.message).join('; ')}\n`);
    failure ||= new AggregateError(cleanupErrors, 'Fixture cleanup failed');
  }
}
if (failure) throw failure;
