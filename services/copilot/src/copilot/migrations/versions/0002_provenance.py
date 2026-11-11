"""Track where a document came from (`curated` bundled corpus vs `api-upload`).

Revision ID: 0002
Revises: 0001
"""

from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """Add provenance; bundled-corpus rows become curated, everything else api-upload."""
    op.execute(
        "ALTER TABLE copilot.documents "
        "ADD COLUMN IF NOT EXISTS provenance text NOT NULL DEFAULT 'api-upload'"
    )
    op.execute("UPDATE copilot.documents SET provenance = 'curated' WHERE source LIKE 'corpus/%'")


def downgrade() -> None:
    """Drop provenance."""
    op.execute("ALTER TABLE copilot.documents DROP COLUMN IF EXISTS provenance")
