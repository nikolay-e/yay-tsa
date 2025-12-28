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
      navigate('/');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed');
    }
  };

  return (
    <div className="flex min-h-full items-center justify-center p-lg">
      <div className="w-full max-w-sm">
        <div className="mb-xl text-center">
          <h1 className="mb-sm text-3xl font-bold text-accent">Yaytsa</h1>
          <p className="text-text-secondary">Sign in to your media server</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-md">
          {error && (
            <div className="bg-error/10 border-error/20 rounded-md border p-md text-sm text-error">
              {error}
            </div>
          )}

          <div className="space-y-xs">
            <label htmlFor="username" className="block text-sm font-medium text-text-secondary">
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
                'w-full rounded-sm border border-border bg-bg-secondary px-md py-sm',
                'text-text-primary placeholder:text-text-tertiary',
                'transition-colors focus:border-accent'
              )}
              disabled={isLoading}
            />
          </div>

          <div className="space-y-xs">
            <label htmlFor="password" className="block text-sm font-medium text-text-secondary">
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
                'w-full rounded-sm border border-border bg-bg-secondary px-md py-sm',
                'text-text-primary placeholder:text-text-tertiary',
                'transition-colors focus:border-accent'
              )}
              disabled={isLoading}
            />
          </div>

          <div className="flex items-center gap-sm">
            <input
              id="rememberMe"
              type="checkbox"
              checked={rememberMe}
              onChange={e => setRememberMe(e.target.checked)}
              className="h-4 w-4 accent-accent"
              disabled={isLoading}
            />
            <label htmlFor="rememberMe" className="text-sm text-text-secondary">
              Remember me
            </label>
          </div>

          <button
            type="submit"
            disabled={isLoading}
            className={cn(
              'w-full rounded-md bg-accent px-md py-sm font-medium text-white',
              'touch-manipulation transition-colors hover:bg-accent-hover',
              'disabled:cursor-not-allowed disabled:opacity-50'
            )}
          >
            {isLoading ? (
              <span className="flex items-center justify-center gap-sm">
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
