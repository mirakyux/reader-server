import { useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { ActionButton, Alert, Field } from './Controls';
import { Confirm, DataTable, Panel, Toolbar, useAction } from './Common';
import { api } from '../lib/api';
import type { ReadingStats } from '../lib/advanced';
import { useLibrary } from '../context';

interface LegacyRecord { bookName: string; deviceId: string; seconds: number; lastRead: number }
type Stats = ReadingStats & { legacyRecords?: LegacyRecord[]; legacySeconds?: number };
export function ReadingStatisticsPanel() {
  const [stats, setStats] = useState<Stats>(); const [error, setError] = useState(''); const [book, setBook] = useState('');
  const [deleting, setDeleting] = useState<{ title: string; input: Record<string, unknown> }>(); const { books } = useLibrary(); const { run, busy } = useAction();
  const load = async () => { const value = await api.get<Stats>('reading/stats', { timezone: Intl.DateTimeFormat().resolvedOptions().timeZone }); if (Array.isArray(value.days)) { setStats(value); setError(''); } };
  useEffect(() => { void load().catch(() => setError('阅读统计暂未读取，请刷新重试。')); }, []);
  const selected = stats?.books.find((item) => item.bookUrl === book);
  const minutes = (seconds: number) => seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 3600)} 小时 ${Math.floor(seconds / 60) % 60} 分钟`;
  return <Panel><h2>阅读统计</h2><Toolbar><ActionButton disabled={busy} variant="outline" startIcon={<RefreshCw />} onClick={() => void run(load)}>刷新统计</ActionButton>
    <Field select label="统计书籍" value={book} onChange={(event) => setBook(event.target.value)}><option value="">全部书籍</option>{stats?.books.map((item) => <option key={item.bookUrl} value={item.bookUrl}>{books.find((entry) => entry.bookUrl === item.bookUrl)?.name || item.bookName || item.bookUrl}</option>)}</Field>
    <ActionButton variant="ghost" color="error" disabled={busy} onClick={() => setDeleting({ title: book ? '删除此书的网页阅读统计？' : '清空全部阅读统计？', input: book ? { bookUrl: book } : { all: true } })}>删除{book ? '此书记录' : '全部记录'}</ActionButton>
  </Toolbar>{error && <Alert severity="error">{error}</Alert>}{stats && <><div className="stats-summary"><div><strong>{minutes(selected?.seconds ?? stats.totalSeconds)}</strong><span>累计阅读时间</span></div><div><strong>{(selected?.characters ?? stats.totalCharacters).toLocaleString()}</strong><span>网页阅读字数估计</span></div><div><strong>{selected?.sessions ?? stats.totalSessions}</strong><span>网页阅读会话</span></div></div>
    <DataTable rows={(book ? selected?.days || [] : stats.days).slice().reverse()} rowKey={(item) => item.date} columns={[{ title: '日期', render: (item) => item.date }, { title: '阅读时间', render: (item) => minutes(item.seconds) }, { title: '字数估计', render: (item) => item.characters.toLocaleString() }, { title: '会话数', render: (item) => item.sessions }]} />
    {!!stats.legacyRecords?.length && <><h3>FU 历史阅读记录</h3><p className="muted small">FU 保存各设备按书名累计的阅读时长，已计入总时长；原数据没有每日明细与字数。</p><DataTable rows={stats.legacyRecords} rowKey={(row) => `${row.deviceId}:${row.bookName}`} columns={[
      { title: '书名', render: (row) => row.bookName }, { title: '设备', render: (row) => row.deviceId || '未知设备' }, { title: '累计时长', render: (row) => minutes(row.seconds) }, { title: '最后阅读', render: (row) => row.lastRead ? new Date(row.lastRead).toLocaleString('zh-CN') : '未知' },
      { title: '操作', render: (row) => <ActionButton size="sm" variant="ghost" color="error" onClick={() => setDeleting({ title: `删除「${row.bookName}」的 FU 历史记录？`, input: { legacyBookName: row.bookName } })}>删除</ActionButton> }
    ]} /></>}
  </>}<p className="muted small">统计按 {stats?.timezone || '当前时区'} 汇总；后台与闲置时间不计入网页会话。离线记录下次阅读时重试上传。</p>
    {deleting && <Confirm danger title={deleting.title} onClose={() => setDeleting(undefined)} onConfirm={async () => { await api.post('reading/stats/delete', deleting.input); await load(); }} />}
  </Panel>;
}
