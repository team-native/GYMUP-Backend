from datetime import datetime, timedelta, timezone
from typing import Any
from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.api.v1.common import error, now_iso, user_id
from app.db.documents import get_doc, list_docs, new_id, save_doc
from app.db.models import RestTimer
from app.db.session import get_db_session

router = APIRouter()

def utcnow(): return datetime.now(timezone.utc)

async def find_active(session, user, workout_id):
    row = await session.scalar(select(RestTimer).where(RestTimer.owner_id==user,RestTimer.workout_id==workout_id,RestTimer.status=="ACTIVE").with_for_update())
    if row is not None:
        return await get_doc(session,user,"rest",row.id)
    legacy = await list_docs(session,user,"rest")
    return next((r for r in legacy if r.payload.get("workoutId")==workout_id and r.payload.get("status")=="ACTIVE"),None)
@router.post("/workouts/{workoutId}/rests", status_code=201, summary="휴식 타이머 시작")
async def start_rest(workoutId: str, body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    workout = await get_doc(session, user, "workout", workoutId)
    if not workout:
        raise error("WORKOUT_OR_SET_NOT_FOUND", "Workout not found", 404)
    if workout.payload.get("status") != "IN_PROGRESS":
        raise error("INVALID_WORKOUT_STATE", "Workout is not active", 409)
    if await find_active(session, user, workoutId):
        raise error("REST_ALREADY_ACTIVE", "An active rest timer already exists", 409)
    set_id = body.get("setId")
    if not any(s["setId"] == set_id for s in workout.payload.get("sets", [])):
        raise error("WORKOUT_OR_SET_NOT_FOUND", "Set not found", 404)
    seconds = int(body.get("durationSeconds", 90))
    if not 1 <= seconds <= 3600:
        raise error("INVALID_DURATION", "durationSeconds must be between 1 and 3600")
    started = utcnow()
    payload = {"restId": new_id(), "workoutId": workoutId, "setId": set_id, "status": "ACTIVE", "startedAt": started.isoformat(), "endsAt": (started+timedelta(seconds=seconds)).isoformat(), "durationSeconds": seconds}
    await save_doc(session, user, "rest", payload, payload["restId"])
    await session.commit()
    return {**payload, "nextSet": None}

@router.get("/workouts/{workoutId}/rests/current", summary="현재 휴식 상태 조회")
async def current_rest(workoutId: str, user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    rest = await find_active(session, user, workoutId)
    if not rest:
        raise error("ACTIVE_REST_NOT_FOUND", "No active rest timer", 404)
    p = rest.payload
    remaining = max(0, int((datetime.fromisoformat(p["endsAt"])-utcnow()).total_seconds()+0.999))
    return {"restId": rest.id, "status": p["status"], "startedAt": p["startedAt"], "endsAt": p["endsAt"], "remainingSeconds": remaining, "notificationAt": p["endsAt"]}

@router.patch("/workouts/{workoutId}/rests/current/extend", summary="휴식시간 연장")
async def extend_rest(workoutId: str, body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    rest = await find_active(session, user, workoutId)
    if not rest:
        raise error("ACTIVE_REST_NOT_FOUND", "No active rest timer", 404)
    if int(body.get("seconds", 15)) != 15:
        raise error("MAX_REST_EXCEEDED", "Rest can be extended by 15 seconds")
    p = rest.payload
    ends = datetime.fromisoformat(p["endsAt"])+timedelta(seconds=15)
    if ends-datetime.fromisoformat(p["startedAt"]) > timedelta(minutes=30):
        raise error("MAX_REST_EXCEEDED", "Maximum rest duration exceeded")
    p["endsAt"] = ends.isoformat()
    await save_doc(session, user, "rest", p, rest.id)
    await session.commit()
    return {"restId": rest.id, "endsAt": p["endsAt"], "remainingSeconds": max(0, int((ends-utcnow()).total_seconds()+0.999))}

@router.post("/workouts/{workoutId}/rests/current/skip", summary="휴식 건너뛰기")
async def skip_rest(workoutId: str, user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    rest = await find_active(session, user, workoutId)
    if not rest:
        old = next((r for r in await list_docs(session, user, "rest") if r.payload.get("workoutId") == workoutId and r.payload.get("status") == "SKIPPED"), None)
        if old:
            return {"restId": old.id, "status": "SKIPPED", "nextSet": None}
        raise error("ACTIVE_REST_NOT_FOUND", "No active rest timer", 404)
    p = rest.payload | {"status": "SKIPPED", "endedAt": now_iso()}
    await save_doc(session, user, "rest", p, rest.id)
    await session.commit()
    return {"restId": rest.id, "status": "SKIPPED", "nextSet": None}

