// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { FavoriteButton } from './FavoriteButton';

const post = vi.fn<(url: string) => Promise<void>>();
const del = vi.fn<(url: string) => Promise<void>>();
const stubClient = { requireAuth: () => 'u1', isAuthenticated: () => true, post, delete: del };

function renderButton(props: Parameters<typeof FavoriteButton>[0]) {
  const qc = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <FavoriteButton {...props} />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  cleanup();
  post.mockReset().mockResolvedValue(undefined);
  del.mockReset().mockResolvedValue(undefined);
  useAuthStore.setState({ client: stubClient as never });
});

describe('FavoriteButton', () => {
  it('renders an empty, not-pressed heart with an "Add" label when not favorite', () => {
    renderButton({ itemId: 't1', itemType: 'track', isFavorite: false });
    const btn = screen.getByRole('button');
    expect(btn.getAttribute('aria-pressed')).toBe('false');
    expect(btn.getAttribute('aria-label')).toBe('Add track to favorites');
    expect(btn.getAttribute('title')).toBe('Add track to favorites');
  });

  it('renders a pressed heart with a "Remove" label when favorite', () => {
    renderButton({ itemId: 't1', itemType: 'album', isFavorite: true });
    const btn = screen.getByRole('button');
    expect(btn.getAttribute('aria-pressed')).toBe('true');
    expect(btn.getAttribute('aria-label')).toBe('Remove album from favorites');
  });

  it('marks favorite (POST) on click when empty', async () => {
    renderButton({ itemId: 't1', itemType: 'track', isFavorite: false });
    fireEvent.click(screen.getByRole('button'));
    await waitFor(() => expect(post).toHaveBeenCalledWith('/UserFavoriteItems/t1'));
    expect(del).not.toHaveBeenCalled();
  });

  it('unmarks favorite (DELETE) on click when filled — repeated click clearly unlikes', async () => {
    renderButton({ itemId: 't1', itemType: 'track', isFavorite: true });
    fireEvent.click(screen.getByRole('button'));
    await waitFor(() => expect(del).toHaveBeenCalledWith('/UserFavoriteItems/t1'));
    expect(post).not.toHaveBeenCalled();
  });

  it('honours the disabled prop and does not fire a mutation', () => {
    renderButton({ itemId: 't1', isFavorite: false, disabled: true });
    const btn = screen.getByRole('button') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
    fireEvent.click(btn);
    expect(post).not.toHaveBeenCalled();
  });
});
