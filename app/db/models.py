from datetime import date, datetime, timezone
from sqlalchemy import Boolean, Date, DateTime, Float, ForeignKeyConstraint, Index, Integer, String, Text, UniqueConstraint, text
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column
from app.db.base import Base

class PayloadEntity:
    owner_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    payload: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)

class LegacyDocument(Base):
    __tablename__ = "documents"
    owner_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    kind: Mapped[str] = mapped_column(String(48), primary_key=True)
    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    payload: Mapped[dict] = mapped_column(JSONB, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

class UserProfile(PayloadEntity, Base):
    __tablename__ = "user_profiles"
    experience_level: Mapped[str | None] = mapped_column(String(32))
    goal_type: Mapped[str | None] = mapped_column(String(48))
    weekly_workout_target: Mapped[int | None] = mapped_column(Integer)
    profile_completed: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

class UserSetting(PayloadEntity, Base):
    __tablename__ = "user_settings"
    default_rest_seconds: Mapped[int | None] = mapped_column(Integer)

class Routine(PayloadEntity, Base):
    __tablename__ = "routines"
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    playlist: Mapped[dict | None] = mapped_column(JSONB)
    __table_args__ = (Index("ix_routines_owner", "owner_id"),)

class RoutineExercise(Base):
    __tablename__ = "routine_exercises"
    owner_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    routine_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    exercise_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    order_index: Mapped[int] = mapped_column(Integer, nullable=False)
    name: Mapped[str | None] = mapped_column(String(120))
    set_count: Mapped[int] = mapped_column(Integer, nullable=False)
    reps: Mapped[int] = mapped_column(Integer, nullable=False)
    weight: Mapped[float] = mapped_column(Float, nullable=False)
    rest_seconds: Mapped[int] = mapped_column(Integer, nullable=False)
    extra: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    __table_args__ = (
        ForeignKeyConstraint(["owner_id", "routine_id"], ["routines.owner_id", "routines.id"], ondelete="CASCADE"),
        UniqueConstraint("owner_id", "routine_id", "order_index", name="uq_routine_exercise_order"),
    )

class Workout(PayloadEntity, Base):
    __tablename__ = "workouts"
    routine_id: Mapped[str] = mapped_column(String(128), nullable=False)
    routine_name: Mapped[str] = mapped_column(String(120), nullable=False)
    status: Mapped[str] = mapped_column(String(24), nullable=False, index=True)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    timezone_name: Mapped[str] = mapped_column(String(64), default="UTC", nullable=False)
    current_exercise_index: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    duration: Mapped[int | None] = mapped_column(Integer)
    total_sets: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    volume: Mapped[float] = mapped_column(Float, default=0, nullable=False)
    xp: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    growth_delta: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    version: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    __table_args__ = (
        ForeignKeyConstraint(["owner_id", "routine_id"], ["routines.owner_id", "routines.id"], ondelete="RESTRICT"),
        Index("uq_workouts_one_active_per_owner", "owner_id", unique=True, postgresql_where=text("status = 'IN_PROGRESS'")),
        Index("ix_workouts_owner_started", "owner_id", "started_at"),
    )

class WorkoutSet(Base):
    __tablename__ = "workout_sets"
    set_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    owner_id: Mapped[str] = mapped_column(String(128), nullable=False)
    workout_id: Mapped[str] = mapped_column(String(128), nullable=False)
    exercise_id: Mapped[str] = mapped_column(String(128), nullable=False)
    exercise_name: Mapped[str | None] = mapped_column(String(120))
    set_index: Mapped[int] = mapped_column(Integer, nullable=False)
    weight: Mapped[float] = mapped_column(Float, nullable=False)
    reps: Mapped[int] = mapped_column(Integer, nullable=False)
    completed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    is_pr: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    __table_args__ = (
        ForeignKeyConstraint(["owner_id", "workout_id"], ["workouts.owner_id", "workouts.id"], ondelete="CASCADE"),
        UniqueConstraint("workout_id", "exercise_id", "set_index", name="uq_workout_exercise_set_index"),
        Index("ix_workout_sets_owner_exercise", "owner_id", "exercise_id", "completed_at"),
    )

class Goal(PayloadEntity, Base):
    __tablename__ = "goals"
    goal_type: Mapped[str] = mapped_column(String(48), nullable=False)
    start_date: Mapped[date] = mapped_column(Date, nullable=False)
    end_date: Mapped[date] = mapped_column(Date, nullable=False)
    weekly_workout_target: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(24), nullable=False)
    growth: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    __table_args__ = (Index("uq_goals_one_active_per_owner", "owner_id", unique=True, postgresql_where=text("status = 'ACTIVE'")),)

