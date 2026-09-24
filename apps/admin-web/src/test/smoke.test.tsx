import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';

describe('Admin Web test infrastructure', () => {
  it('renders a React element in jsdom', () => {
    render(<main>Admin test infrastructure</main>);

    expect(screen.getByText('Admin test infrastructure')).toBeInTheDocument();
  });
});
