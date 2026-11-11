"""Create documents and chunks with an HNSW cosine index.

Revision ID: 0001
Revises:
"""

from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    """Create the RAG store."""
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS copilot.documents (
            id uuid PRIMARY KEY,
            title text NOT NULL,
            source text NOT NULL,
            created_at timestamptz NOT NULL DEFAULT now()
        )
        """
    )
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS copilot.chunks (
            id uuid PRIMARY KEY,
            document_id uuid NOT NULL REFERENCES copilot.documents (id) ON DELETE CASCADE,
            ordinal integer NOT NULL,
            content text NOT NULL,
            embedding vector(384) NOT NULL,
            tsv tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED
        )
        """
    )
    op.execute("CREATE INDEX IF NOT EXISTS ix_chunks_document_id ON copilot.chunks (document_id)")
    op.execute("CREATE INDEX IF NOT EXISTS ix_chunks_tsv ON copilot.chunks USING gin (tsv)")
    op.execute(
        """
        CREATE INDEX IF NOT EXISTS ix_chunks_embedding_hnsw ON copilot.chunks
        USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64)
        """
    )


def downgrade() -> None:
    """Drop the RAG store."""
    op.execute("DROP TABLE IF EXISTS copilot.chunks")
    op.execute("DROP TABLE IF EXISTS copilot.documents")
