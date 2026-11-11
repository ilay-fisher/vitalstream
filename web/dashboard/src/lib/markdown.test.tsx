import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Markdown } from './markdown';

describe('Markdown', () => {
  it('shows HTML in model output as text, never as elements', () => {
    const { container } = render(
      <Markdown text={'<img src=x onerror=alert(1)> <script>alert(2)</script>'} />,
    );
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('script')).toBeNull();
    expect(container.textContent).toContain('<script>alert(2)</script>');
  });

  it('renders bold, code, lists and headings', () => {
    const { container } = render(
      <Markdown
        text={'## Plan\n\nGive **fluids** via `iv`.\n\n- one\n- two\n\n1. first\n2. second'}
      />,
    );
    expect(container.querySelector('h4')?.textContent).toBe('Plan');
    expect(container.querySelector('strong')?.textContent).toBe('fluids');
    expect(container.querySelector('code')?.textContent).toBe('iv');
    expect(container.querySelectorAll('ul li')).toHaveLength(2);
    expect(container.querySelectorAll('ol li')).toHaveLength(2);
  });

  it('turns [n] into citation links and reports clicks', async () => {
    const onCitation = vi.fn();
    render(<Markdown text="Lactate first [1]." onCitation={onCitation} />);
    await userEvent.click(screen.getByRole('link', { name: 'Source 1' }));
    expect(onCitation).toHaveBeenCalledWith(1);
  });

  it('keeps single line breaks inside a paragraph', () => {
    const { container } = render(<Markdown text={'a\nb'} />);
    expect(container.querySelector('br')).not.toBeNull();
  });
});
