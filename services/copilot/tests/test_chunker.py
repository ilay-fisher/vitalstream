"""Markdown-aware chunking: structure is preserved, size is bounded, context overlaps."""

from copilot.rag.chunker import Chunk, chunk_markdown, estimate_tokens

DOC = """# Sepsis screening

SYNTHETIC EDUCATIONAL CONTENT.

Intro paragraph about screening.

## Red flags

- Temperature above 38.3
- Heart rate above 90

## Actions

Start the sepsis six within one hour.
"""


def test_short_document_with_sections_yields_chunk_per_section_path() -> None:
    chunks = chunk_markdown(DOC, title="Sepsis screening", max_tokens=400, overlap_tokens=40)
    assert [c.ordinal for c in chunks] == list(range(len(chunks)))
    joined = "\n".join(c.content for c in chunks)
    assert "Red flags" in joined
    assert "Start the sepsis six" in joined


def test_chunks_carry_heading_path_prefix() -> None:
    chunks = chunk_markdown(DOC, title="Sepsis screening", max_tokens=400, overlap_tokens=40)
    red = next(c for c in chunks if "Temperature above 38.3" in c.content)
    assert red.heading_path == "Sepsis screening > Red flags"
    assert red.content.startswith("Sepsis screening > Red flags")


def test_list_items_are_never_split_from_their_list() -> None:
    chunks = chunk_markdown(DOC, title="Sepsis screening", max_tokens=400, overlap_tokens=0)
    red = next(c for c in chunks if "Temperature above 38.3" in c.content)
    assert "Heart rate above 90" in red.content


def test_fenced_code_block_is_kept_whole() -> None:
    doc = "# T\n\n## Code\n\n```\nline one\n\nline two after blank\n```\n"
    chunks = chunk_markdown(doc, title="T", max_tokens=400, overlap_tokens=0)
    assert any("line one\n\nline two after blank" in c.content for c in chunks)


def test_long_section_is_split_within_budget() -> None:
    paragraphs = [f"Paragraph {i} " + "word " * 120 for i in range(10)]
    doc = "# Long\n\n## Body\n\n" + "\n\n".join(paragraphs)
    chunks = chunk_markdown(doc, title="Long", max_tokens=200, overlap_tokens=0)
    assert len(chunks) > 3
    assert all(estimate_tokens(c.content) <= 200 + 40 for c in chunks)


def test_oversized_single_paragraph_is_split_by_sentence() -> None:
    sentence = "This sentence is exactly nine words long here ok. "
    doc = "# Big\n\n## Body\n\n" + sentence * 80
    chunks = chunk_markdown(doc, title="Big", max_tokens=100, overlap_tokens=0)
    assert len(chunks) > 3
    assert all(estimate_tokens(c.content) <= 100 + 20 for c in chunks)


def test_overlap_repeats_tail_of_previous_chunk() -> None:
    paragraphs = [f"UNIQUE{i} " + "filler " * 60 for i in range(6)]
    doc = "# O\n\n## Body\n\n" + "\n\n".join(paragraphs)
    with_overlap = chunk_markdown(doc, title="O", max_tokens=200, overlap_tokens=90)
    without = chunk_markdown(doc, title="O", max_tokens=200, overlap_tokens=0)
    assert len(with_overlap) >= len(without)
    first, second = with_overlap[0], with_overlap[1]
    last_marker = [w for w in first.content.split() if w.startswith("UNIQUE")][-1]
    assert last_marker in second.content


def test_overlap_never_crosses_section_boundary() -> None:
    chunks = chunk_markdown(DOC, title="Sepsis screening", max_tokens=400, overlap_tokens=200)
    red = next(c for c in chunks if c.heading_path.endswith("Red flags"))
    assert "Start the sepsis six" not in red.content


def test_empty_and_whitespace_documents_yield_no_chunks() -> None:
    assert chunk_markdown("", title="x") == []
    assert chunk_markdown("   \n\n  ", title="x") == []


def test_document_without_headings_uses_title_as_path() -> None:
    chunks = chunk_markdown("Just a paragraph of text.", title="Plain")
    assert len(chunks) == 1
    assert chunks[0].heading_path == "Plain"


def test_heading_only_sections_are_skipped() -> None:
    chunks = chunk_markdown("# T\n\n## Empty\n\n## Full\n\ncontent here", title="T")
    assert [c.heading_path for c in chunks] == ["T > Full"]


def test_estimate_tokens_grows_with_text() -> None:
    assert estimate_tokens("") == 0
    assert estimate_tokens("one two three") < estimate_tokens("one two three four five six")


def test_chunk_is_immutable_value() -> None:
    chunk = Chunk(ordinal=0, content="x", heading_path="p")
    assert chunk == Chunk(ordinal=0, content="x", heading_path="p")
