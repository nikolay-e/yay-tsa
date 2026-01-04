import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { cn } from '@/shared/utils/cn';

export function LoginPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(false);
  const [error, setError] = useState('');

  const login = useAuthStore(state => state.login);
  const isLoading = useAuthStore(state => state.isLoading);
  const navigate = useNavigate();

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');

    if (!username || !password) {
      setError('Please fill in all fields');
      return;
    }

    try {
      await login(username, password, { rememberMe });
      navigate('/', { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed');
    }
  };

  return (
    <div className="flex min-h-full items-center justify-center p-6">
      <div className="w-full max-w-sm">
        <div className="mb-8 text-center">
          <h1 className="text-accent mb-2 text-3xl font-bold">Yaytsa</h1>
          <p className="text-text-secondary">Sign in to your media server</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          {error && (
            <div className="bg-error/10 border-error/20 text-error rounded-md border p-4 text-sm">
              {error}
            </div>
          )}

          <div className="space-y-1">
            <label htmlFor="username" className="text-text-secondary block text-sm font-medium">
              Username
            </label>
            <input
              id="username"
              type="text"
              value={username}
              onChange={e => setUsername(e.target.value)}
              placeholder="Enter your username"
              autoComplete="username"
              className={cn(
                'border-border bg-bg-secondary w-full rounded-sm border px-4 py-2',
                'text-text-primary placeholder:text-text-tertiary',
                'focus:border-accent transition-colors'
              )}
              disabled={isLoading}
            />
          </div>

          <div className="space-y-1">
            <label htmlFor="password" className="text-text-secondary block text-sm font-medium">
              Password
            </label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              placeholder="Enter your password"
              autoComplete="current-password"
              className={cn(
                'border-border bg-bg-secondary w-full rounded-sm border px-4 py-2',
                'text-text-primary placeholder:text-text-tertiary',
                'focus:border-accent transition-colors'
              )}
              disabled={isLoading}
            />
          </div>

          <div className="flex items-center gap-2">
            <input
              id="rememberMe"
              type="checkbox"
              checked={rememberMe}
              onChange={e => setRememberMe(e.target.checked)}
              className="accent-accent h-4 w-4"
              disabled={isLoading}
            />
            <label htmlFor="rememberMe" className="text-text-secondary text-sm">
              Remember me
            </label>
          </div>

          <button
            type="submit"
            disabled={isLoading}
            className={cn(
              'bg-accent w-full rounded-md px-4 py-2 font-medium text-white',
              'hover:bg-accent-hover touch-manipulation transition-colors',
              'disabled:cursor-not-allowed disabled:opacity-50'
            )}
          >
            {isLoading ? (
              <span className="flex items-center justify-center gap-2">
                <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" />
                Signing in...
              </span>
            ) : (
              'Sign In'
            )}
          </button>
        </form>
      </div>
    </div>
  );
}
