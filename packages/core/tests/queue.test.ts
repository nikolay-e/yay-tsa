import { describe, it, expect, beforeEach } from 'vitest';
import { PlaybackQueue } from '../src/player/queue.js';
import { AudioItem } from '../src/models/types.js';

describe('PlaybackQueue', () => {
  let queue: PlaybackQueue;
  let testItems: AudioItem[];

  beforeEach(() => {
    queue = new PlaybackQueue();
    testItems = [
      createTestAudioItem('1', 'Track 1'),
      createTestAudioItem('2', 'Track 2'),
      createTestAudioItem('3', 'Track 3'),
      createTestAudioItem('4', 'Track 4'),
      createTestAudioItem('5', 'Track 5')
    ];
  });

  describe('Basic queue operations', () => {
    it('should start empty', () => {
      expect(queue.isEmpty()).toBe(true);
      expect(queue.getLength()).toBe(0);
      expect(queue.getCurrentItem()).toBeNull();
    });

    it('should set queue and current index', () => {
      queue.setQueue(testItems, 0);

      expect(queue.isEmpty()).toBe(false);
      expect(queue.getLength()).toBe(5);
      expect(queue.getCurrentIndex()).toBe(0);
      expect(queue.getCurrentItem()?.Name).toBe('Track 1');
    });

    it('should add item to queue', () => {
      queue.addToQueue(testItems[0]);

      expect(queue.getLength()).toBe(1);
      expect(queue.getCurrentIndex()).toBe(0);
    });

    it('should add multiple items to queue', () => {
      queue.addMultipleToQueue(testItems);

      expect(queue.getLength()).toBe(5);
      expect(queue.getCurrentItem()?.Name).toBe('Track 1');
    });

    it('should clear queue', () => {
      queue.setQueue(testItems);
      queue.clear();

      expect(queue.isEmpty()).toBe(true);
      expect(queue.getLength()).toBe(0);
    });
  });

  describe('Navigation', () => {
    beforeEach(() => {
      queue.setQueue(testItems, 0);
    });

    it('should move to next item', () => {
      const next = queue.next();

      expect(next?.Name).toBe('Track 2');
      expect(queue.getCurrentIndex()).toBe(1);
    });

    it('should move to previous item', () => {
      queue.jumpTo(2);
      const prev = queue.previous();

      expect(prev?.Name).toBe('Track 2');
      expect(queue.getCurrentIndex()).toBe(1);
    });

    it('should return null when no next without repeat', () => {
      queue.jumpTo(4);
      const next = queue.next();

      expect(next).toBeNull();
      expect(queue.getCurrentIndex()).toBe(4);
    });

    it('should return null when no previous at start', () => {
      const prev = queue.previous();

      expect(prev).toBeNull();
      expect(queue.getCurrentIndex()).toBe(0);
    });

    it('should jump to specific index', () => {
      const item = queue.jumpTo(3);

      expect(item?.Name).toBe('Track 4');
      expect(queue.getCurrentIndex()).toBe(3);
    });

    it('should check if has next', () => {
      expect(queue.hasNext()).toBe(true);

      queue.jumpTo(4);
      expect(queue.hasNext()).toBe(false);
    });

    it('should check if has previous', () => {
      expect(queue.hasPrevious()).toBe(false);

      queue.jumpTo(1);
      expect(queue.hasPrevious()).toBe(true);
    });
  });

  describe('Repeat mode', () => {
    beforeEach(() => {
      queue.setQueue(testItems, 0);
    });

    it('should default to repeat off', () => {
      expect(queue.getRepeatMode()).toBe('off');
    });

    it('should set repeat mode', () => {
      queue.setRepeatMode('all');
      expect(queue.getRepeatMode()).toBe('all');
    });

    it('should toggle repeat mode', () => {
      expect(queue.toggleRepeatMode()).toBe('all');
      expect(queue.toggleRepeatMode()).toBe('one');
      expect(queue.toggleRepeatMode()).toBe('off');
    });

    it('should loop to start with repeat all', () => {
      queue.setRepeatMode('all');
      queue.jumpTo(4);
      const next = queue.next();

      expect(next?.Name).toBe('Track 1');
      expect(queue.getCurrentIndex()).toBe(0);
    });

    it('should stay on current with repeat one', () => {
      queue.setRepeatMode('one');
      queue.jumpTo(2);
      const next = queue.next();

      expect(next?.Name).toBe('Track 3');
      expect(queue.getCurrentIndex()).toBe(2);
    });

    it('should always have next with repeat modes', () => {
      queue.jumpTo(4);

      queue.setRepeatMode('all');
      expect(queue.hasNext()).toBe(true);

      queue.setRepeatMode('one');
      expect(queue.hasNext()).toBe(true);
    });
  });

  describe('Shuffle mode', () => {
    beforeEach(() => {
      queue.setQueue(testItems, 0);
    });

    it('should default to shuffle off', () => {
      expect(queue.getShuffleMode()).toBe('off');
    });

    it('should shuffle queue', () => {
      const originalOrder = queue.getAllItems().map(i => i.Id);

      queue.setShuffleMode('on');

      const shuffledOrder = queue.getAllItems().map(i => i.Id);

      // Items should be same but likely different order
      expect(shuffledOrder).toHaveLength(originalOrder.length);
      expect(shuffledOrder.sort()).toEqual(originalOrder.sort());
    });

    it('should keep current item at current position when shuffling', () => {
      queue.jumpTo(2);
      const currentBefore = queue.getCurrentItem();

      queue.setShuffleMode('on');

      const currentAfter = queue.getCurrentItem();

      expect(currentAfter?.Id).toBe(currentBefore?.Id);
    });

    it('should restore original order when disabling shuffle', () => {
      const originalOrder = queue.getAllItems().map(i => i.Name);

      queue.setShuffleMode('on');
      queue.setShuffleMode('off');

      const restoredOrder = queue.getAllItems().map(i => i.Name);

      expect(restoredOrder).toEqual(originalOrder);
    });

    it('should toggle shuffle mode', () => {
      expect(queue.toggleShuffleMode()).toBe('on');
      expect(queue.toggleShuffleMode()).toBe('off');
    });
  });

  describe('Queue manipulation', () => {
    beforeEach(() => {
      queue.setQueue(testItems, 0);
    });

    it('should insert item at position', () => {
      const newItem = createTestAudioItem('6', 'Track 6');

      queue.insertAt(newItem, 2);

      expect(queue.getLength()).toBe(6);
      expect(queue.getItemAt(2)?.Name).toBe('Track 6');
    });

    it('should remove item at position', () => {
      const removed = queue.removeAt(2);

      expect(removed?.Name).toBe('Track 3');
      expect(queue.getLength()).toBe(4);
      expect(queue.getItemAt(2)?.Name).toBe('Track 4');
    });

    it('should adjust current index when removing before', () => {
      queue.jumpTo(3);
      queue.removeAt(1);

      expect(queue.getCurrentIndex()).toBe(2);
    });

    it('should move item from one position to another', () => {
      const success = queue.moveItem(4, 1);

      expect(success).toBe(true);
      expect(queue.getItemAt(1)?.Name).toBe('Track 5');
      expect(queue.getItemAt(2)?.Name).toBe('Track 2');
    });

    it('should update current index when moving current item', () => {
      queue.jumpTo(2);
      queue.moveItem(2, 4);

      expect(queue.getCurrentIndex()).toBe(4);
    });
  });

  describe('Edge cases', () => {
    it('should handle empty queue operations gracefully', () => {
      expect(queue.next()).toBeNull();
      expect(queue.previous()).toBeNull();
      expect(queue.getCurrentItem()).toBeNull();
      expect(queue.hasNext()).toBe(false);
      expect(queue.hasPrevious()).toBe(false);
    });

    it('should handle single item queue', () => {
      queue.setQueue([testItems[0]]);

      expect(queue.getLength()).toBe(1);
      expect(queue.next()).toBeNull();
      expect(queue.previous()).toBeNull();
    });

    it('should handle out of bounds jump', () => {
      queue.setQueue(testItems);

      expect(queue.jumpTo(-1)).toBeNull();
      expect(queue.jumpTo(10)).toBeNull();
    });

    it('should handle invalid remove index', () => {
      queue.setQueue(testItems);

      expect(queue.removeAt(-1)).toBeNull();
      expect(queue.removeAt(10)).toBeNull();
    });

    it('should handle clearing and re-adding items', () => {
      queue.setQueue(testItems);
      queue.clear();
      queue.setQueue(testItems.slice(0, 2));

      expect(queue.getLength()).toBe(2);
      expect(queue.getCurrentItem()?.Name).toBe('Track 1');
    });
  });
});

// Helper function to create test audio items
function createTestAudioItem(id: string, name: string): AudioItem {
  return {
    Type: 'Audio',
    Name: name,
    ServerId: 'test-server',
    Id: id,
    RunTimeTicks: 180_000_000 // 3 minutes
  };
}
