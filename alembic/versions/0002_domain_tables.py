"""Split JSON documents into domain tables and relational child records."""
import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0002_domain_tables"
down_revision = "0001_documents"
branch_labels = None
depends_on = None

JSON = postgresql.JSONB(astext_type=sa.Text())
TS = sa.DateTime(timezone=True)

def common_columns():
    return [
        sa.Column("owner_id", sa.String(128), nullable=False),
        sa.Column("id", sa.String(128), nullable=False),
        sa.Column("payload", JSON, nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", TS, nullable=False, server_default=sa.func.now()),
        sa.PrimaryKeyConstraint("owner_id", "id"),
    ]

def upgrade() -> None:
    op.create_table("user_profiles", *common_columns(), sa.Column("experience_level", sa.String(32)), sa.Column("goal_type", sa.String(48)), sa.Column("weekly_workout_target", sa.Integer()), sa.Column("profile_completed", sa.Boolean(), nullable=False, server_default=sa.false()))
    op.create_table("user_settings", *common_columns(), sa.Column("default_rest_seconds", sa.Integer()))
    op.create_table("routines", *common_columns(), sa.Column("name", sa.String(120), nullable=False), sa.Column("playlist", JSON))
    op.create_index("ix_routines_owner", "routines", ["owner_id"])
    op.create_table(
        "routine_exercises",
        sa.Column("owner_id", sa.String(128), nullable=False), sa.Column("routine_id", sa.String(128), nullable=False),
        sa.Column("exercise_id", sa.String(128), nullable=False), sa.Column("order_index", sa.Integer(), nullable=False),
        sa.Column("name", sa.String(120)), sa.Column("set_count", sa.Integer(), nullable=False),
        sa.Column("reps", sa.Integer(), nullable=False), sa.Column("weight", sa.Float(), nullable=False),
        sa.Column("rest_seconds", sa.Integer(), nullable=False), sa.Column("extra", JSON, nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.PrimaryKeyConstraint("owner_id", "routine_id", "exercise_id"),
        sa.ForeignKeyConstraint(["owner_id", "routine_id"], ["routines.owner_id", "routines.id"], ondelete="CASCADE"),
        sa.UniqueConstraint("owner_id", "routine_id", "order_index", name="uq_routine_exercise_order"),
    )
    op.create_table(
        "workouts", *common_columns(),
        sa.Column("routine_id", sa.String(128), nullable=False), sa.Column("routine_name", sa.String(120), nullable=False),
        sa.Column("status", sa.String(24), nullable=False), sa.Column("started_at", TS, nullable=False), sa.Column("ended_at", TS),
        sa.Column("timezone_name", sa.String(64), nullable=False, server_default="UTC"),
        sa.Column("current_exercise_index", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("duration", sa.Integer()), sa.Column("total_sets", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("volume", sa.Float(), nullable=False, server_default="0"), sa.Column("xp", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("growth_delta", sa.Integer(), nullable=False, server_default="0"), sa.Column("version", sa.Integer(), nullable=False, server_default="1"),
        sa.ForeignKeyConstraint(["owner_id", "routine_id"], ["routines.owner_id", "routines.id"], ondelete="RESTRICT"),
    )
    op.create_index("uq_workouts_one_active_per_owner", "workouts", ["owner_id"], unique=True, postgresql_where=sa.text("status = 'IN_PROGRESS'"))
    op.create_index("ix_workouts_owner_started", "workouts", ["owner_id", "started_at"])
    op.create_index("ix_workouts_status", "workouts", ["status"])
    op.create_table(
        "workout_sets", sa.Column("set_id", sa.String(128), primary_key=True),
        sa.Column("owner_id", sa.String(128), nullable=False), sa.Column("workout_id", sa.String(128), nullable=False),
        sa.Column("exercise_id", sa.String(128), nullable=False), sa.Column("exercise_name", sa.String(120)),
        sa.Column("set_index", sa.Integer(), nullable=False), sa.Column("weight", sa.Float(), nullable=False),
        sa.Column("reps", sa.Integer(), nullable=False), sa.Column("completed_at", TS, nullable=False),
        sa.Column("is_pr", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.ForeignKeyConstraint(["owner_id", "workout_id"], ["workouts.owner_id", "workouts.id"], ondelete="CASCADE"),
        sa.UniqueConstraint("workout_id", "exercise_id", "set_index", name="uq_workout_exercise_set_index"),
    )
    op.create_index("ix_workout_sets_owner_exercise", "workout_sets", ["owner_id", "exercise_id", "completed_at"])
    op.create_table("goals", *common_columns(), sa.Column("goal_type", sa.String(48), nullable=False), sa.Column("start_date", sa.Date(), nullable=False), sa.Column("end_date", sa.Date(), nullable=False), sa.Column("weekly_workout_target", sa.Integer(), nullable=False), sa.Column("status", sa.String(24), nullable=False), sa.Column("growth", sa.Integer(), nullable=False, server_default="0"))
    op.create_index("uq_goals_one_active_per_owner", "goals", ["owner_id"], unique=True, postgresql_where=sa.text("status = 'ACTIVE'"))
    op.create_table("weekly_goals", *common_columns(), sa.Column("week_start", sa.Date(), nullable=False), sa.Column("target_count", sa.Integer(), nullable=False), sa.Column("completed_count", sa.Integer(), nullable=False, server_default="0"), sa.Column("achieved", sa.Boolean(), nullable=False, server_default=sa.false()), sa.Column("goal_id", sa.String(128)), sa.UniqueConstraint("owner_id", "week_start", name="uq_weekly_goal_owner_week"))
    op.create_table("rewards", *common_columns(), sa.Column("workout_id", sa.String(128), nullable=False), sa.Column("xp", sa.Integer(), nullable=False), sa.Column("growth_delta", sa.Integer(), nullable=False), sa.Column("granted_at", TS, nullable=False), sa.ForeignKeyConstraint(["owner_id", "workout_id"], ["workouts.owner_id", "workouts.id"], ondelete="CASCADE"), sa.UniqueConstraint("owner_id", "workout_id", name="uq_reward_per_workout"))
    op.create_table("rest_timers", *common_columns(), sa.Column("workout_id", sa.String(128), nullable=False), sa.Column("set_id", sa.String(128), nullable=False), sa.Column("status", sa.String(24), nullable=False), sa.Column("started_at", TS, nullable=False), sa.Column("ends_at", TS, nullable=False), sa.Column("duration_seconds", sa.Integer(), nullable=False), sa.ForeignKeyConstraint(["owner_id", "workout_id"], ["workouts.owner_id", "workouts.id"], ondelete="CASCADE"))
    op.create_index("uq_rest_one_active_per_workout", "rest_timers", ["owner_id", "workout_id"], unique=True, postgresql_where=sa.text("status = 'ACTIVE'"))
    op.create_table("progress_photos", *common_columns(), sa.Column("workout_id", sa.String(128), nullable=False), sa.Column("file_name", sa.String(255), nullable=False), sa.Column("content_type", sa.String(64), nullable=False), sa.Column("size", sa.Integer(), nullable=False), sa.Column("status", sa.String(32), nullable=False), sa.Column("storage_path", sa.Text()), sa.Column("edited_path", sa.Text()), sa.Column("captured_at", TS, nullable=False), sa.Column("expires_at", TS, nullable=False), sa.ForeignKeyConstraint(["owner_id", "workout_id"], ["workouts.owner_id", "workouts.id"], ondelete="CASCADE"))
    op.create_index("ix_photos_owner_captured", "progress_photos", ["owner_id", "captured_at"])
    op.create_table("photo_jobs", *common_columns(), sa.Column("photo_id", sa.String(128), nullable=False), sa.Column("job_type", sa.String(24), nullable=False), sa.Column("status", sa.String(24), nullable=False), sa.Column("options", JSON, nullable=False, server_default=sa.text("'{}'::jsonb")), sa.ForeignKeyConstraint(["owner_id", "photo_id"], ["progress_photos.owner_id", "progress_photos.id"], ondelete="CASCADE"))
    op.create_index("ix_photo_jobs_owner_status", "photo_jobs", ["owner_id", "status"])
    op.create_table("avatar_items", *common_columns(), sa.Column("item_type", sa.String(48)), sa.Column("unlocked", sa.Boolean(), nullable=False, server_default=sa.false()))


def downgrade() -> None:
    op.drop_table("avatar_items")
    op.drop_index("ix_photo_jobs_owner_status", table_name="photo_jobs"); op.drop_table("photo_jobs")
    op.drop_index("ix_photos_owner_captured", table_name="progress_photos"); op.drop_table("progress_photos")
    op.drop_index("uq_rest_one_active_per_workout", table_name="rest_timers"); op.drop_table("rest_timers")
    op.drop_table("rewards"); op.drop_table("weekly_goals")
    op.drop_index("uq_goals_one_active_per_owner", table_name="goals"); op.drop_table("goals")
    op.drop_index("ix_workout_sets_owner_exercise", table_name="workout_sets"); op.drop_table("workout_sets")
    op.drop_index("ix_workouts_status", table_name="workouts"); op.drop_index("ix_workouts_owner_started", table_name="workouts"); op.drop_index("uq_workouts_one_active_per_owner", table_name="workouts"); op.drop_table("workouts")
    op.drop_table("routine_exercises"); op.drop_index("ix_routines_owner", table_name="routines"); op.drop_table("routines")
    op.drop_table("user_settings"); op.drop_table("user_profiles")


