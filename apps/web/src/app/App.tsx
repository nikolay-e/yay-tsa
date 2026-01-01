import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { RootLayout } from '@/pages/RootLayout';
import { HomePage } from '@/pages/HomePage';
import { LoginPage } from '@/pages/LoginPage';
import { AlbumsPage } from '@/pages/AlbumsPage';
import { AlbumDetailPage } from '@/pages/AlbumDetailPage';
import { ArtistsPage } from '@/pages/ArtistsPage';
import { ArtistDetailPage } from '@/pages/ArtistDetailPage';
import { SettingsPage } from '@/pages/SettingsPage';
import { ProtectedRoute } from './routing/ProtectedRoute';

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
            <AlbumsPage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'albums/:id',
        element: (
          <ProtectedRoute>
            <AlbumDetailPage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'artists',
        element: (
          <ProtectedRoute>
            <ArtistsPage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'artists/:id',
        element: (
          <ProtectedRoute>
            <ArtistDetailPage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'settings',
        element: (
          <ProtectedRoute>
            <SettingsPage />
          </ProtectedRoute>
        ),
      },
    ],
  },
]);

export function App() {
  return <RouterProvider router={router} />;
}
