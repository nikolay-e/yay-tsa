import { useState } from 'react';
import { Copy, LogOut, X, Loader2, Wifi } from 'lucide-react';
import { cn } from '@/shared/utils/cn';
import { toast } from '@/shared/ui/Toast';
import { useGroupSyncStore } from '../stores/group-sync-store';

export function GroupSyncPanel({
  isOpen,
  onClose,
}: Readonly<{ isOpen: boolean; onClose: () => void }>) {
  const [joinCodeInput, setJoinCodeInput] = useState('');
  const [groupName, setGroupName] = useState('');
  const [isCreating, setIsCreating] = useState(false);
  const [isJoining, setIsJoining] = useState(false);

  const { joinCode, isOwner, members, mode, driftMs, createGroup, joinGroup, leaveGroup } =
    useGroupSyncStore();

  const handleCreate = async () => {
    setIsCreating(true);
    await createGroup(groupName || 'My Group');
    setIsCreating(false);
  };

  const handleJoin = async () => {
    if (!joinCodeInput.trim()) return;
    setIsJoining(true);
    await joinGroup(joinCodeInput.trim());
    setIsJoining(false);
    setJoinCodeInput('');
  };

  const handleLeave = async () => {
    await leaveGroup();
    toast.add('info', 'Left group');
    onClose();
  };

  const copyCode = () => {
    if (joinCode) {
      navigator.clipboard.writeText(joinCode).catch(() => {});
      toast.add('success', 'Code copied');
    }
  };

  if (!isOpen) return null;

  return (
    <div className="bg-bg-primary/80 fixed inset-0 z-50 flex items-end justify-center backdrop-blur-sm md:items-center">
      <div className="bg-bg-secondary border-border w-full max-w-md rounded-t-2xl border p-4 md:rounded-2xl">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-text-primary text-lg font-semibold">Group Listen</h2>
          <button
            type="button"
            onClick={onClose}
            className="text-text-secondary hover:text-text-primary rounded-full p-1"
            aria-label="Close"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {mode === 'group' ? (
          <div className="space-y-4">
            <div className="bg-accent/10 rounded-lg p-3">
              <div className="flex items-center justify-between">
                <span className="text-text-primary text-sm font-medium">
                  {isOwner ? 'Your group' : 'In group'}
                </span>
                <div className="flex items-center gap-2">
                  <span className="text-accent font-mono text-lg font-bold">{joinCode}</span>
                  <button
                    type="button"
                    onClick={copyCode}
                    className="text-text-secondary hover:text-accent rounded p-1"
                    aria-label="Copy code"
                  >
                    <Copy className="h-4 w-4" />
                  </button>
                </div>
              </div>
              <p className="text-text-secondary mt-1 text-xs">Share this code so others can join</p>
            </div>

            <div>
              <h3 className="text-text-secondary mb-2 text-xs font-medium uppercase">
                Members ({members.length})
              </h3>
              <div className="space-y-1">
                {members.map(m => (
                  <div key={m.deviceId} className="flex items-center gap-2 rounded px-2 py-1.5">
                    <Wifi
                      className={cn('h-3 w-3', m.stale ? 'text-text-tertiary' : 'text-success')}
                    />
                    <span className="text-text-primary text-sm">{m.deviceId}</span>
                    {m.stale && <span className="text-text-tertiary text-xs">(reconnecting)</span>}
                  </div>
                ))}
              </div>
            </div>

            {Math.abs(driftMs) > 30 && (
              <p className="text-text-tertiary text-xs">
                Sync drift: {driftMs > 0 ? '+' : ''}
                {driftMs}ms
              </p>
            )}

            <button
              type="button"
              onClick={handleLeave}
              className="text-error hover:bg-error/10 flex w-full items-center justify-center gap-2 rounded-lg px-4 py-2 text-sm transition-colors"
            >
              <LogOut className="h-4 w-4" />
              Leave group
            </button>
          </div>
        ) : (
          <div className="space-y-4">
            <div>
              <span className="text-text-secondary mb-1 block text-xs font-medium">
                Create a group
              </span>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={groupName}
                  onChange={e => setGroupName(e.target.value)}
                  placeholder="Group name (optional)"
                  className="bg-bg-tertiary border-border text-text-primary placeholder:text-text-tertiary flex-1 rounded-lg border px-3 py-2 text-sm"
                />
                <button
                  type="button"
                  onClick={handleCreate}
                  disabled={isCreating}
                  className="bg-accent text-text-on-accent hover:bg-accent-hover shrink-0 rounded-lg px-4 py-2 text-sm font-medium disabled:opacity-50"
                >
                  {isCreating ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Create'}
                </button>
              </div>
            </div>

            <div className="border-border border-t pt-4">
              <span className="text-text-secondary mb-1 block text-xs font-medium">
                Join a group
              </span>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={joinCodeInput}
                  onChange={e => setJoinCodeInput(e.target.value)}
                  placeholder="Enter 6-digit code"
                  maxLength={6}
                  className="bg-bg-tertiary border-border text-text-primary placeholder:text-text-tertiary flex-1 rounded-lg border px-3 py-2 text-center font-mono text-lg tracking-widest"
                />
                <button
                  type="button"
                  onClick={handleJoin}
                  disabled={isJoining || joinCodeInput.length < 6}
                  className="bg-accent text-text-on-accent hover:bg-accent-hover shrink-0 rounded-lg px-4 py-2 text-sm font-medium disabled:opacity-50"
                >
                  {isJoining ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Join'}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
