export interface TimedText { start: number; end: number; text: string; color?: string; mode?: number }
const seconds = (value: string): number => value.replace(',', '.').split(':').reduce((total, part) => total * 60 + Number(part), 0);
const plain = (value: string) => new DOMParser().parseFromString(value, 'text/html').body.textContent?.trim() || '';
const valid = (cue: TimedText) => Number.isFinite(cue.start) && Number.isFinite(cue.end) && cue.start >= 0 && cue.end > cue.start && !!cue.text;

/** FU video sources use subtitle URLs/text and Bilibili XML comments. Timings stay in seconds. */
export function parseSubtitles(value: string): TimedText[] {
  const text = value.replace(/^\uFEFF/, '').replaceAll('\r', '');
  const cues: TimedText[] = [];
  for (const block of text.split(/\n\s*\n/)) {
    const lines = block.split('\n'); const index = lines.findIndex((line) => line.includes('-->'));
    if (index < 0) continue;
    const times = lines[index].trim().split(/\s+-->\s+/);
    cues.push({ start: seconds(times[0]), end: seconds(times[1]?.split(/\s/)[0] || ''), text: plain(lines.slice(index + 1).join('\n')) });
  }
  if (!cues.length) {
    for (const line of text.split('\n')) {
      const matches = [...line.matchAll(/\[(\d+:\d+(?:\.\d+)?)\]/g)];
      for (const match of matches) cues.push({ start: seconds(match[1]), end: Infinity, text: plain(line.replace(/\[.*?\]/g, '')) });
    }
    cues.sort((a, b) => a.start - b.start);
    for (let index = 0; index < cues.length; index++) cues[index].end = cues[index + 1]?.start || cues[index].start + 8;
  }
  return cues.filter(valid).sort((a, b) => a.start - b.start).slice(0, 10000);
}

export function parseDanmaku(value: string): TimedText[] {
  const document = new DOMParser().parseFromString(value, 'text/xml');
  return [...document.querySelectorAll('d[p]')].slice(0, 10000).map((element) => {
    const fields = (element.getAttribute('p') || '').split(','); const start = Number(fields[0]); const color = Number(fields[3]);
    return { start, end: start + 8, mode: Number(fields[1]), text: element.textContent?.trim() || '',
      color: Number.isInteger(color) && color >= 0 && color <= 0xffffff ? `#${color.toString(16).padStart(6, '0')}` : '#ffffff' };
  }).filter(valid).sort((a, b) => a.start - b.start);
}

export function visibleTimedText(cues: TimedText[], time: number): TimedText[] { return cues.filter((cue) => cue.start <= time && time < cue.end); }
