from datetime import datetime, timezone
import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0001_documents"
down_revision = None
branch_labels = None
depends_on = None

def upgrade() -> None:
    op.create_table(
        "documents",
        sa.Column("id", sa.String(length=128), nullable=False),
        sa.Column("owner_id", sa.String(length=128), nullable=False),
        sa.Column("kind", sa.String(length=48), nullable=False),
        sa.Column("payload", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.PrimaryKeyConstraint("owner_id", "kind", "id", name="pk_documents"),
    )
    op.create_index("ix_documents_owner_kind", "documents", ["owner_id", "kind"])

def downgrade() -> None:
    op.drop_index("ix_documents_owner_kind", table_name="documents")
    op.drop_table("documents")
