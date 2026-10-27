from datetime import datetime, timezone
from typing import Any
from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession
from app.api.v1.common import error, now_iso, owned, required, user_id
from app.db.documents import get_doc, save_doc
from app.db.session import get_db_session

router = APIRouter()

@router.put("/users/me/onboarding", summary="최초 사용자 설정 저장")
async def onboarding(body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    required(body, "experienceLevel", "goalType", "weeklyWorkoutTarget")
    current = await get_doc(session, user, "profile", "me")
    if current and current.payload.get("profileCompleted"):
        raise error("ALREADY_COMPLETED", "Onboarding has already been completed", 409)
    target = int(body["weeklyWorkoutTarget"])
    if not 1 <= target <= 14:
        raise error("VALIDATION_ERROR", "weeklyWorkoutTarget must be between 1 and 14")
    payload = {"profileCompleted": True, "experienceLevel": body["experienceLevel"], "goalType": body["goalType"], "weeklyWorkoutTarget": target}
    await save_doc(session, user, "profile", payload, "me")
    await session.commit()
    return {"userId": user, **payload}

@router.patch("/users/me/settings/rest-time", summary="기본 휴식시간 설정")
async def update_settings(body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    required(body, "defaultRestSeconds")
    seconds = int(body["defaultRestSeconds"])
    if not 1 <= seconds <= 900:
        raise error("OUT_OF_RANGE", "defaultRestSeconds must be between 1 and 900")
    current = await get_doc(session, user, "settings", "me")
    payload = (current.payload if current else {}) | {"defaultRestSeconds": seconds, "updatedAt": now_iso()}
    await save_doc(session, user, "settings", payload, "me")
    await session.commit()
    return {"defaultRestSeconds": seconds, "updatedAt": payload["updatedAt"]}

@router.post("/routines", status_code=201, summary="루틴 생성")
async def create_routine(body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    required(body, "name", "exercises")
    exercises = body["exercises"]
    if not isinstance(exercises, list) or not exercises:
        raise error("VALIDATION_ERROR", "A routine must contain at least one exercise")
    for item in exercises:
        required(item, "exerciseId", "sets", "reps", "weight", "restSeconds")
        if int(item["sets"]) < 1 or int(item["reps"]) < 1 or float(item["weight"]) < 0:
            raise error("VALIDATION_ERROR", "Invalid set, rep, or weight value")
    from app.db.documents import new_id
    rid = new_id()
    items = [dict(item, order=int(item.get("order", i))) for i, item in enumerate(exercises)]
    payload = {"routineId": rid, "name": str(body["name"]).strip(), "exercises": items, "createdAt": now_iso()}
    await save_doc(session, user, "routine", payload, rid)
    await session.commit()
    return payload

@router.patch("/routines/{routineId}/exercises", summary="루틴 운동 구성 수정")
async def edit_routine(routineId: str, body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    routine = await owned(session, user, "routine", routineId, "ROUTINE_NOT_FOUND")
    items = [x for x in routine.payload["exercises"] if x.get("exerciseId") not in set(body.get("removeExerciseIds", []))]
    updates = {x.get("exerciseId"): x for x in body.get("update", [])}
    items = [x | updates.get(x.get("exerciseId"), {}) for x in items] + body.get("add", [])
    if not items:
        raise error("EMPTY_ROUTINE", "A routine cannot be empty")
    payload = routine.payload | {"exercises": items, "updatedAt": now_iso()}
    await save_doc(session, user, "routine", payload, routineId)
    await session.commit()
    return payload

@router.put("/routines/{routineId}/exercises/order", summary="루틴 운동 순서 변경")
async def reorder(routineId: str, body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    routine = await owned(session, user, "routine", routineId, "ROUTINE_NOT_FOUND")
    ids = body.get("exerciseIds", [])
    mapping = {x["exerciseId"]: x for x in routine.payload["exercises"]}
    if len(ids) != len(mapping) or set(ids) != set(mapping):
        raise error("INVALID_ORDER", "exerciseIds must contain every exercise exactly once")
    items = [mapping[eid] | {"order": i} for i, eid in enumerate(ids)]
    payload = routine.payload | {"exercises": items, "updatedAt": now_iso()}
    await save_doc(session, user, "routine", payload, routineId)
    await session.commit()
    return {"routineId": routineId, "orderedExercises": items, "updatedAt": payload["updatedAt"]}

@router.put("/routines/{routineId}/playlist", summary="루틴 플레이리스트 연결")
async def set_playlist(routineId: str, body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    routine = await owned(session, user, "routine", routineId, "ROUTINE_NOT_FOUND")
    required(body, "provider", "playlistId", "playlistName", "externalUrl")
    if str(body["provider"]).lower() not in {"spotify", "apple_music", "apple music"}:
        raise error("INVALID_PROVIDER", "provider must be Spotify or Apple Music")
    payload = routine.payload | {"playlist": body, "updatedAt": now_iso()}
    await save_doc(session, user, "routine", payload, routineId)
    await session.commit()
    return {"routineId": routineId, "playlist": body, "updatedAt": payload["updatedAt"]}
