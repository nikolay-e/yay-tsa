import { Link, Navigate, Outlet, useLocation } from 'react-router-dom';
import { useEffect, useState, useRef } from 'react';
import {
  Home,
  Disc3,
  Users,
  Music,
  LogOut,
  Settings,
  Menu,
  X,
  type LucideIcon,
} from 'lucide-react';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { PlayerBar } from '@/features/player/components';
import { useCurrentTrack } from '@/features/player/stores/player.store';
import { VersionInfo } from '@/shared/components/VersionInfo';
import { cn } from '@/shared/utils/cn';

interface NavItem {
  href: string;
  label: string;
  icon: LucideIcon;
  testId?: string;
}

const NAV_ITEMS: NavItem[] = [
  { href: '/', label: 'Home', icon: Home, testId: 'nav-home' },
  { href: '/artists', label: 'Artists', icon: Users },
  { href: '/albums', label: 'Albums', icon: Disc3, testId: 'nav-albums' },
  { href: '/songs', label: 'Songs', icon: Music },
  { href: '/settings', label: 'Settings', icon: Settings },
];

type AuthState = 'loading' | 'authenticated' | 'unauthenticated';

export function RootLayout() {
  const [authState, setAuthState] = useState<AuthState>('loading');
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);
  const isAuthenticated = useAuthStore(state => state.isAuthenticated);
  const restoreSession = useAuthStore(state => state.restoreSession);
  const location = useLocation();
  const sessionRestored = useRef(false);
  const currentTrack = useCurrentTrack();

  const isLoginPage = location.pathname === '/login';

  useEffect(() => {
    if (sessionRestored.current) {
      return;
    }
    sessionRestored.current = true;

    const init = async () => {
      const restored = await restoreSession();
      setAuthState(restored ? 'authenticated' : 'unauthenticated');
    };
    void init();
  }, [restoreSession]);

  useEffect(() => {
    if (!sessionRestored.current) {
      return;
    }
    setAuthState(isAuthenticated ? 'authenticated' : 'unauthenticated');
  }, [isAuthenticated]);

  useEffect(() => {
    setIsSidebarOpen(false);
  }, [location.pathname]);

  if (authState === 'loading') {
    return (
      <div className="bg-bg-primary flex h-full items-center justify-center">
        <div className="border-accent h-8 w-8 animate-spin rounded-full border-2 border-t-transparent" />
      </div>
    );
  }

  if (authState === 'unauthenticated' && !isLoginPage) {
    return <Navigate to="/login" replace />;
  }

  if (authState === 'authenticated' && isLoginPage) {
    return <Navigate to="/" replace />;
  }

  const showNavigation = authState === 'authenticated' && !isLoginPage;
  const showPlayer = authState === 'authenticated' && !isLoginPage && currentTrack;

  return (
    <div className="flex h-full min-h-screen">
      {showNavigation && (
        <>
          <Sidebar
            isOpen={isSidebarOpen}
            onClose={() => setIsSidebarOpen(false)}
            hasPlayer={!!showPlayer}
          />
          {isSidebarOpen && (
            <div
              className="fixed inset-0 z-40 bg-black/50"
              onClick={() => setIsSidebarOpen(false)}
            />
          )}
        </>
      )}
      <main className={cn('h-full min-h-screen flex-1 overflow-y-auto', showPlayer && 'pb-20')}>
        {showNavigation && (
          <button
            onClick={() => setIsSidebarOpen(true)}
            className="bg-bg-secondary fixed top-4 left-4 z-30 rounded-full p-2 shadow-lg"
            aria-label="Open menu"
          >
            <Menu className="h-6 w-6" />
          </button>
        )}
        <Outlet />
      </main>
      {showPlayer && <PlayerBar />}
    </div>
  );
}

interface SidebarProps {
  isOpen: boolean;
  onClose: () => void;
  hasPlayer: boolean;
}

function Sidebar({ isOpen, onClose, hasPlayer }: SidebarProps) {
  const logout = useAuthStore(state => state.logout);
  const location = useLocation();

  return (
    <aside
      className={cn(
        'w-sidebar border-border bg-bg-secondary fixed top-0 left-0 z-50 flex h-full flex-col border-r transition-transform duration-300',
        isOpen ? 'translate-x-0' : '-translate-x-full',
        hasPlayer && 'pb-20'
      )}
    >
      <div className="flex items-center justify-between p-6">
        <h1 className="text-accent text-xl font-bold">Yaytsa</h1>
        <button
          onClick={onClose}
          className="hover:bg-bg-hover rounded-full p-1"
          aria-label="Close menu"
        >
          <X className="h-5 w-5" />
        </button>
      </div>
      <nav className="flex-1 px-2">
        {NAV_ITEMS.map(item => (
          <Link
            key={item.href}
            to={item.href}
            data-testid={item.testId}
            className={cn(
              'flex items-center gap-4 rounded-md px-4 py-2 transition-colors',
              'hover:bg-bg-hover',
              location.pathname === item.href && 'bg-bg-tertiary text-accent'
            )}
          >
            <item.icon className="h-5 w-5" />
            <span>{item.label}</span>
          </Link>
        ))}
      </nav>
      <div className="border-border border-t p-4">
        <button
          onClick={() => void logout()}
          className="text-text-secondary hover:bg-bg-hover hover:text-error flex w-full items-center gap-4 rounded-md px-4 py-2 transition-colors"
        >
          <LogOut className="h-5 w-5" />
          <span>Logout</span>
        </button>
        <VersionInfo />
      </div>
    </aside>
  );
}
