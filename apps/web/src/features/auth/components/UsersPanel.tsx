import { useState } from 'react';
import { UserPlus, Trash2, KeyRound, ShieldCheck, User, Loader2, Copy, Check } from 'lucide-react';
import { AdminService, type UserSummary } from '@yay-tsa/core';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuthStore } from '../stores/auth.store';

function PasswordReveal({ password, label }: Readonly<{ password: string; label: string }>) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    await navigator.clipboard.writeText(password);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="mt-3 rounded-lg border border-green-500/30 bg-green-500/10 p-3">
      <div className="text-text-secondary mb-1 text-xs">{label}</div>
      <div className="flex items-center gap-2">
        <code className="flex-1 rounded bg-black/20 px-2 py-1 font-mono text-sm text-green-400">
          {password}
        </code>
        <button
          onClick={() => void copy()}
          className="text-text-secondary hover:text-text-primary shrink-0 transition-colors"
          aria-label="Copy to clipboard"
        >
          {copied ? <Check className="h-4 w-4 text-green-400" /> : <Copy className="h-4 w-4" />}
        </button>
      </div>
      <div className="text-text-secondary mt-1 text-xs">
        Save this — it won&apos;t be shown again.
      </div>
    </div>
  );
}

function AddUserModal({
  onClose,
  onCreated,
}: Readonly<{
  onClose: () => void;
  onCreated: (password: string) => void;
}>) {
  const client = useAuthStore(state => state.client);
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [isAdmin, setIsAdmin] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: async () => {
      if (!client) throw new Error('Not authenticated');
      const svc = new AdminService(client);
      return svc.createUser({
        Username: username.trim(),
        DisplayName: displayName.trim() || undefined,
        IsAdmin: isAdmin,
      });
    },
    onSuccess: result => {
      onCreated(result.initialPassword);
    },
    onError: (err: Error) => {
      setError(err.message);
    },
  });

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
      <div className="bg-bg-primary border-border w-full max-w-sm rounded-xl border p-6 shadow-xl">
        <h3 className="mb-4 text-lg font-semibold">Add User</h3>

        <div className="space-y-3">
          <div>
            <label htmlFor="new-username" className="text-text-secondary mb-1 block text-sm">
              Username *
            </label>
            <input
              id="new-username"
              type="text"
              value={username}
              onChange={e => setUsername(e.target.value)}
              placeholder="e.g. john"
              className="bg-bg-secondary border-border focus:border-accent w-full rounded-lg border px-3 py-2 text-sm outline-none"
            />
          </div>
          <div>
            <label htmlFor="new-display-name" className="text-text-secondary mb-1 block text-sm">
              Display name
            </label>
            <input
              id="new-display-name"
              type="text"
              value={displayName}
              onChange={e => setDisplayName(e.target.value)}
              placeholder="e.g. John Doe"
              className="bg-bg-secondary border-border focus:border-accent w-full rounded-lg border px-3 py-2 text-sm outline-none"
            />
          </div>
          <label className="flex cursor-pointer items-center gap-2">
            <input
              type="checkbox"
              checked={isAdmin}
              onChange={e => setIsAdmin(e.target.checked)}
              className="accent-accent"
            />
            <span className="text-sm">Admin</span>
          </label>
        </div>

        {error && <div className="text-error mt-3 text-sm">{error}</div>}

        <div className="mt-5 flex justify-end gap-2">
          <button
            onClick={onClose}
            className="text-text-secondary hover:text-text-primary rounded-lg px-4 py-2 text-sm transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={() => mutation.mutate()}
            disabled={!username.trim() || mutation.isPending}
            className="bg-accent hover:bg-accent/90 flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-medium text-white transition-colors disabled:opacity-50"
          >
            {mutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
            Create
          </button>
        </div>
      </div>
    </div>
  );
}

