import { describe, expect, it } from 'vitest';
import { parseDanmaku, parseSubtitles, visibleTimedText } from '../src/lib/media';
describe('FU video timed text', () => {
  it('parses WebVTT/SRT identifiers and settings, shows cues only in their playback range', () => {
    const cues = parseSubtitles('WEBVTT\n\nintro\n00:00:01.500 --> 00:00:03.000 align:start\n<b>开头</b> &amp; 字幕\n\n2\n00:00:03,000 --> 00:00:05,250\n第二段');
    expect(cues).toEqual([{ start: 1.5, end: 3, text: '开头 & 字幕' }, { start: 3, end: 5.25, text: '第二段' }]);
    expect(visibleTimedText(cues, 1)).toEqual([]); expect(visibleTimedText(cues, 3).map((cue) => cue.text)).toEqual(['第二段']);
  });
  it('parses LRC timestamps and ignores invalid timestamps', () => {
    expect(parseSubtitles('[00:01.20][00:02.40]一句\n[00:05.00]下一句')).toEqual([
      { start: 1.2, end: 2.4, text: '一句' }, { start: 2.4, end: 5, text: '一句' }, { start: 5, end: 13, text: '下一句' },
    ]);
    expect(parseSubtitles('invalid --> 00:00:05\n无效')).toEqual([]);
  });
  it('parses FU Bilibili XML with decoded text, colors and modes; seeking removes old comments', () => {
    const cues = parseDanmaku('<i><d p="2.5,1,25,16711680,0,0,0">红色 &amp; 评论</d><d p="-1,1,25,0">无效</d><d p="20,5,25,16777215">顶部</d></i>');
    expect(cues).toHaveLength(2); expect(cues[0]).toMatchObject({ start: 2.5, color: '#ff0000', mode: 1, text: '红色 & 评论' });
    expect(visibleTimedText(cues, 21).map((cue) => cue.text)).toEqual(['顶部']); expect(visibleTimedText(cues, 0)).toEqual([]);
  });
});
