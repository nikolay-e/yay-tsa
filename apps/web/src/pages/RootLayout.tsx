import { Link, Navigate, Outlet, useLocation } from 'react-router-dom';
import { useEffect, useState, useRef } from 'react';
import { Home, Disc3, Users, Music, LogOut, Settings, type LucideIcon } from 'lucide-react';
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
      {showNavigation && <Sidebar hasPlayer={!!showPlayer} />}
      <main
        className={cn(
          'h-full min-h-screen flex-1 overflow-y-auto',
          showNavigation && 'md:ml-sidebar',
          showNavigation && (showPlayer ? 'pb-32' : 'pb-14'),
          showNavigation && (showPlayer ? 'md:pb-20' : 'md:pb-0')
        )}
      >
        <Outlet />
      </main>
      {showNavigation && <BottomTabBar hasPlayer={!!showPlayer} />}
      {showPlayer && <PlayerBar />}
    </div>
  );
}

interface SidebarProps {
  hasPlayer: boolean;
}

function Sidebar({ hasPlayer }: SidebarProps) {
  const logout = useAuthStore(state => state.logout);
  const location = useLocation();

  return (
    <aside
      data-testid="sidebar"
      className={cn(
        'w-sidebar border-border bg-bg-secondary fixed top-0 left-0 z-50 hidden h-full flex-col border-r md:flex',
        hasPlayer && 'pb-20'
      )}
    >
      <div className="p-6">
        <h1 className="text-accent text-xl font-bold">Yaytsa</h1>
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

interface BottomTabBarProps {
  hasPlayer: boolean;
}

function BottomTabBar({ hasPlayer }: BottomTabBarProps) {
  const location = useLocation();

  return (
    <nav
      data-testid="bottom-tab-bar"
      className={cn(
        'border-border bg-bg-secondary z-bottom-tab h-bottom-tab fixed right-0 bottom-0 left-0 flex items-center justify-around border-t md:hidden',
        hasPlayer && 'bottom-20'
      )}
    >
      {NAV_ITEMS.map(item => {
        const isActive = location.pathname === item.href;
        return (
          <Link
            key={item.href}
            to={item.href}
            data-testid={item.testId}
            className={cn(
              'flex flex-1 flex-col items-center gap-1 py-2 transition-colors',
              isActive ? 'text-accent' : 'text-text-secondary'
            )}
          >
            <item.icon className="h-5 w-5" />
            <span className="text-xs">{item.label}</span>
          </Link>
        );
      })}
    </nav>
  );
}
