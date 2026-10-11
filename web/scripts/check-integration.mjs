import { chromium } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { mkdir, readFile } from 'node:fs/promises';
const frontend = 'http://127.0.0.1:4173';
const apiBase = 'http://127.0.0.1:18080/reader3';
const sourceUrl = `https://frontend-integration.invalid/${randomUUID()}`;
const name = 'React联调书源';
const bookName = 'React联调阅读';
const config = { bookSourceUrl: sourceUrl, bookSourceName: name, bookSourceGroup: '回归验证' };
const script = `var config=${JSON.stringify(config)};
function search(key,page){return [{bookUrl:config.bookSourceUrl+'/book',name:'${bookName}',author:'测试作者'}];}
function getBookInfo(book){return {bookUrl:config.bookSourceUrl+'/book',name:'${bookName}',author:'测试作者',tocUrl:config.bookSourceUrl+'/toc'};}
function getChapters(book){return [{title:'第一章 联调',url:config.bookSourceUrl+'/one'},{title:'第二章 完整缓存',url:config.bookSourceUrl+'/two'},{title:'第三章 导出',url:config.bookSourceUrl+'/three'}];}
function getContent(chapter,book,next){return chapter.title+'\\n实际 JavaScript 书源返回的完整正文，阅读与导出都能使用。';}
function loginUi(state){return {rows:state.sent?[{name:'用户名',key:'username',type:'text'},{name:'验证码',key:'code',type:'text',value:'2468'}]:[{name:'用户名',key:'username',type:'text'},{name:'获取验证码',type:'button',action:'sendCode'}]};}
function loginAction(action,state,form){if(action==='sendCode')return {state:{sent:true}};return form.username&&form.code==='2468'?{login:form,state:state,close:true}:{state:state,error:{code:'验证码错误'}};}
`;
const browser = await chromium.launch({ channel: process.platform === 'win32' ? 'msedge' : undefined, headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const errors = [];
page.on('pageerror', (error) => errors.push(error.message));
page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
const call = async (endpoint, body) => { const response = await fetch(`${apiBase}/${endpoint}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }); const result = await response.json(); if (!result.isSuccess) throw new Error(`${endpoint}: ${result.errorMsg}`); return result.data; };
try {
  await mkdir('test-results', { recursive: true });
  await mkdir('screenshots', { recursive: true });
  console.log('parse probe', (await call('parseBookSource', { content: script })).map((source) => ({ name: source.bookSourceName, url: source.bookSourceUrl })));
  await page.goto(`${frontend}/#/sources`);
  await page.getByRole('button', { name: '导入书源', exact: true }).first().click();
  await page.getByLabel('书源 JSON / JavaScript').fill(script);
  await page.getByRole('button', { name: '预览书源', exact: true }).click();
  await page.getByRole('button', { name: '导入 1 个书源', exact: true }).click();
  const row = page.getByRole('row').filter({ hasText: sourceUrl });
  await row.waitFor();
  await page.screenshot({ path: 'screenshots/sources-integration-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: 'screenshots/sources-integration-mobile.png', fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await row.getByRole('button', { name: /更多操作/ }).click();
  await page.getByRole('menuitem', { name: '书源登录', exact: true }).click();
  await page.getByLabel('用户名', { exact: true }).fill('reader');
  await page.getByRole('button', { name: '获取验证码', exact: true }).click();
  if (await page.getByLabel('验证码', { exact: true }).inputValue() !== '2468') throw new Error('Dynamic field default missing');
  await page.getByRole('button', { name: '提交登录', exact: true }).click();
  await page.getByRole('dialog').waitFor({ state: 'hidden' });
  console.log('real script import and dynamic login passed');
  await page.goto(`${frontend}/#/search`);
  await page.getByLabel('搜索书源', { exact: true }).selectOption(sourceUrl);
  await page.getByLabel('书名 / 作者', { exact: true }).fill(bookName);
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await page.getByRole('heading', { name: bookName, exact: true }).click();
  await page.getByRole('button', { name: '加入书架', exact: true }).click();
  await page.getByRole('button', { name: '开始阅读', exact: true }).click();
  await page.getByText('实际 JavaScript 书源返回的完整正文，阅读与导出都能使用。', { exact: true }).waitFor();
  await page.screenshot({ path: 'screenshots/reader-integration.png', fullPage: true });
  console.log('real search, shelf, catalog and content passed');
  await page.getByRole('button', { name: '阅读设置', exact: true }).click();
  await page.screenshot({ path: 'screenshots/reading-settings.png', fullPage: true });
  await page.getByRole('button', { name: '缓存与导出', exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: 'screenshots/reading-settings-download.png', fullPage: true });
  await page.getByRole('button', { name: '缓存与导出', exact: true }).click();
  await page.screenshot({ path: 'screenshots/cache-download.png', fullPage: true });
  const txtEvent = page.waitForEvent('download');
  await page.getByRole('button', { name: '缓存全部并导出', exact: true }).click();
  const txt = await txtEvent;
  await txt.saveAs('test-results/integration-book.txt');
  const txtBody = await readFile('test-results/integration-book.txt', 'utf8');
  for (const text of ['第一章 联调', '第二章 完整缓存', '第三章 导出']) if (!txtBody.includes(text)) throw new Error(`Missing export content ${text}`);
  await page.getByText('完整书籍已下载。', { exact: true }).waitFor();
  await page.screenshot({ path: 'screenshots/cache-download-completed.png', fullPage: true });
  await page.getByLabel('导出格式', { exact: true }).selectOption('epub');
  const epubEvent = page.waitForEvent('download');
  await page.getByRole('button', { name: '缓存全部并导出', exact: true }).click();
  const epub = await epubEvent;
  await epub.saveAs('test-results/integration-book.epub');
  const epubBody = await readFile('test-results/integration-book.epub');
  if (epubBody.subarray(0, 2).toString() !== 'PK') throw new Error('Export was not an EPUB archive');
  console.log('real complete cache, TXT and EPUB downloads passed', JSON.stringify({ txt: txt.suggestedFilename(), epub: epub.suggestedFilename(), txtBytes: txtBody.length, epubBytes: epubBody.length, errors }));
  if (errors.length) throw new Error(errors.join('\n'));
} catch (error) {
  await page.screenshot({ path: 'screenshots/integration-failure.png', fullPage: true });
  console.log('failure page', await page.locator('body').innerText());
  throw error;
} finally {
  await browser.close();
  await call('deleteBooks', [{ bookUrl: `${sourceUrl}/book`, name: bookName, author: '测试作者' }]);
  await call('deleteBookSources', [{ ...config, mainJs: script }]);
}
