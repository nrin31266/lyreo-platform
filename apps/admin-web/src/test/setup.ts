import '@testing-library/jest-dom/vitest';

// jsdom has no layout observer; behavior tests do not assert pixel measurements.
class TestResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = TestResizeObserver;

// Radix Select uses browser pointer/scroll APIs that jsdom does not implement.
HTMLElement.prototype.hasPointerCapture ??= () => false;
HTMLElement.prototype.setPointerCapture ??= () => {};
HTMLElement.prototype.releasePointerCapture ??= () => {};
HTMLElement.prototype.scrollIntoView ??= () => {};
