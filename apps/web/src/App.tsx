import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { RootLayout } from './routes/RootLayout';
import { HomePage } from './routes/HomePage';
import { LoginPage } from './routes/LoginPage';
import { AlbumsPage } from './routes/AlbumsPage';
import { AlbumDetailPage } from './routes/AlbumDetailPage';
import { ArtistsPage } from './routes/ArtistsPage';
import { ArtistDetailPage } from './routes/ArtistDetailPage';
import { SettingsPage } from './routes/SettingsPage';
import { ProtectedRoute } from './components/ProtectedRoute';

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
