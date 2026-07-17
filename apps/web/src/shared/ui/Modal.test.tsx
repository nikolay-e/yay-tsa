// @vitest-environment jsdom
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import { Modal } from './Modal';

afterEach(cleanup);

function open(overrides: Partial<Parameters<typeof Modal>[0]> = {}) {
  const onClose = vi.fn();
  render(
    <Modal isOpen onClose={onClose} ariaLabelledBy="t" {...overrides}>
      <h2 id="t">Title</h2>
      <input aria-label="field" />
    </Modal>
  );
  return { onClose };
}

describe('Modal Escape-to-close', () => {
  it('closes on Escape when focus is inside the dialog (focus trap)', () => {
    const { onClose } = open();
    // Focus trap moves focus into the dialog; a keydown there must still close it —
    // the dialog stops propagation, so the document listener alone would miss it.
    fireEvent.keyDown(screen.getByLabelText('field'), { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on Escape fired at the document level (focus outside)', () => {
    const { onClose } = open();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('does not close on Escape when preventClose is set', () => {
    const { onClose } = open({ preventClose: true });
    fireEvent.keyDown(screen.getByLabelText('field'), { key: 'Escape' });
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();
  });

  it('ignores non-Escape keys inside the dialog', () => {
    const { onClose } = open();
    fireEvent.keyDown(screen.getByLabelText('field'), { key: 'a' });
    expect(onClose).not.toHaveBeenCalled();
  });
});
