from datetime import date, timedelta
from typing import Any
from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession
from app.api.v1.common import decode_cursor, encode_cursor, error, now_iso, required, user_id
from app.api.v1.workouts import growth_value
from app.db.documents import get_doc, list_docs, new_id, save_doc
from app.db.session import get_db_session

router = APIRouter()

def week_start(day: date | None = None) -> date:
    current = day or date.today()
    return current - timedelta(days=current.weekday())

@router.post("/goals", status_code=201, summary="운동 목표 생성")
async def create_goal(body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    required(body, "goalType", "startDate", "endDate", "weeklyWorkoutTarget")
    if any(g.payload.get("status") == "ACTIVE" for g in await list_docs(session, user, "goal")):
        raise error("ACTIVE_GOAL_EXISTS", "An active goal already exists", 409)
    target = int(body["weeklyWorkoutTarget"])
    if not 1 <= target <= 14:
        raise error("INVALID_GOAL", "weeklyWorkoutTarget must be between 1 and 14")
    gid, week = new_id(), week_start().isoformat()
    payload = {"goalId": gid, "goalType": body["goalType"], "startDate": body["startDate"], "endDate": body["endDate"], "weeklyWorkoutTarget": target, "status": "ACTIVE", "growth": 0, "createdAt": now_iso()}
    await save_doc(session, user, "goal", payload, gid)
    await save_doc(session, user, "weekly_goal", {"goalId": gid, "week": week, "targetCount": target, "completedCount": 0, "achieved": False}, week)
    await session.commit()
    return {"goalId": gid, "status": "ACTIVE", "growth": 0, "weeklyGoal": {"weekStart": week, "targetCount": target, "completedCount": 0}}

@router.get("/goals/active/progress", summary="현재 목표 진행도 조회")
async def goal_progress(user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    goal = next((g for g in await list_docs(session, user, "goal") if g.payload.get("status") == "ACTIVE"), None)
    if not goal:
        raise error("ACTIVE_GOAL_NOT_FOUND", "No active goal", 404)
    growth = await growth_value(session, user)
    stage = "Peak Form" if growth >= 100 else "Intermediate" if growth >= 50 else "Beginner"
    return {"goalId": goal.id, "growth": growth, "previousGrowth": max(0, growth-10), "stage": stage, "factors": {"completedWorkouts": growth//10}, "updatedAt": now_iso()}

@router.post("/goals/{goalId}/complete", summary="운동 목표 완료 처리")
async def complete_goal(goalId: str, body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    goal = await get_doc(session, user, "goal", goalId)
    if not goal:
        raise error("GOAL_NOT_FOUND", "Goal not found", 404)
    p = goal.payload
    if p["status"] != "COMPLETED" and await growth_value(session, user) < 100:
        raise error("GROWTH_NOT_REACHED", "Growth has not reached 100")
    if p["status"] != "COMPLETED":
        p.update({"status": "COMPLETED", "completedAt": now_iso(), "rewards": ["PEAK_FORM"]})
        await save_doc(session, user, "goal", p, goalId)
        await session.commit()
    return {"goalId": goalId, "status": "COMPLETED", "peakFormUnlocked": True, "rewards": p.get("rewards", ["PEAK_FORM"]), "completedAt": p["completedAt"]}

@router.get("/goals/weekly/current", summary="주간 목표 조회")
async def current_weekly_goal(user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session), timezone: str | None = None):
    goal = next((g for g in await list_docs(session, user, "goal") if g.payload.get("status") == "ACTIVE"), None)
    if not goal:
        raise error("WEEKLY_GOAL_NOT_FOUND", "No active weekly goal", 404)
    week = week_start()
    count = 0
    for w in await list_docs(session, user, "workout"):
        if w.payload.get("status") == "COMPLETED" and date.fromisoformat(w.payload["startedAt"][:10]) >= week:
            count += 1
    target = int(goal.payload["weeklyWorkoutTarget"])
    return {"weekStart": week.isoformat(), "weekEnd": (week+timedelta(days=6)).isoformat(), "targetCount": target, "completedCount": count, "achieved": count >= target}

@router.get("/streaks/weekly", summary="주간 스트릭 조회")
async def weekly_streak(user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    goal = next((g for g in await list_docs(session, user, "goal") if g.payload.get("status") == "ACTIVE"), None)
    if not goal:
        return {"currentStreak": 0, "longestStreak": 0, "lastAchievedWeek": None, "status": "NOT_STARTED"}
    target = int(goal.payload["weeklyWorkoutTarget"])
    counts: dict[str, int] = {}
    for w in await list_docs(session, user, "workout"):
        if w.payload.get("status") == "COMPLETED":
            day = date.fromisoformat(w.payload["startedAt"][:10])
            key = week_start(day).isoformat()
            counts[key] = counts.get(key, 0) + 1
    achieved = sorted(date.fromisoformat(k) for k,v in counts.items() if v >= target)
    best = run = 0
    previous = None
    for d in achieved:
        run = run + 1 if previous and d-previous == timedelta(days=7) else 1
        best = max(best, run)
        previous = d
    current = 0
    expected = week_start()
    achieved_set = set(achieved)
    while expected in achieved_set:
        current += 1
        expected -= timedelta(days=7)
    return {"currentStreak": current, "longestStreak": best, "lastAchievedWeek": achieved[-1].isoformat() if achieved else None, "status": "ACHIEVED" if current else "IN_PROGRESS"}

@router.get("/avatar", summary="현재 아바타 조회")
async def avatar(user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    growth = await growth_value(session, user)
    xp = sum(int(d.payload.get("xp", 0)) for d in await list_docs(session, user, "reward"))
    level = xp//1000 + 1
    stage = "Peak Form" if growth >= 100 else "Intermediate" if growth >= 50 else "Beginner"
    profile = await get_doc(session, user, "profile", "me")
    return {"level": level, "growth": growth, "stage": stage, "modelKey": stage.lower().replace(" ", "_"), "morphValue": growth/100, "equippedItems": [], "weeklyGoal": profile.payload.get("weeklyWorkoutTarget", 0) if profile else 0}

@router.get("/avatar/growth-stage", summary="아바타 성장 단계 조회")
async def growth_stage(user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    growth = await growth_value(session, user)
    stage = "Peak Form" if growth >= 100 else "Intermediate" if growth >= 50 else "Beginner"
    lower, upper = (100,100) if growth >= 100 else (50,99) if growth >= 50 else (0,49)
    return {"growth": growth, "stage": stage, "minGrowth": lower, "maxGrowth": upper, "modelKey": stage.lower().replace(" ", "_"), "morphValue": growth/100}

@router.get("/avatar/items", summary="아바타 아이템 목록 조회")
async def avatar_items(user: str = Depends(user_id), status: str = "all", cursor: str | None = None, size: int = Query(20, ge=1, le=100)):
    offset = decode_cursor(cursor)
    return {"items": [], "nextCursor": None}

@router.get("/users/me/level", summary="레벨 정보 조회")
async def level_info(user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    xp = sum(int(d.payload.get("xp", 0)) for d in await list_docs(session, user, "reward"))
    level, current = xp//1000+1, xp%1000
    return {"level": level, "currentXp": current, "nextLevelXp": 1000, "progressPercent": current/10, "lastLevelUpAt": None}

@router.get("/rewards/workouts/{workoutId}", summary="운동 보상 조회")
async def workout_reward(workoutId: str, user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    reward = await get_doc(session, user, "reward", workoutId)
    if not reward:
        raise error("REWARD_NOT_FOUND", "Reward not found", 404)
    return reward.payload
