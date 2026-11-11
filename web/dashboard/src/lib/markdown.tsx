import { Fragment, type ReactNode } from 'react';

/**
 * Renders a small, safe markdown subset (paragraphs, headings, lists, **bold**, `code`) as React
 * elements. Model output is never injected as HTML: every character ends up in a text node, so
 * markup in the answer is displayed, not executed. `[n]` markers become citation links.
 */
export function renderInline(text: string, onCitation?: (index: number) => void): ReactNode[] {
  const nodes: ReactNode[] = [];
  const pattern = /(\*\*[^*]+\*\*|`[^`]+`|\[\d{1,2}\])/g;
  let last = 0;
  let key = 0;
  for (const match of text.matchAll(pattern)) {
    const start = match.index ?? 0;
    if (start > last) nodes.push(text.slice(last, start));
    const token = match[0];
    if (token.startsWith('**')) {
      nodes.push(<strong key={key++}>{token.slice(2, -2)}</strong>);
    } else if (token.startsWith('`')) {
      nodes.push(<code key={key++}>{token.slice(1, -1)}</code>);
    } else {
      const index = Number(token.slice(1, -1));
      nodes.push(
        <sup key={key++} className="cite">
          <a
            href={`#citation-${index}`}
            aria-label={`Source ${index}`}
            onClick={(event) => {
              if (onCitation) {
                event.preventDefault();
                onCitation(index);
              }
            }}
          >
            {index}
          </a>
        </sup>,
      );
    }
    last = start + token.length;
  }
  if (last < text.length) nodes.push(text.slice(last));
  return nodes;
}

export function Markdown({
  text,
  onCitation,
}: {
  text: string;
  onCitation?: (index: number) => void;
}) {
  const blocks = text.split(/\n{2,}/).filter((b) => b.trim() !== '');
  return (
    <>
      {blocks.map((block, i) => {
        const lines = block.split('\n');
        if (lines.every((l) => /^\s*[-*]\s+/.test(l))) {
          return (
            <ul key={i}>
              {lines.map((l, j) => (
                <li key={j}>{renderInline(l.replace(/^\s*[-*]\s+/, ''), onCitation)}</li>
              ))}
            </ul>
          );
        }
        if (lines.every((l) => /^\s*\d+[.)]\s+/.test(l))) {
          return (
            <ol key={i}>
              {lines.map((l, j) => (
                <li key={j}>{renderInline(l.replace(/^\s*\d+[.)]\s+/, ''), onCitation)}</li>
              ))}
            </ol>
          );
        }
        const heading = /^#{1,4}\s+(.*)$/.exec(lines[0]);
        if (heading && lines.length === 1) {
          return <h4 key={i}>{renderInline(heading[1], onCitation)}</h4>;
        }
        return (
          <p key={i}>
            {lines.map((l, j) => (
              <Fragment key={j}>
                {j > 0 && <br />}
                {renderInline(l, onCitation)}
              </Fragment>
            ))}
          </p>
        );
      })}
    </>
  );
}
