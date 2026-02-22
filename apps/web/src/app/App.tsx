import React, { lazy, Suspense } from 'react';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { RootLayout } from '@/pages/RootLayout';
import { HomePage } from '@/pages/HomePage';
import { LoginPage } from '@/pages/LoginPage';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { ProtectedRoute } from './routing/ProtectedRoute';

const AlbumsPage = lazy(() => import('@/pages/AlbumsPage').then(m => ({ default: m.AlbumsPage })));
const AlbumDetailPage = lazy(() =>
  import('@/pages/AlbumDetailPage').then(m => ({ default: m.AlbumDetailPage }))
);
const ArtistsPage = lazy(() =>
  import('@/pages/ArtistsPage').then(m => ({ default: m.ArtistsPage }))
);
const ArtistDetailPage = lazy(() =>
  import('@/pages/ArtistDetailPage').then(m => ({ default: m.ArtistDetailPage }))
);
const FavoritesPage = lazy(() =>
  import('@/pages/FavoritesPage').then(m => ({ default: m.FavoritesPage }))
);
const SongsPage = lazy(() => import('@/pages/SongsPage').then(m => ({ default: m.SongsPage })));
const SettingsPage = lazy(() =>
  import('@/pages/SettingsPage').then(m => ({ default: m.SettingsPage }))
);

function LazyRoute({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={<LoadingSpinner />}>{children}</Suspense>;
}

const router = createBrowserRouter([
  {
    path: '/',
    element: <RootLayout />,
    children: [
      {
        index: true,
        element: (
          <ProtectedRoute>
            <HomePage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'login',
        element: <LoginPage />,
      },
      {
        path: 'albums',
        element: (
          <ProtectedRoute>
            <LazyRoute>
              <AlbumsPage />
            </LazyRoute>
          </ProtectedRoute>
        ),
      },
      {
        path: 'albums/:id',
        element: (
          <ProtectedRoute>
            <LazyRoute>
              <AlbumDetailPage />
            </LazyRoute>
          </ProtectedRoute>
        ),
      },
      {
        path: 'artists',
        element: (
          <ProtectedRoute>
            <LazyRoute>
              <ArtistsPage />
            </LazyRoute>
          </ProtectedRoute>
        ),
      },
      {
        path: 'artists/:id',
        element: (
          <ProtectedRoute>
            <LazyRoute>
              <ArtistDetailPage />
            </LazyRoute>
          </ProtectedRoute>
        ),
      },
      {
        path: 'favorites',
        element: (
          <ProtectedRoute>
            <LazyRoute>
              <FavoritesPage />
            </LazyRoute>
          </ProtectedRoute>
        ),
      },
      {
        path: 'songs',
        element: (
          <ProtectedRoute>
            <LazyRoute>
              <SongsPage />
            </LazyRoute>
          </ProtectedRoute>
        ),
      },
      {
        path: 'settings',
        element: (
          <ProtectedRoute>
            <LazyRoute>
              <SettingsPage />
            </LazyRoute>
          </ProtectedRoute>
        ),
      },
    ],
  },
]);

export function App() {
  return <RouterProvider router={router} />;
}
