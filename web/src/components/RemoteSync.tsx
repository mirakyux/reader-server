import { useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { ActionButton, Field, Toggle, ToggleLabel } from './Controls';
import { Confirm, DataTable, Panel, Toolbar, useAction } from './Common';
import { api } from '../lib/api';
import { useLibrary } from '../context';
import '../rss-sync.css';

interface DavConfig { url: string; username: string; passwordConfigured?: boolean; dailyBackup: boolean; lastBackupDay?: string; lastBackupTime?: number; lastError?: string }
interface RemoteBackup { path: string; name: string; size: number; modified: string }
export function RemoteSync() {
  const [config, setConfig] = useState<DavConfig>({ url: '', username: '', dailyBackup: false }); const [password, setPassword] = useState(''); const [clearPassword, setClearPassword] = useState(false);
  const [backups, setBackups] = useState<RemoteBackup[]>(); const [restoring, setRestoring] = useState<RemoteBackup>(); const [status, setStatus] = useState(''); const { reload } = useLibrary(); const { run, busy } = useAction();
  const load = async () => { const value = await api.get<DavConfig>('sync/webdav/config'); if (typeof value.url === 'string') setConfig(value); };
  useEffect(() => { void run(load); }, [run]);
  const save = async () => { const value = await api.post<DavConfig>('sync/webdav/config', { ...config, ...(clearPassword ? { password: '' } : password ? { password } : {}) }); setConfig(value); setPassword(''); setClearPassword(false); };
  return <Panel><h2>远程 WebDAV 同步</h2><p className="muted">连接已有的 WebDAV，双向同步阅读位置，并保存兼容 FU 的备份。</p><div className="form-stack">
    <Field label="远程 WebDAV 文件夹地址" value={config.url} onChange={(event) => setConfig({ ...config, url: event.target.value })} helperText="例如 https://example.com/dav/legado；凭据只保存到当前用户的服务器空间。" />
    <Field label="WebDAV 用户名" value={config.username} onChange={(event) => setConfig({ ...config, username: event.target.value })} />
    <Field label="WebDAV 密码" type="password" value={password} disabled={clearPassword} onChange={(event) => setPassword(event.target.value)} helperText={config.passwordConfigured ? '已保存密码；留空保持，填写新密码时替换。' : '服务需要认证时填写。'} />
    <ToggleLabel label="清空已保存密码" control={<Toggle checked={clearPassword} onChange={(_, value) => setClearPassword(value)} />} />
    <ToggleLabel label="每天自动备份一次" control={<Toggle checked={config.dailyBackup} onChange={(_, value) => setConfig({ ...config, dailyBackup: value })} />} />
    <Toolbar><ActionButton disabled={busy} onClick={() => void run(save, 'WebDAV 配置已保存')}>保存远程配置</ActionButton><ActionButton disabled={busy || !config.url} variant="outline" onClick={() => void run(async () => { await save(); const value = await api.post<RemoteBackup[]>('sync/webdav/test'); setStatus(`连接成功，文件夹包含 ${value.length} 项。`); }, '远程 WebDAV 连接成功')}>测试连接</ActionButton></Toolbar>
    <Toolbar><ActionButton disabled={busy || !config.url} startIcon={<RefreshCw />} onClick={() => void run(async () => { await save(); const result = await api.post<{ sent: number; received: number }>('sync/webdav/progress'); await reload(); setStatus(`读取 ${result.received} 份远程进度，发送 ${result.sent} 份进度。以较新时间为准，同时间保留较远位置。`); }, '阅读进度已同步')}>同步阅读进度</ActionButton>
      <ActionButton disabled={busy || !config.url} variant="outline" onClick={() => void run(async () => { await save(); const value = await api.post<{ path: string }>('sync/webdav/backup'); setStatus(`已备份：${value.path}`); await load(); }, '备份已上传')}>立即备份</ActionButton>
      <ActionButton disabled={busy || !config.url} variant="ghost" onClick={() => void run(async () => { await save(); setBackups(await api.get<RemoteBackup[]>('sync/webdav/backups')); })}>查看远程备份</ActionButton>
    </Toolbar>{config.lastBackupTime ? <p className="muted">上次备份：{new Date(config.lastBackupTime).toLocaleString('zh-CN')}</p> : null}{config.lastError && <p role="alert">{config.lastError}</p>}{status && <p role="status" className="remote-sync-status">{status}</p>}
    <p className="muted small">自动备份由服务器运行，每天检查并执行一次；页面关闭后仍可工作。自动备份失败可在此查看并手动重试。恢复备份会替换对应实体及配置。</p>
    {backups && <DataTable rows={backups} rowKey={(row) => row.path} columns={[{ title: '备份', render: (row) => row.name }, { title: '大小', render: (row) => `${(row.size / 1024 / 1024).toFixed(1)} MiB` }, { title: '操作', render: (row) => <ActionButton variant="outline" onClick={() => setRestoring(row)}>恢复此备份</ActionButton> }]} />}
  </div>{restoring && <Confirm title={`从「${restoring.name}」恢复？`} description="服务器会先校验文件和路径，恢复后更新书架、书源、收藏、规则及配置。" onClose={() => setRestoring(undefined)} onConfirm={async () => { await api.post('sync/webdav/restore', { path: restoring.path }); await reload(); await load(); }} />}</Panel>;
}
