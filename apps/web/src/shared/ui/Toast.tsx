import { useEffect, useState } from 'react';
import { X, AlertCircle, CheckCircle, Info, AlertTriangle } from 'lucide-react';
import { cn } from '@/shared/utils/cn';

export type ToastType = 'error' | 'success' | 'info' | 'warning';

interface ToastMessage {
  id: string;
  type: ToastType;
  message: string;
  duration?: number;
}

interface ToastStore {
  toasts: ToastMessage[];
  add: (type: ToastType, message: string, duration?: number) => void;
  remove: (id: string) => void;
}

const toastListeners = new Set<() => void>();
let toasts: ToastMessage[] = [];

function notifyListeners() {
  toastListeners.forEach(listener => listener());
}

export const toast: ToastStore = {
  get toasts() {
    return toasts;
  },
  add(type: ToastType, message: string, duration = 5000) {
    const id = crypto.randomUUID();
    toasts = [...toasts, { id, type, message, duration }];
    notifyListeners();

    if (duration > 0) {
      setTimeout(() => {
        toast.remove(id);
      }, duration);
    }
  },
  remove(id: string) {
    toasts = toasts.filter(t => t.id !== id);
    notifyListeners();
  },
};

function useToasts(): ToastMessage[] {
  const [, forceUpdate] = useState({});

  useEffect(() => {
    const listener = () => forceUpdate({});
    toastListeners.add(listener);
    return () => {
      toastListeners.delete(listener);
    };
  }, []);

  return toasts;
}

const icons = {
  error: AlertCircle,
  success: CheckCircle,
  info: Info,
  warning: AlertTriangle,
};

const styles = {
  error: 'bg-error/10 border-error/30 text-error',
  success: 'bg-success/10 border-success/30 text-success',
  info: 'bg-accent/10 border-accent/30 text-accent',
  warning: 'bg-warning/10 border-warning/30 text-warning',
};

function ToastItem({ item }: { item: ToastMessage }) {
  const Icon = icons[item.type];

  return (
    <div
      className={cn(
        'flex items-start gap-2 rounded-md border p-2 shadow-lg',
        'animate-in slide-in-from-top-2 fade-in duration-200',
        styles[item.type]
      )}
      role="alert"
    >
      <Icon className="mt-0.5 h-4 w-4 shrink-0" />
      <p className="flex-1 text-sm">{item.message}</p>
      <button
        onClick={() => toast.remove(item.id)}
        className="shrink-0 rounded p-1 opacity-70 transition-opacity hover:opacity-100"
        aria-label="Dismiss"
      >
        <X className="h-3 w-3" />
      </button>
    </div>
  );
}

export function ToastContainer() {
  const items = useToasts();

  if (items.length === 0) {
    return null;
  }

  return (
    <div className="z-toast fixed top-[max(1rem,env(safe-area-inset-top,0px))] right-[max(1rem,env(safe-area-inset-right,0px))] flex max-w-sm flex-col gap-2">
      {items.map(item => (
        <ToastItem key={item.id} item={item} />
      ))}
    </div>
  );
}
