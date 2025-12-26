/**
 * Queue State Machine
 * Manages playback queue, shuffle, and repeat modes
 */

import { AudioItem, QueueState, RepeatMode, ShuffleMode } from '../internal/models/types.js';

export class PlaybackQueue {
  private items: AudioItem[] = [];
  private currentIndex: number = -1;
  private originalOrder: AudioItem[] = [];
  private repeatMode: RepeatMode = 'off';
  private shuffleMode: ShuffleMode = 'off';

  /**
   * Get current queue state
   */
  getState(): QueueState {
    return {
      items: [...this.items],
      currentIndex: this.currentIndex,
      originalOrder: [...this.originalOrder],
      repeatMode: this.repeatMode,
      shuffleMode: this.shuffleMode,
    };
  }

  /**
   * Set queue items and optionally start playing at index
   */
  setQueue(items: AudioItem[], startIndex: number = 0): void {
    if (items.length === 0) {
      this.clear();
      return;
    }

    this.items = [...items];
    this.originalOrder = [...items];
    this.currentIndex = Math.max(0, Math.min(startIndex, items.length - 1));

    // Apply shuffle if enabled
    if (this.shuffleMode === 'on') {
      this.applyShuffle();
    }
  }

  /**
   * Add item to end of queue
   */
  addToQueue(item: AudioItem): void {
    this.items.push(item);
    this.originalOrder.push(item);

    // If queue was empty, set current index
    if (this.items.length === 1) {
      this.currentIndex = 0;
    }
  }

  /**
   * Add multiple items to queue
   */
  addMultipleToQueue(items: AudioItem[]): void {
    this.items.push(...items);
    this.originalOrder.push(...items);

    // If queue was empty, set current index
    if (this.items.length === items.length) {
      this.currentIndex = 0;
    }
  }

  /**
   * Remove item from queue by index
   */
  removeAt(index: number): AudioItem | null {
    if (index < 0 || index >= this.items.length) {
      return null;
    }

    const removed = this.items.splice(index, 1)[0];

    // Also remove from original order
    const originalIndex = this.originalOrder.findIndex(item => item.Id === removed.Id);
    if (originalIndex !== -1) {
      this.originalOrder.splice(originalIndex, 1);
    }

    // Adjust current index
    if (index < this.currentIndex) {
      this.currentIndex--;
    } else if (index === this.currentIndex) {
      // If we removed current item, stay at same index (will be next item)
      // Unless it was the last item
      if (this.currentIndex >= this.items.length && this.items.length > 0) {
        this.currentIndex = this.items.length - 1;
      }
    }

    // If queue is now empty
    if (this.items.length === 0) {
      this.currentIndex = -1;
    }

    return removed;
  }

  /**
   * Insert item at specific position
   */
  insertAt(item: AudioItem, index: number): void {
    if (index < 0 || index > this.items.length) {
      return;
    }

    const wasEmpty = this.items.length === 0;
    this.items.splice(index, 0, item);
    this.originalOrder.splice(index, 0, item);

    // If queue was empty, set current index to first item
    if (wasEmpty) {
      this.currentIndex = 0;
    } else if (index <= this.currentIndex) {
      // Adjust current index if inserting before current position
      this.currentIndex++;
    }
  }

  /**
   * Clear entire queue
   */
  clear(): void {
    this.items = [];
    this.originalOrder = [];
    this.currentIndex = -1;
  }

  /**
   * Get current item
   */
  getCurrentItem(): AudioItem | null {
    if (this.currentIndex < 0 || this.currentIndex >= this.items.length) {
      return null;
    }
    return this.items[this.currentIndex];
  }

  /**
   * Get item at specific index
   */
  getItemAt(index: number): AudioItem | null {
    if (index < 0 || index >= this.items.length) {
      return null;
    }
    return this.items[index];
  }

  /**
   * Get all items
   */
  getAllItems(): AudioItem[] {
    return [...this.items];
  }

  /**
   * Get current index
   */
  getCurrentIndex(): number {
    return this.currentIndex;
  }

  /**
   * Get queue length
   */
  getLength(): number {
    return this.items.length;
  }

  /**
   * Check if queue is empty
   */
  isEmpty(): boolean {
    return this.items.length === 0;
  }

  /**
   * Check if there's a next item
   */
  hasNext(): boolean {
    if (this.isEmpty()) return false;

    // With repeat all, there's always a next
    if (this.repeatMode === 'all') return true;

    // With repeat one, current item repeats
    if (this.repeatMode === 'one') return true;

    // Otherwise check if there's an item after current
    return this.currentIndex < this.items.length - 1;
  }

  /**
   * Check if there's a previous item
   */
  hasPrevious(): boolean {
    if (this.isEmpty()) return false;

    // With repeat modes, there's always a previous
    if (this.repeatMode !== 'off') return true;

    return this.currentIndex > 0;
  }

