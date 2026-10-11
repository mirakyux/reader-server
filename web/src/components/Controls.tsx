import { useId } from 'react';
import type { ComponentProps, ReactNode } from 'react';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Textarea } from './ui/textarea';
import { Label } from './ui/label';
import { Badge } from './ui/badge';
import { Dialog, DialogContent, DialogTitle, DialogFooter } from './ui/dialog';
import { Sheet, SheetContent, SheetTitle } from './ui/sheet';
import { Switch } from './ui/switch';
import { Tabs, TabsList, TabsTrigger } from './ui/tabs';
import { Alert as ShadcnAlert, AlertDescription } from './ui/alert';
import { LoaderCircle } from 'lucide-react';
import { cn } from '../lib/utils';

type ActionProps = Omit<ComponentProps<typeof Button>, 'size'> & { size?: 'sm' | 'default' | 'lg'; startIcon?: ReactNode; endIcon?: ReactNode; fullWidth?: boolean; color?: string; component?: 'label' };
export function ActionButton({ startIcon, endIcon, children, className, fullWidth, color, component, ...props }: ActionProps) { if (component === 'label') return <Button asChild {...props}><label className={cn(className, 'cursor-pointer')}>{startIcon}{children}{endIcon}</label></Button>; return <Button {...props} className={cn(fullWidth && 'w-full', color === 'error' && 'text-destructive', className)}>{startIcon}{children}{endIcon}</Button>; }
export function IconAction({ children, className, color, ...props }: Omit<ActionProps, 'size'> & { size?: 'sm' }) { return <Button variant="ghost" size="icon" {...props} className={cn(color === 'error' && 'text-destructive', className)}>{children}</Button>; }
interface FieldProps { label?: string; value?: string | number; onChange?: (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => void; placeholder?: string; size?: string; fullWidth?: boolean; multiline?: boolean; rows?: number; minRows?: number; maxRows?: number; select?: boolean; children?: ReactNode; type?: string; helperText?: string; error?: boolean; autoFocus?: boolean; disabled?: boolean; onKeyDown?: React.KeyboardEventHandler; sx?: Record<string, unknown>; slotProps?: { htmlInput?: Record<string, unknown> }; className?: string; }
export function Field({ label, value = '', onChange, placeholder, multiline, rows, minRows, select, children, type, helperText, error, autoFocus, disabled, onKeyDown, sx, slotProps, className, fullWidth }: FieldProps) {
  const id = useId(); const attributes = slotProps?.htmlInput || {}; const style = Object.fromEntries(Object.entries(sx || {}).filter(([, value]) => typeof value === 'string' || typeof value === 'number'));
  return <div className={cn('field', fullWidth && 'w-full', className)} style={style}><Label htmlFor={id}>{label}</Label>{select ? <select id={id} className="native-select" value={value} onChange={onChange} disabled={disabled}>{children}</select> : multiline ? <Textarea id={id} value={value} onChange={onChange} placeholder={placeholder} rows={rows || minRows || 3} disabled={disabled} {...attributes} /> : <Input id={id} value={value} onChange={onChange} placeholder={placeholder} type={type || 'text'} autoFocus={autoFocus} disabled={disabled} onKeyDown={onKeyDown} {...attributes} />} {helperText && <small className={error ? 'text-destructive' : 'muted'}>{helperText}</small>}</div>;
}
export function BadgeChip({ label, onClick, color, className, variant }: { label: ReactNode; onClick?: () => void; color?: string; className?: string; variant?: string; size?: string }) { const badge = <Badge variant={variant === 'outline' ? 'outline' : color === 'primary' ? 'default' : 'secondary'} className={cn('badge-chip', className)}>{label}</Badge>; return onClick ? <button onClick={onClick} type="button" className="chip-button">{badge}</button> : badge; }
export function Modal({ open, onClose, children, maxWidth = 'sm', returnFocusRef }: { open: boolean; onClose: () => void; children: ReactNode; fullWidth?: boolean; maxWidth?: 'xs' | 'sm' | 'md' | 'lg'; returnFocusRef?: React.RefObject<HTMLElement | null> }) { return <Dialog open={open} onOpenChange={(value) => { if (!value) onClose(); }}><DialogContent aria-describedby={undefined} onCloseAutoFocus={(event) => { if (returnFocusRef?.current) { event.preventDefault(); returnFocusRef.current.focus(); } }} className={cn('reader-dialog', maxWidth === 'xs' ? 'sm:max-w-md' : maxWidth === 'sm' ? 'sm:max-w-xl' : maxWidth === 'md' ? 'sm:max-w-4xl' : 'sm:max-w-6xl')}>{children}</DialogContent></Dialog>; }
export const ModalTitle = DialogTitle;
export function ModalBody({ children }: { children: ReactNode }) { return <div className="modal-body">{children}</div>; }
export const ModalActions = DialogFooter;
export function SidePanel({ open, onClose, children }: { open: boolean; onClose: () => void; children: ReactNode; anchor?: string; slotProps?: unknown }) { return <Sheet open={open} onOpenChange={(value) => { if (!value) onClose(); }}><SheetContent aria-describedby={undefined} className="reader-sheet sm:max-w-4xl w-full" showCloseButton={false}><SheetTitle className="sr-only">书源编辑器</SheetTitle>{children}</SheetContent></Sheet>; }
export function Toggle({ checked, onChange, inputProps, disabled }: { checked: boolean; onChange: (event: null, checked: boolean) => void; disabled?: boolean; size?: string; inputProps?: { 'aria-label'?: string } }) { return <Switch checked={checked} disabled={disabled} onCheckedChange={(value) => onChange(null, value)} {...inputProps} />; }
export function ToggleLabel({ control, label }: { control: ReactNode; label: ReactNode }) { return <label className="toggle-label">{control}<span>{label}</span></label>; }
export function TabBar<T extends string | number>({ value, onChange, children }: { value: T; onChange: (event: null, value: T) => void; children: ReactNode; variant?: string; scrollButtons?: string }) { return <Tabs value={String(value)} onValueChange={(next) => onChange(null, (typeof value === 'number' ? Number(next) : next) as T)}><TabsList className="reader-tabs">{children}</TabsList></Tabs>; }
export function TabItem({ value, label }: { value: string | number; label: ReactNode }) { return <TabsTrigger value={String(value)}>{label}</TabsTrigger>; }
export function Alert({ children, severity = 'info', className, action }: { children: ReactNode; severity?: string; className?: string; action?: ReactNode }) { return <ShadcnAlert variant={severity === 'error' ? 'destructive' : 'default'} className={cn('reader-alert', className)}><AlertDescription className="flex w-full items-center justify-between gap-4"><div className="min-w-0 flex-1">{children}</div>{action && <div className="shrink-0">{action}</div>}</AlertDescription></ShadcnAlert>; }
export function Spinner({ size = 24 }: { size?: number }) { return <LoaderCircle className="animate-spin text-primary" width={size} height={size} aria-label="加载中" />; }
