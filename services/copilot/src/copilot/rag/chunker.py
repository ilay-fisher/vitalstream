"""Markdown-aware chunking.

Chunks never straddle a heading: each section is packed independently so that a retrieved chunk
is topically coherent and can be prefixed with its heading path ("Title > Section"), which both
improves embedding quality and gives citations a human-readable anchor. Within a section, blocks
(paragraphs, lists, tables, fenced code) are kept intact where they fit, and a tail of the
previous chunk is repeated so that facts that span a boundary stay retrievable.
"""

import math
import re
from dataclasses import dataclass

_HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
_FENCE = re.compile(r"^\s*(```|~~~)")
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")
_TOKENS_PER_WORD = 1.3
_MIN_BODY_TOKENS = 40


@dataclass(frozen=True, slots=True)
class Chunk:
    """A retrievable slice of a document."""

    ordinal: int
    content: str
    heading_path: str


def estimate_tokens(text: str) -> int:
    """Cheap tokenizer-free estimate (~1.3 tokens per whitespace-delimited word)."""
    return math.ceil(len(text.split()) * _TOKENS_PER_WORD)


def _sections(markdown: str, title: str) -> list[tuple[str, list[str]]]:
    """Split into (heading path, body blocks) honouring fenced code and blank-line blocks."""
    sections: list[tuple[str, list[str]]] = []
    stack: list[tuple[int, str]] = []
    blocks: list[str] = []
    current: list[str] = []
    in_fence = False

    def path() -> str:
        parts = [title] + [
            text for level, text in stack if not (level == 1 and text.lower() == title.lower())
        ]
        return " > ".join(parts)

    def flush_block() -> None:
        if current:
            blocks.append("\n".join(current).strip("\n"))
            current.clear()

    def flush_section(heading_path: str) -> None:
        flush_block()
        if blocks:
            sections.append((heading_path, list(blocks)))
        blocks.clear()

    section_path = path()
    for line in markdown.splitlines():
        if _FENCE.match(line):
            in_fence = not in_fence
            current.append(line)
            continue
        heading = None if in_fence else _HEADING.match(line)
        if heading:
            flush_section(section_path)
            level = len(heading.group(1))
            while stack and stack[-1][0] >= level:
                stack.pop()
            stack.append((level, heading.group(2).strip()))
            section_path = path()
        elif not line.strip() and not in_fence:
            flush_block()
        else:
            current.append(line)
    flush_section(section_path)
    return sections


def _split_oversized(block: str, budget: int) -> list[str]:
    """Break a block that exceeds the budget by sentence, then by word."""
    pieces: list[str] = []
    current: list[str] = []
    for sentence in _SENTENCE_END.split(block):
        for part in _words_within(sentence, budget):
            if current and estimate_tokens(" ".join([*current, part])) > budget:
                pieces.append(" ".join(current))
                current = []
            current.append(part)
    if current:
        pieces.append(" ".join(current))
    return pieces


def _words_within(text: str, budget: int) -> list[str]:
    if estimate_tokens(text) <= budget:
        return [text]
    words = text.split()
    step = max(1, int(budget / _TOKENS_PER_WORD))
    return [" ".join(words[i : i + step]) for i in range(0, len(words), step)]


def _overlap_tail(blocks: list[str], overlap_tokens: int) -> list[str]:
    """Trailing whole blocks that fit the overlap budget, else a word tail of the last block."""
    if overlap_tokens <= 0 or not blocks:
        return []
    tail: list[str] = []
    used = 0
    for block in reversed(blocks):
        size = estimate_tokens(block)
        if used + size > overlap_tokens:
            break
        tail.insert(0, block)
        used += size
    if tail:
        return tail
    words = blocks[-1].split()
    keep = max(1, int(overlap_tokens / _TOKENS_PER_WORD))
    return [" ".join(words[-keep:])]


def _pack(blocks: list[str], budget: int, overlap_tokens: int) -> list[list[str]]:
    packed: list[list[str]] = []
    current: list[str] = []
    fresh = False  # current holds at least one block that is not just overlap
    queue = [piece for block in blocks for piece in _split_oversized(block, budget)]
    for block in queue:
        size = estimate_tokens(block)
        if current and sum(map(estimate_tokens, current)) + size > budget:
            if fresh:
                packed.append(current)
                current = _overlap_tail(current, overlap_tokens)
                fresh = False
            while current and sum(map(estimate_tokens, current)) + size > budget:
                current.pop(0)
        current.append(block)
        fresh = True
    if current and fresh:
        packed.append(current)
    return packed


def chunk_markdown(
    markdown: str, *, title: str, max_tokens: int = 400, overlap_tokens: int = 60
) -> list[Chunk]:
    """Chunk a markdown document into ~``max_tokens`` pieces with ``overlap_tokens`` of overlap.

    Each chunk's ``content`` starts with its heading path so the text is self-describing when
    it is embedded, indexed for BM25 and shown as a citation.
    """
    chunks: list[Chunk] = []
    for heading_path, blocks in _sections(markdown, title):
        budget = max(_MIN_BODY_TOKENS, max_tokens - estimate_tokens(heading_path))
        for group in _pack(blocks, budget, overlap_tokens):
            body = "\n\n".join(group)
            chunks.append(
                Chunk(
                    ordinal=len(chunks),
                    content=f"{heading_path}\n\n{body}",
                    heading_path=heading_path,
                )
            )
    return chunks
