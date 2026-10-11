import { expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { VideoContent } from '../src/components/MediaContent';
it('video captions and comments follow playback and seeking, and comments can be hidden', () => {
  const { container } = render(<VideoContent book={{ bookUrl: 'https://test/video', name: 'video' }} content="https://test/video.mp4" chapter={{ index: 0, title: '视频', url: 'https://test/chapter', lyric: '00:00:01.000 --> 00:00:03.000\n开头字幕\n\n00:00:20.000 --> 00:00:25.000\n后段字幕', danmaku: '<i><d p="1,1,25,16711680">开头评论</d></i>' }} onEnd={() => {}} />);
  const video = container.querySelector('video')!;
  video.currentTime = 2; fireEvent.timeUpdate(video);
  expect(container.querySelector('.video-subtitles')).toHaveTextContent('开头字幕');
  expect(container.querySelector('.video-danmaku')).toHaveTextContent('开头评论');
  fireEvent.click(screen.getByRole('button', { name: '关闭弹幕' }));
  expect(container.querySelector('.video-danmaku')).toBeEmptyDOMElement();
  video.currentTime = 21; fireEvent.seeked(video);
  expect(container.querySelector('.video-subtitles')).toHaveTextContent('后段字幕');
  expect(container.querySelector('.video-subtitles')).not.toHaveTextContent('开头字幕');
  fireEvent.click(screen.getByRole('button', { name: '开头字幕' }));
  expect(video.currentTime).toBe(1);
});
