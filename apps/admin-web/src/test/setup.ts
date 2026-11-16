import '@testing-library/jest-dom/vitest';

// jsdom has no layout observer; behavior tests do not assert pixel measurements.
class TestResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = TestResizeObserver;