  /**
   * Move to next item
   * Returns new current item or null if at end
   */
  next(): AudioItem | null {
    if (this.isEmpty()) return null;

    // Repeat one: stay on current
    if (this.repeatMode === 'one') {
      return this.getCurrentItem();
    }

    // Move to next item
    if (this.currentIndex < this.items.length - 1) {
      this.currentIndex++;
    } else {
      // At end of queue
      if (this.repeatMode === 'all') {
        // Loop back to start
        this.currentIndex = 0;
      } else {
        // No repeat: stay at last item (caller should stop playback)
        return null;
      }
    }

    return this.getCurrentItem();
  }

  /**
   * Move to previous item
   * Returns new current item or null if at start
   */
  previous(): AudioItem | null {
    if (this.isEmpty()) return null;

    // Repeat one: stay on current
    if (this.repeatMode === 'one') {
      return this.getCurrentItem();
    }

    // Move to previous item
    if (this.currentIndex > 0) {
      this.currentIndex--;
    } else {
      // At start of queue
      if (this.repeatMode === 'all') {
        // Loop to end
        this.currentIndex = this.items.length - 1;
      } else {
        // No repeat: stay at first item
        return null;
      }
    }

    return this.getCurrentItem();
  }

  /**
   * Jump to specific index
   */
  jumpTo(index: number): AudioItem | null {
    if (index < 0 || index >= this.items.length) {
      return null;
    }

    this.currentIndex = index;
    return this.getCurrentItem();
  }

  /**
   * Set repeat mode
   */
  setRepeatMode(mode: RepeatMode): void {
    this.repeatMode = mode;
  }

  /**
   * Get repeat mode
   */
  getRepeatMode(): RepeatMode {
    return this.repeatMode;
  }

  /**
   * Toggle repeat mode (off -> all -> one -> off)
   */
  toggleRepeatMode(): RepeatMode {
    switch (this.repeatMode) {
      case 'off':
        this.repeatMode = 'all';
        break;
      case 'all':
        this.repeatMode = 'one';
        break;
      case 'one':
        this.repeatMode = 'off';
        break;
    }
    return this.repeatMode;
  }

  /**
   * Set shuffle mode
   */
  setShuffleMode(mode: ShuffleMode): void {
    if (this.shuffleMode === mode) return;

    this.shuffleMode = mode;

    if (mode === 'on') {
      this.applyShuffle();
    } else {
      this.removeShuffle();
    }
  }

  /**
   * Get shuffle mode
   */
  getShuffleMode(): ShuffleMode {
    return this.shuffleMode;
  }

  /**
   * Toggle shuffle mode
   */
  toggleShuffleMode(): ShuffleMode {
    const newMode: ShuffleMode = this.shuffleMode === 'off' ? 'on' : 'off';
    this.setShuffleMode(newMode);
    return newMode;
  }

  /**
   * Apply shuffle to queue
   * Keeps current item at current position
   */
  private applyShuffle(): void {
    if (this.items.length <= 1) return;

    const currentItem = this.getCurrentItem();

    // Shuffle all items
    const shuffled = this.shuffleArray([...this.items]);

    // If there's a current item, make sure it stays at current position
    if (currentItem) {
      const currentItemIndex = shuffled.findIndex(item => item.Id === currentItem.Id);
      if (currentItemIndex !== -1 && currentItemIndex !== this.currentIndex) {
        // Swap current item to current position
        [shuffled[this.currentIndex], shuffled[currentItemIndex]] = [
          shuffled[currentItemIndex],
          shuffled[this.currentIndex],
        ];
      }
    }

    this.items = shuffled;
  }

  /**
   * Remove shuffle and restore original order
   * Maintains current item
   */
  private removeShuffle(): void {
    const currentItem = this.getCurrentItem();

    // Restore original order
    this.items = [...this.originalOrder];

    // Find new index of current item
    if (currentItem) {
      const newIndex = this.items.findIndex(item => item.Id === currentItem.Id);
      if (newIndex !== -1) {
        this.currentIndex = newIndex;
      }
    }
  }

  /**
   * Fisher-Yates shuffle algorithm
   */
  private shuffleArray<T>(array: T[]): T[] {
    const shuffled = [...array];
    for (let i = shuffled.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
    }
    return shuffled;
  }

  /**
   * Move item from one position to another
   */
  moveItem(fromIndex: number, toIndex: number): boolean {
    if (
      fromIndex < 0 ||
      fromIndex >= this.items.length ||
      toIndex < 0 ||
      toIndex >= this.items.length ||
      fromIndex === toIndex
    ) {
      return false;
    }

    const [item] = this.items.splice(fromIndex, 1);
    this.items.splice(toIndex, 0, item);

    // Update current index if affected
    if (fromIndex === this.currentIndex) {
      this.currentIndex = toIndex;
    } else if (fromIndex < this.currentIndex && toIndex >= this.currentIndex) {
      this.currentIndex--;
    } else if (fromIndex > this.currentIndex && toIndex <= this.currentIndex) {
      this.currentIndex++;
    }

    return true;
  }
}
