import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { Home, Disc3, Users, LogOut } from 'lucide-react';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { PlayerBar } from '@/features/player/components';
import { useCurrentTrack } from '@/features/player/stores/player.store';
import { cn } from '@/shared/utils/cn';

export function RootLayout() {
  const [loading, setLoading] = useState(true);
  const isAuthenticated = useAuthStore(state => state.isAuthenticated);
  const restoreSession = useAuthStore(state => state.restoreSession);
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => {
    const init = async () => {
      const restored = await restoreSession();
      setLoading(false);
      if (!restored && location.pathname !== '/login') {
        navigate('/login');
      }
    };
    void init();
  }, []);

  useEffect(() => {
    if (!loading && isAuthenticated && location.pathname === '/login') {
      navigate('/');
    }
  }, [loading, isAuthenticated, location.pathname, navigate]);

  const isLoginPage = location.pathname === '/login';
  const showSidebar = isAuthenticated && !isLoginPage;
  const currentTrack = useCurrentTrack();
  const showPlayer = isAuthenticated && !isLoginPage && currentTrack;

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center bg-bg-primary">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-accent border-t-transparent" />
      </div>
    );
  }

  return (
    <div className="flex min-h-full">
      {showSidebar && <Sidebar />}
      <main
        className={cn(
          'min-h-full flex-1',
          showSidebar && 'md:ml-sidebar',
          showPlayer && 'pb-20',
          isAuthenticated &&
            !isLoginPage &&
            'pb-[calc(theme(spacing.bottom-tab)+theme(spacing.safe-b))] md:pb-0',
          showPlayer &&
            isAuthenticated &&
            'pb-[calc(5rem+theme(spacing.bottom-tab)+theme(spacing.safe-b))] md:pb-20'
        )}
      >
        <Outlet />
      </main>
      {isAuthenticated && !isLoginPage && <BottomTabBar />}
      {showPlayer && <PlayerBar />}
    </div>
  );
}

function Sidebar() {
  const logout = useAuthStore(state => state.logout);
  const location = useLocation();

  const navItems = [
    { href: '/', label: 'Home', icon: Home },
    { href: '/albums', label: 'Albums', icon: Disc3 },
    { href: '/artists', label: 'Artists', icon: Users },
  ];

  return (
    <aside className="fixed left-0 top-0 hidden h-full w-sidebar flex-col border-r border-border bg-bg-secondary md:flex">
      <div className="p-lg">
        <h1 className="text-xl font-bold text-accent">Yaytsa</h1>
      </div>
      <nav className="flex-1 px-sm">
        {navItems.map(item => (
          <a
            key={item.href}
            href={item.href}
            data-testid={
              item.href === '/' ? 'nav-home' : item.href === '/albums' ? 'nav-albums' : undefined
            }
            className={cn(
              'flex items-center gap-md rounded-md px-md py-sm transition-colors',
              'hover:bg-bg-hover',
              location.pathname === item.href && 'bg-bg-tertiary text-accent'
            )}
          >
            <item.icon className="h-5 w-5" />
            <span>{item.label}</span>
          </a>
        ))}
      </nav>
      <div className="border-t border-border p-md">
        <button
          onClick={() => void logout()}
          className="flex w-full items-center gap-md rounded-md px-md py-sm text-text-secondary transition-colors hover:bg-bg-hover hover:text-error"
        >
          <LogOut className="h-5 w-5" />
          <span>Logout</span>
        </button>
      </div>
    </aside>
  );
}

function BottomTabBar() {
  const logout = useAuthStore(state => state.logout);
  const location = useLocation();

  const tabs = [
    { href: '/', label: 'Home', icon: Home },
    { href: '/albums', label: 'Albums', icon: Disc3 },
    { href: '/artists', label: 'Artists', icon: Users },
  ];

  return (
    <nav className="pb-safe fixed bottom-0 left-0 right-0 z-bottom-tab flex border-t border-border bg-bg-secondary md:hidden">
      {tabs.map(tab => (
        <a
          key={tab.href}
          href={tab.href}
          data-testid={
            tab.href === '/' ? 'nav-home' : tab.href === '/albums' ? 'nav-albums' : undefined
          }
          className={cn(
            'flex min-h-touch flex-1 flex-col items-center justify-center gap-1 py-2',
            'touch-manipulation text-text-secondary transition-colors',
            location.pathname === tab.href && 'text-accent'
          )}
        >
          <tab.icon className="h-5 w-5" />
          <span className="text-xs">{tab.label}</span>
        </a>
      ))}
      <button
        onClick={() => void logout()}
        className="flex min-h-touch flex-1 touch-manipulation flex-col items-center justify-center gap-1 py-2 text-text-secondary hover:text-error"
      >
        <LogOut className="h-5 w-5" />
        <span className="text-xs">Logout</span>
      </button>
    </nav>
  );
}
