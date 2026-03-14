import { useEffect, type ReactNode } from 'react';
import { cn } from '@/shared/utils/cn';
import { useFocusTrap } from '@/shared/hooks/useFocusTrap';

type ModalProps = Readonly<{
  isOpen: boolean;
  onClose: () => void;
  ariaLabelledBy: string;
  className?: string;
  backdropClassName?: string;
  preventClose?: boolean;
  children: ReactNode;
}>;

const DEFAULT_BACKDROP = 'flex items-center justify-center bg-black/50';

export function Modal({
  isOpen,
  onClose,
  ariaLabelledBy,
  className,
  backdropClassName,
  preventClose,
  children,
}: ModalProps) {
  const dialogRef = useFocusTrap<HTMLDivElement>(isOpen);

  useEffect(() => {
    if (!isOpen) return;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = '';
    };
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !preventClose) onClose();
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, preventClose, onClose]);

  if (!isOpen) return null;

  return (
    <div
      role="presentation"
      className={cn('z-modal-backdrop fixed inset-0', backdropClassName ?? DEFAULT_BACKDROP)}
      onClick={e => {
        if (e.target === e.currentTarget && !preventClose) onClose();
      }}
    >
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={ariaLabelledBy}
        className={className}
        onClick={e => e.stopPropagation()}
        onKeyDown={e => e.stopPropagation()}
      >
        {children}
      </div>
    </div>
  );
}
