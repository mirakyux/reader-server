import { chromium, expect } from '@playwright/test';
import { mkdir } from 'node:fs/promises';

const frontend = process.env.READER_LAYOUT_FRONTEND || 'http://127.0.0.1:5173';
const browser = await chromium.launch({ channel: process.platform === 'win32' ? 'msedge' : undefined, headless: true });
const errors = [];
const results = [];
try {
  await mkdir('screenshots', { recursive: true });
  for (const [name, width, height] of [['desktop', 1440, 1000], ['mobile', 390, 844], ['narrow', 320, 844]]) {
    const page = await browser.newPage({ viewport: { width, height } });
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
    await page.goto(frontend, { waitUntil: 'networkidle' });
    const navigation = page.getByRole('navigation', { name: '主导航', exact: true });
    await expect(navigation.getByRole('link')).toHaveCount(2);
    await expect(navigation.getByRole('button')).toHaveCount(1);
    await expect(page.getByRole('heading', { name: '我的书架', exact: true })).toBeVisible();
    const firstBookCard = page.locator('.book-card').first();
    const firstBook = await firstBookCard.count() ? await firstBookCard.boundingBox() : null;
    if (firstBook && firstBook.y >= height) throw new Error(`${name}: the shelf starts below the first screen`);
    if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error(`${name}: horizontal overflow`);
    await page.screenshot({ path: `screenshots/layout-${name}.png`, fullPage: true });
    await navigation.getByRole('button', { name: '更多', exact: true }).click();
    await expect(page.getByRole('menu')).toBeVisible();
    await expect(page.getByRole('menuitem', { name: '书源工作台', exact: true })).toBeVisible();
    const menu = await page.getByRole('menu').boundingBox();
    if (!menu || menu.x < 0 || menu.x + menu.width > width) throw new Error(`${name}: tools menu is outside the viewport`);
    await page.screenshot({ path: `screenshots/layout-${name}-tools.png`, fullPage: true });
    await page.keyboard.press('Escape');
    await expect(navigation.getByRole('button', { name: '更多', exact: true })).toBeFocused();
    results.push({ device: name, width, firstBookTop: firstBook?.y, toolItems: 7 });
    await page.close();
  }
  if (errors.length) throw new Error(errors.join('\n'));
  console.log(JSON.stringify({ result: 'passed', frontend, views: results, errors }, null, 2));
} finally {
  await browser.close();
}
