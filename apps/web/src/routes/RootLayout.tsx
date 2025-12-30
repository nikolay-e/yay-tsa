import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useEffect, useState, useRef } from 'react';
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
  const sessionRestored = useRef(false);

  useEffect(() => {
    if (sessionRestored.current) return;
    sessionRestored.current = true;

    const init = async () => {
      const restored = await restoreSession();
      setLoading(false);
      if (!restored && location.pathname !== '/login') {
        navigate('/login');
      }
    };
    void init();
  }, [restoreSession, location.pathname, navigate]);

  useEffect(() => {
    if (loading) return;
    if (!isAuthenticated && location.pathname !== '/login') {
      navigate('/login');
    } else if (isAuthenticated && location.pathname === '/login') {
      navigate('/');
    }
  }, [loading, isAuthenticated, location.pathname, navigate]);

  const isLoginPage = location.pathname === '/login';
  const showSidebar = isAuthenticated && !isLoginPage;
  const currentTrack = useCurrentTrack();
  const showPlayer = isAuthenticated && !isLoginPage && currentTrack;

  if (loading) {
    return (
      <div className="bg-bg-primary flex h-full items-center justify-center">
        <div className="border-accent h-8 w-8 animate-spin rounded-full border-2 border-t-transparent" />
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-screen">
      {showSidebar && <Sidebar />}
      <main
        className={cn(
          'h-full min-h-screen flex-1 overflow-y-auto',
          showSidebar && 'md:ml-sidebar',
          showPlayer && 'pb-20',
          isAuthenticated &&
            !isLoginPage &&
            'pb-[calc(var(--spacing-bottom-tab)+var(--spacing-safe-b))] md:pb-0',
          showPlayer &&
            isAuthenticated &&
            'pb-[calc(5rem+var(--spacing-bottom-tab)+var(--spacing-safe-b))] md:pb-20'
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
    <aside className="w-sidebar border-border bg-bg-secondary fixed top-0 left-0 hidden h-full flex-col border-r md:flex">
      <div className="p-6">
        <h1 className="text-accent text-xl font-bold">Yaytsa</h1>
      </div>
      <nav className="flex-1 px-2">
        {navItems.map(item => (
          <a
            key={item.href}
            href={item.href}
            data-testid={
              item.href === '/' ? 'nav-home' : item.href === '/albums' ? 'nav-albums' : undefined
            }
            className={cn(
              'flex items-center gap-4 rounded-md px-4 py-2 transition-colors',
              'hover:bg-bg-hover',
              location.pathname === item.href && 'bg-bg-tertiary text-accent'
            )}
          >
            <item.icon className="h-5 w-5" />
            <span>{item.label}</span>
          </a>
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
    <nav className="pb-safe z-bottom-tab border-border bg-bg-secondary fixed right-0 bottom-0 left-0 flex border-t md:hidden">
      {tabs.map(tab => (
        <a
          key={tab.href}
          href={tab.href}
          data-testid={
            tab.href === '/' ? 'nav-home' : tab.href === '/albums' ? 'nav-albums' : undefined
          }
          className={cn(
            'min-h-touch flex flex-1 flex-col items-center justify-center gap-1 py-2',
            'text-text-secondary touch-manipulation transition-colors',
            location.pathname === tab.href && 'text-accent'
          )}
        >
          <tab.icon className="h-5 w-5" />
          <span className="text-xs">{tab.label}</span>
        </a>
      ))}
      <button
        onClick={() => void logout()}
        className="min-h-touch text-text-secondary hover:text-error flex flex-1 touch-manipulation flex-col items-center justify-center gap-1 py-2"
      >
        <LogOut className="h-5 w-5" />
        <span className="text-xs">Logout</span>
      </button>
    </nav>
  );
}