function UserRow({
  user,
  currentUserId,
  onDeleted,
  onPasswordReset,
}: Readonly<{
  user: UserSummary;
  currentUserId: string | null;
  onDeleted: () => void;
  onPasswordReset: (password: string) => void;
}>) {
  const client = useAuthStore(state => state.client);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const deleteMutation = useMutation({
    mutationFn: async () => {
      if (!client) throw new Error('Not authenticated');
      await new AdminService(client).deleteUser(user.Id);
    },
    onSuccess: onDeleted,
  });

  const resetMutation = useMutation({
    mutationFn: async () => {
      if (!client) throw new Error('Not authenticated');
      return new AdminService(client).resetPassword(user.Id);
    },
    onSuccess: onPasswordReset,
  });

  const isSelf = currentUserId === user.Id;

  return (
    <div className="bg-bg-secondary border-border flex items-center gap-3 rounded-lg border px-4 py-3">
      <div className="text-text-secondary shrink-0">
        {user.IsAdmin ? (
          <ShieldCheck className="text-accent h-5 w-5" />
        ) : (
          <User className="h-5 w-5" />
        )}
      </div>

      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate font-medium">{user.Username}</span>
          {user.IsAdmin && <span className="text-accent shrink-0 text-xs font-medium">Admin</span>}
          {isSelf && <span className="text-text-muted shrink-0 text-xs">(you)</span>}
        </div>
        {user.DisplayName && (
          <div className="text-text-secondary truncate text-sm">{user.DisplayName}</div>
        )}
      </div>

      {!isSelf && (
        <div className="flex shrink-0 items-center gap-1">
          <button
            onClick={() => resetMutation.mutate()}
            disabled={resetMutation.isPending}
            aria-label="Reset password"
            className="text-text-secondary hover:text-text-primary rounded-lg p-2 transition-colors disabled:opacity-50"
          >
            {resetMutation.isPending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <KeyRound className="h-4 w-4" />
            )}
          </button>

          {confirmDelete ? (
            <div className="flex items-center gap-1">
              <button
                onClick={() => deleteMutation.mutate()}
                disabled={deleteMutation.isPending}
                className="text-error hover:bg-error/10 rounded-lg px-2 py-1 text-xs font-medium transition-colors disabled:opacity-50"
              >
                {deleteMutation.isPending ? <Loader2 className="h-3 w-3 animate-spin" /> : 'Delete'}
              </button>
              <button
                onClick={() => setConfirmDelete(false)}
                className="text-text-secondary hover:text-text-primary rounded-lg px-2 py-1 text-xs transition-colors"
              >
                Cancel
              </button>
            </div>
          ) : (
            <button
              onClick={() => setConfirmDelete(true)}
              aria-label="Delete user"
              className="text-text-secondary hover:text-error rounded-lg p-2 transition-colors"
            >
              <Trash2 className="h-4 w-4" />
            </button>
          )}
        </div>
      )}
    </div>
  );
}

export function UsersPanel() {
  const client = useAuthStore(state => state.client);
  const userId = useAuthStore(state => state.userId);
  const qc = useQueryClient();

  const [showAddModal, setShowAddModal] = useState(false);
  const [shownPassword, setShownPassword] = useState<{ label: string; password: string } | null>(
    null
  );

  const {
    data: users,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['admin', 'users'],
    queryFn: () => {
      if (!client) throw new Error('Not authenticated');
      return new AdminService(client).listUsers();
    },
    enabled: !!client,
  });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['admin', 'users'] });
  };

  const handleCreated = (password: string) => {
    setShowAddModal(false);
    setShownPassword({ label: 'Initial password for new user:', password });
    invalidate();
  };

  const handlePasswordReset = (password: string) => {
    setShownPassword({ label: 'New password:', password });
  };

  const userSuffix = users?.length === 1 ? '' : 's';
  const userCountText = users ? `${users.length} user${userSuffix}` : '';

  return (
    <div className="p-4">
      <div className="mb-4 flex items-center justify-between">
        <span className="text-text-secondary text-sm">{userCountText}</span>
        <button
          onClick={() => {
            setShownPassword(null);
            setShowAddModal(true);
          }}
          className="bg-accent hover:bg-accent/90 flex items-center gap-2 rounded-lg px-3 py-1.5 text-sm font-medium text-white transition-colors"
        >
          <UserPlus className="h-4 w-4" />
          Add User
        </button>
      </div>

      {shownPassword && (
        <PasswordReveal label={shownPassword.label} password={shownPassword.password} />
      )}

      <div className="mt-3 space-y-2">
        {isLoading && (
          <div className="text-text-secondary flex items-center gap-2 py-4 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            Loading users...
          </div>
        )}

        {error && <div className="text-error text-sm">Failed to load users</div>}

        {users?.map(user => (
          <UserRow
            key={user.Id}
            user={user}
            currentUserId={userId}
            onDeleted={invalidate}
            onPasswordReset={handlePasswordReset}
          />
        ))}
      </div>

      {showAddModal && (
        <AddUserModal onClose={() => setShowAddModal(false)} onCreated={handleCreated} />
      )}
    </div>
  );
}
