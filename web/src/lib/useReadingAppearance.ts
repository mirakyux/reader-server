import { useEffect, useState } from 'react';
import { loadReadingFont } from './appearance';
import { notify } from '../components/Common';
import type { ReadingSettings } from '../types';
export function useReadingFont(settings: ReadingSettings): string {
  const [font, setFont] = useState<{ url: string; family: string }>();
  useEffect(() => { let active = true; const url = settings.customFontUrl; if (url) void loadReadingFont(url).then((family) => { if (active) setFont({ url, family }); }).catch(() => { if (active) notify('自定义字体加载失败，请检查字体文件或地址；当前使用系统字体。', 'warning'); }); return () => { active = false; }; }, [settings.customFontUrl]);
  return font && font.url === settings.customFontUrl ? `${font.family}, ${settings.fontFamily}` : settings.fontFamily;
}
