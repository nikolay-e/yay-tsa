import { create } from 'zustand';

interface UpdatePromptState {
  needRefresh: boolean;
  applyUpdate: () => void;
  offerUpdate: (apply: () => void) => void;
  dismiss: () => void;
}

export const useUpdatePromptStore = create<UpdatePromptState>(set => ({
  needRefresh: false,
  applyUpdate: () => {},
  offerUpdate: apply => set({ needRefresh: true, applyUpdate: apply }),
  dismiss: () => set({ needRefresh: false }),
}));

export function UpdatePrompt() {
  const needRefresh = useUpdatePromptStore(state => state.needRefresh);
  const applyUpdate = useUpdatePromptStore(state => state.applyUpdate);
  const dismiss = useUpdatePromptStore(state => state.dismiss);

  if (!needRefresh) return null;

  return (
    <div
      role="status"
      data-testid="update-prompt"
      className="z-toast border-border bg-bg-secondary fixed top-[max(1rem,env(safe-area-inset-top,0px))] left-1/2 flex -translate-x-1/2 items-center gap-3 rounded-md border px-4 py-2 shadow-lg"
    >
      <span className="text-text-primary text-sm">Update available</span>
      <button
        type="button"
        onClick={applyUpdate}
        className="bg-accent text-text-on-accent hover:bg-accent-hover rounded-md px-3 py-1 text-sm font-medium transition-colors"
      >
        Restart
      </button>
      <button
        type="button"
        onClick={dismiss}
        className="text-text-secondary hover:text-text-primary text-sm transition-colors"
      >
        Later
      </button>
    </div>
  );
}
