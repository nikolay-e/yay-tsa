import { describe, it, expect, beforeEach } from 'vitest';
import { PlaybackQueue } from './queue.js';
import type { AudioItem, RepeatMode } from '../internal/models/types.js';

function track(id: string): AudioItem {
  return {
    Id: id,
    Name: `Track ${id}`,
    Type: 'Audio',
    RunTimeTicks: 2400000000,
    AlbumId: 'album-1',
    Album: 'Album',
    Artists: ['Artist'],
    IndexNumber: 1,
  };
}

function tracks(count: number): AudioItem[] {
  return Array.from({ length: count }, (_, i) => track(`t${i + 1}`));
}

describe('PlaybackQueue', () => {
  let queue: PlaybackQueue;

  beforeEach(() => {
    queue = new PlaybackQueue();
  });

  describe('basic operations', () => {
    it('starts empty', () => {
      expect(queue.isEmpty()).toBe(true);
      expect(queue.getLength()).toBe(0);
      expect(queue.getCurrentItem()).toBeNull();
      expect(queue.getCurrentIndex()).toBe(-1);
    });

    it('addToQueue appends item and sets index on first add', () => {
      queue.addToQueue(track('a'));
      expect(queue.getLength()).toBe(1);
      expect(queue.getCurrentIndex()).toBe(0);
      expect(queue.getCurrentItem()?.Id).toBe('a');
    });

    it('addMultipleToQueue appends items', () => {
      queue.addMultipleToQueue(tracks(3));
      expect(queue.getLength()).toBe(3);
      expect(queue.getCurrentIndex()).toBe(0);
    });

    it('addMultipleToQueue to non-empty queue preserves index', () => {
      queue.setQueue(tracks(2));
      queue.next();
      queue.addMultipleToQueue([track('extra')]);
      expect(queue.getCurrentIndex()).toBe(1);
      expect(queue.getLength()).toBe(3);
    });

    it('insertAt adds item at position', () => {
      queue.setQueue(tracks(3));
      queue.insertAt(track('ins'), 1);
      expect(queue.getLength()).toBe(4);
      expect(queue.getItemAt(1)?.Id).toBe('ins');
    });

    it('insertAt before current shifts index', () => {
      queue.setQueue(tracks(3), 1);
      queue.insertAt(track('ins'), 0);
      expect(queue.getCurrentIndex()).toBe(2);
      expect(queue.getCurrentItem()?.Id).toBe('t2');
    });

    it('insertAt with invalid index is ignored', () => {
      queue.setQueue(tracks(2));
      queue.insertAt(track('x'), -1);
      queue.insertAt(track('y'), 100);
      expect(queue.getLength()).toBe(2);
    });

    it('removeAt removes item and adjusts index', () => {
      queue.setQueue(tracks(3), 2);
      const removed = queue.removeAt(0);
      expect(removed?.Id).toBe('t1');
      expect(queue.getLength()).toBe(2);
      expect(queue.getCurrentIndex()).toBe(1);
    });

    it('removeAt current item stays at same index', () => {
      queue.setQueue(tracks(3), 1);
      queue.removeAt(1);
      expect(queue.getCurrentIndex()).toBe(1);
      expect(queue.getCurrentItem()?.Id).toBe('t3');
    });

    it('removeAt last item when current adjusts to new last', () => {
      queue.setQueue(tracks(2), 1);
      queue.removeAt(1);
      expect(queue.getCurrentIndex()).toBe(0);
    });

    it('removeAt out of bounds returns null', () => {
      queue.setQueue(tracks(2));
      expect(queue.removeAt(-1)).toBeNull();
      expect(queue.removeAt(5)).toBeNull();
    });

    it('clear empties queue', () => {
      queue.setQueue(tracks(5));
      queue.clear();
      expect(queue.isEmpty()).toBe(true);
      expect(queue.getCurrentIndex()).toBe(-1);
    });

    it('moveItem reorders and updates current', () => {
      queue.setQueue(tracks(4), 0);
      queue.moveItem(0, 2);
      expect(queue.getCurrentIndex()).toBe(2);
      expect(queue.getCurrentItem()?.Id).toBe('t1');
    });

    it('moveItem returns false for invalid indices', () => {
      queue.setQueue(tracks(2));
      expect(queue.moveItem(-1, 1)).toBe(false);
      expect(queue.moveItem(0, 5)).toBe(false);
      expect(queue.moveItem(0, 0)).toBe(false);
    });

    it('setQueue with empty array clears', () => {
      queue.setQueue(tracks(3));
      queue.setQueue([]);
      expect(queue.isEmpty()).toBe(true);
    });

    it('setQueue clamps startIndex', () => {
      queue.setQueue(tracks(3), 100);
      expect(queue.getCurrentIndex()).toBe(2);
    });
  });

  describe('navigation with RepeatMode.off', () => {
    beforeEach(() => {
      queue.setQueue(tracks(3));
      queue.setRepeatMode('off');
    });

    it('next advances through queue', () => {
      expect(queue.next()?.Id).toBe('t2');
      expect(queue.next()?.Id).toBe('t3');
    });

    it('next at end returns null and stays', () => {
      queue.jumpTo(2);
      expect(queue.next()).toBeNull();
      expect(queue.getCurrentIndex()).toBe(2);
    });

    it('previous goes back', () => {
      queue.jumpTo(2);
      expect(queue.previous()?.Id).toBe('t2');
      expect(queue.previous()?.Id).toBe('t1');
    });

    it('previous at start returns null and stays', () => {
      expect(queue.previous()).toBeNull();
      expect(queue.getCurrentIndex()).toBe(0);
    });

    it('hasNext/hasPrevious are correct', () => {
      expect(queue.hasNext()).toBe(true);
      expect(queue.hasPrevious()).toBe(false);
      queue.jumpTo(2);
      expect(queue.hasNext()).toBe(false);
      expect(queue.hasPrevious()).toBe(true);
    });

    it('peekNext shows next without advancing', () => {
      expect(queue.peekNext()?.Id).toBe('t2');
      expect(queue.getCurrentIndex()).toBe(0);
    });

    it('peekNext at end returns null', () => {
      queue.jumpTo(2);
      expect(queue.peekNext()).toBeNull();
    });
  });

  describe('navigation with RepeatMode.all', () => {
    beforeEach(() => {
      queue.setQueue(tracks(3));
      queue.setRepeatMode('all');
    });

    it('next at end wraps to start', () => {
      queue.jumpTo(2);
      expect(queue.next()?.Id).toBe('t1');
      expect(queue.getCurrentIndex()).toBe(0);
    });

    it('previous at start wraps to end', () => {
      expect(queue.previous()?.Id).toBe('t3');
      expect(queue.getCurrentIndex()).toBe(2);
    });

    it('hasNext and hasPrevious always true', () => {
      expect(queue.hasNext()).toBe(true);
      expect(queue.hasPrevious()).toBe(true);
    });

    it('peekNext at end shows first item', () => {
      queue.jumpTo(2);
      expect(queue.peekNext()?.Id).toBe('t1');
    });
  });

  describe('navigation with RepeatMode.one', () => {
    beforeEach(() => {
      queue.setQueue(tracks(3), 1);
      queue.setRepeatMode('one');
    });

    it('next returns current item', () => {
      expect(queue.next()?.Id).toBe('t2');
      expect(queue.getCurrentIndex()).toBe(1);
    });

    it('previous returns current item', () => {
      expect(queue.previous()?.Id).toBe('t2');
      expect(queue.getCurrentIndex()).toBe(1);
    });

    it('peekNext returns current item', () => {
      expect(queue.peekNext()?.Id).toBe('t2');
    });
  });

  describe('empty queue operations', () => {
    it('next on empty returns null', () => {
      expect(queue.next()).toBeNull();
    });

    it('previous on empty returns null', () => {
      expect(queue.previous()).toBeNull();
    });

    it('peekNext on empty returns null', () => {
      expect(queue.peekNext()).toBeNull();
    });

    it('hasNext/hasPrevious on empty are false', () => {
      expect(queue.hasNext()).toBe(false);
      expect(queue.hasPrevious()).toBe(false);
    });

    it('jumpTo on empty returns null', () => {
      expect(queue.jumpTo(0)).toBeNull();
    });
  });

  describe('shuffle', () => {
    it('toggle preserves all items', () => {
      const items = tracks(10);
      queue.setQueue(items);
      queue.setShuffleMode('on');

      const shuffled = queue.getAllItems();
      expect(shuffled).toHaveLength(10);

      const ids = shuffled.map(i => i.Id).sort((a, b) => a.localeCompare(b));
      const originalIds = items.map(i => i.Id).sort((a, b) => a.localeCompare(b));
      expect(ids).toEqual(originalIds);
    });

    it('current track stays at current index', () => {
      queue.setQueue(tracks(10), 3);
      const currentBefore = queue.getCurrentItem()?.Id;
      queue.setShuffleMode('on');
      expect(queue.getCurrentItem()?.Id).toBe(currentBefore);
      expect(queue.getCurrentIndex()).toBe(3);
    });

    it('disable restores original order', () => {
      const items = tracks(5);
      queue.setQueue(items);
      queue.jumpTo(2);
      queue.setShuffleMode('on');
      queue.setShuffleMode('off');

      const restored = queue.getAllItems().map(i => i.Id);
      expect(restored).toEqual(items.map(i => i.Id));
      expect(queue.getCurrentItem()?.Id).toBe('t3');
    });

    it('shuffle with single item is no-op', () => {
      queue.setQueue([track('solo')]);
      queue.setShuffleMode('on');
      expect(queue.getAllItems()).toHaveLength(1);
      expect(queue.getCurrentItem()?.Id).toBe('solo');
    });

    it('insertAt during shuffle appends to originalOrder', () => {
      queue.setQueue(tracks(3));
      queue.setShuffleMode('on');
      queue.insertAt(track('new'), 1);
      queue.setShuffleMode('off');
      const ids = queue.getAllItems().map(i => i.Id);
      expect(ids).toContain('new');
    });
  });

  describe('shuffle + repeat combinations', () => {
    const modes: RepeatMode[] = ['off', 'one', 'all'];

    for (const mode of modes) {
      it(`shuffle on + repeat ${mode}: navigation works`, () => {
        queue.setQueue(tracks(5));
        queue.setShuffleMode('on');
        queue.setRepeatMode(mode);

        if (mode === 'one') {
          const current = queue.getCurrentItem()?.Id;
          expect(queue.next()?.Id).toBe(current);
        } else if (mode === 'all') {
          for (let i = 0; i < 6; i++) {
            expect(queue.next()).not.toBeNull();
          }
        } else {
          let count = 0;
          while (queue.next() !== null) count++;
          expect(count).toBe(4);
        }
      });
    }
  });

  describe('advanceTo and trimBeforeCurrent', () => {
    it('advanceTo by trackId', () => {
      queue.setQueue(tracks(5));
      expect(queue.advanceTo('t3')).toBe(true);
      expect(queue.getCurrentItem()?.Id).toBe('t3');
    });

    it('advanceTo returns false for missing id', () => {
      queue.setQueue(tracks(3));
      expect(queue.advanceTo('nonexistent')).toBe(false);
    });

    it('trimBeforeCurrent removes previous items', () => {
      queue.setQueue(tracks(5), 3);
      queue.trimBeforeCurrent();
      expect(queue.getLength()).toBe(2);
      expect(queue.getCurrentIndex()).toBe(0);
      expect(queue.getCurrentItem()?.Id).toBe('t4');
    });

    it('trimBeforeCurrent at index 0 is no-op', () => {
      queue.setQueue(tracks(3));
      queue.trimBeforeCurrent();
      expect(queue.getLength()).toBe(3);
    });
  });

  describe('toggleRepeatMode cycles', () => {
    it('off → all → one → off', () => {
      expect(queue.getRepeatMode()).toBe('off');
      expect(queue.toggleRepeatMode()).toBe('all');
      expect(queue.toggleRepeatMode()).toBe('one');
      expect(queue.toggleRepeatMode()).toBe('off');
    });
  });

  describe('toggleShuffleMode', () => {
    it('toggles between off and on', () => {
      expect(queue.getShuffleMode()).toBe('off');
      expect(queue.toggleShuffleMode()).toBe('on');
      expect(queue.toggleShuffleMode()).toBe('off');
    });
  });

  describe('large queue', () => {
    it('1000 items — peekNext and jumpTo work', () => {
      const items = tracks(1000);
      queue.setQueue(items);

      queue.jumpTo(500);
      expect(queue.getCurrentItem()?.Id).toBe('t501');
      expect(queue.peekNext()?.Id).toBe('t502');

      queue.jumpTo(999);
      queue.setRepeatMode('off');
      expect(queue.peekNext()).toBeNull();

      queue.setRepeatMode('all');
      expect(queue.peekNext()?.Id).toBe('t1');
    });

    it('trimBeforeCurrent on large queue at index 500', () => {
      queue.setQueue(tracks(1000), 500);
      queue.trimBeforeCurrent();
      expect(queue.getLength()).toBe(500);
      expect(queue.getCurrentIndex()).toBe(0);
    });
  });

  describe('getState snapshot', () => {
    it('returns copy of internal state', () => {
      queue.setQueue(tracks(3), 1);
      queue.setRepeatMode('all');

      const state = queue.getState();
      expect(state.items).toHaveLength(3);
      expect(state.currentIndex).toBe(1);
      expect(state.repeatMode).toBe('all');
      expect(state.shuffleMode).toBe('off');

      state.items.push(track('mutated'));
      expect(queue.getLength()).toBe(3);
    });
  });
});
