import { create } from 'zustand';

type SearchPanelState = Readonly<{
  isOpen: boolean;
  open: () => void;
  close: () => void;
}>;

/**
 * Open/close state for the global slide-down search panel. The magnifier button in each page header
 * calls `open()`; the panel (rendered once in RootLayout) reads `isOpen`. The `/search` route pins
 * the panel open on its own, independent of this flag.
 */
export const useSearchPanelStore = create<SearchPanelState>(set => ({
  isOpen: false,
  open: () => set({ isOpen: true }),
  close: () => set({ isOpen: false }),
}));
