// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { SortableList } from './SortableList';

// Guards the drag/drop text-selection fix: reorderable rows must opt out of native
// text selection (so a drag gesture never turns into a highlight), the drag handle
// must opt out of touch scrolling/long-press selection, and the temporary body
// `dragging` class must never outlive the component.

type Row = { Id: string; Name: string };

const items: Row[] = [
  { Id: '1', Name: 'First' },
  { Id: '2', Name: 'Second' },
];

function renderList() {
  return render(
    <SortableList
      items={items}
      onReorder={() => {}}
      renderItem={item => <span>{item.Name}</span>}
    />
  );
}

afterEach(() => {
  cleanup();
  document.body.classList.remove('dragging');
});

describe('SortableList drag text-selection guards', () => {
  it('marks each reorderable row as non-selectable so a drag does not select its text', () => {
    renderList();
    const rows = screen.getAllByText(/First|Second/).map(el => el.closest('.group\\/sortable'));
    expect(rows).toHaveLength(2);
    for (const row of rows) {
      expect(row).not.toBeNull();
      expect(row!.className).toContain('select-none');
    }
  });

  it('gives every drag handle touch-none so long-press/scroll does not hijack the drag', () => {
    renderList();
    const handles = screen.getAllByLabelText('Drag to reorder');
    expect(handles).toHaveLength(2);
    for (const handle of handles) {
      expect(handle.className).toContain('touch-none');
      expect(handle.className).toContain('cursor-grab');
    }
  });

  it('does not leave the body `dragging` class stuck after unmount', () => {
    const { unmount } = renderList();
    // Simulate a drag in flight, then unmount before drag end fires (e.g. sort mode switch).
    document.body.classList.add('dragging');
    unmount();
    expect(document.body.classList.contains('dragging')).toBe(false);
  });
});
