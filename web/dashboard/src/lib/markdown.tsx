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

type Block =
  | { kind: 'p'; lines: string[] }
  | { kind: 'ul' | 'ol'; items: string[] }
  | { kind: 'h'; text: string };

const BULLET = /^\s*[-*]\s+/;
const NUMBER = /^\s*\d+[.)]\s+/;

/** Splits text into blocks line by line, so a list directly after a sentence is still a list. */
export function parseBlocks(text: string): Block[] {
  const blocks: Block[] = [];
  for (const line of text.split('\n')) {
    if (line.trim() === '') {
      blocks.push({ kind: 'p', lines: [] });
      continue;
    }
    const last = blocks[blocks.length - 1];
    const heading = /^#{1,4}\s+(.*)$/.exec(line);
    if (heading) {
      blocks.push({ kind: 'h', text: heading[1] });
    } else if (BULLET.test(line) || NUMBER.test(line)) {
      const kind = BULLET.test(line) ? 'ul' : 'ol';
      const item = line.replace(kind === 'ul' ? BULLET : NUMBER, '');
      if (last && last.kind === kind) last.items.push(item);
      else blocks.push({ kind, items: [item] });
    } else if (last && last.kind === 'p') {
      last.lines.push(line);
    } else {
      blocks.push({ kind: 'p', lines: [line] });
    }
  }
  return blocks.filter((b) => b.kind !== 'p' || b.lines.length > 0);
}

export function Markdown({
  text,
  onCitation,
}: {
  text: string;
  onCitation?: (index: number) => void;
}) {
  return (
    <>
      {parseBlocks(text).map((block, i) => {
        if (block.kind === 'h') return <h4 key={i}>{renderInline(block.text, onCitation)}</h4>;
        if (block.kind === 'p') {
          return (
            <p key={i}>
              {block.lines.map((l, j) => (
                <Fragment key={j}>
                  {j > 0 && <br />}
                  {renderInline(l, onCitation)}
                </Fragment>
              ))}
            </p>
          );
        }
        const List = block.kind;
        return (
          <List key={i}>
            {block.items.map((item, j) => (
              <li key={j}>{renderInline(item, onCitation)}</li>
            ))}
          </List>
        );
      })}
    </>
  );
}