class WeeklyGoal(PayloadEntity, Base):
    __tablename__ = "weekly_goals"
    week_start: Mapped[date] = mapped_column(Date, nullable=False)
    target_count: Mapped[int] = mapped_column(Integer, nullable=False)
    completed_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    achieved: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    goal_id: Mapped[str | None] = mapped_column(String(128))
    __table_args__ = (UniqueConstraint("owner_id", "week_start", name="uq_weekly_goal_owner_week"),)

class Reward(PayloadEntity, Base):
    __tablename__ = "rewards"
    workout_id: Mapped[str] = mapped_column(String(128), nullable=False)
    xp: Mapped[int] = mapped_column(Integer, nullable=False)
    growth_delta: Mapped[int] = mapped_column(Integer, nullable=False)
    granted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    __table_args__ = (ForeignKeyConstraint(["owner_id", "workout_id"], ["workouts.owner_id", "workouts.id"], ondelete="CASCADE"), UniqueConstraint("owner_id", "workout_id", name="uq_reward_per_workout"))

class RestTimer(PayloadEntity, Base):
    __tablename__ = "rest_timers"
    workout_id: Mapped[str] = mapped_column(String(128), nullable=False)
    set_id: Mapped[str] = mapped_column(String(128), nullable=False)
    status: Mapped[str] = mapped_column(String(24), nullable=False)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    ends_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    duration_seconds: Mapped[int] = mapped_column(Integer, nullable=False)
    __table_args__ = (ForeignKeyConstraint(["owner_id", "workout_id"], ["workouts.owner_id", "workouts.id"], ondelete="CASCADE"), Index("uq_rest_one_active_per_workout", "owner_id", "workout_id", unique=True, postgresql_where=text("status = 'ACTIVE'")))

class ProgressPhoto(PayloadEntity, Base):
    __tablename__ = "progress_photos"
    workout_id: Mapped[str] = mapped_column(String(128), nullable=False)
    file_name: Mapped[str] = mapped_column(String(255), nullable=False)
    content_type: Mapped[str] = mapped_column(String(64), nullable=False)
    size: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    storage_path: Mapped[str | None] = mapped_column(Text)
    edited_path: Mapped[str | None] = mapped_column(Text)
    captured_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    __table_args__ = (ForeignKeyConstraint(["owner_id", "workout_id"], ["workouts.owner_id", "workouts.id"], ondelete="CASCADE"), Index("ix_photos_owner_captured", "owner_id", "captured_at"))

class PhotoJob(PayloadEntity, Base):
    __tablename__ = "photo_jobs"
    photo_id: Mapped[str] = mapped_column(String(128), nullable=False)
    job_type: Mapped[str] = mapped_column(String(24), nullable=False)
    status: Mapped[str] = mapped_column(String(24), nullable=False)
    options: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    __table_args__ = (ForeignKeyConstraint(["owner_id", "photo_id"], ["progress_photos.owner_id", "progress_photos.id"], ondelete="CASCADE"), Index("ix_photo_jobs_owner_status", "owner_id", "status"))

class AvatarItem(PayloadEntity, Base):
    __tablename__ = "avatar_items"
    item_type: Mapped[str | None] = mapped_column(String(48))
    unlocked: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

