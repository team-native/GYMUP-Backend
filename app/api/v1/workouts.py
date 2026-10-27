from datetime import date, datetime, timedelta, timezone
from typing import Any
from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession
from app.api.v1.common import decode_cursor, encode_cursor, error, now_iso, required, user_id, volume
from app.db.documents import get_doc, list_docs, new_id, save_doc
from app.db.session import get_db_session

router = APIRouter()

async def previous_record_for(session: AsyncSession, user: str, exercise_id: str):
    workouts = await list_docs(session, user, "workout")
    records = [s for w in workouts if w.payload.get("status") == "COMPLETED" for s in w.payload.get("sets", []) if s.get("exerciseId") == exercise_id]
    return max(records, key=lambda s: float(s.get("weight", 0)) * int(s.get("reps", 0)), default=None)

@router.post("/workouts", status_code=201, summary="운동 세션 시작")
async def start_workout(body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    required(body, "routineId")
    routine = await get_doc(session, user, "routine", body["routineId"])
    if not routine:
        raise error("ROUTINE_NOT_FOUND", "Routine not found", 404)
    if any(w.payload.get("status") == "IN_PROGRESS" for w in await list_docs(session, user, "workout")):
        raise error("ACTIVE_WORKOUT_EXISTS", "An active workout already exists", 409)
    wid = new_id()
    snapshot = routine.payload.copy()
    started = body.get("startedAt", now_iso())
    payload = {"workoutId": wid, "routineId": routine.id, "routine": snapshot, "routineName": snapshot["name"], "status": "IN_PROGRESS", "startedAt": started, "timezone": body.get("timezone", "UTC"), "currentExerciseIndex": 0, "sets": [], "prs": []}
    await save_doc(session, user, "workout", payload, wid)
    await session.commit()
    return {"workoutId": wid, "status": "IN_PROGRESS", "routineSnapshot": snapshot, "currentExercise": snapshot["exercises"][0], "startedAt": started, "version": payload.get("version", 1)}

@router.post("/workouts/{workoutId}/sets", status_code=201, summary="운동 세트 기록")
async def record_set(workoutId: str, body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    workout = await get_doc(session, user, "workout", workoutId)
    if not workout:
        raise error("WORKOUT_NOT_FOUND", "Workout not found", 404)
    p = workout.payload
    if p["status"] != "IN_PROGRESS":
        raise error("INVALID_STATE", "Workout is not active", 409)
    required(body, "exerciseId", "setIndex", "weight", "reps")
    idx = p["currentExerciseIndex"]
    if idx >= len(p["routine"]["exercises"]):
        raise error("INVALID_SET", "There is no current exercise")
    planned = p["routine"]["exercises"][idx]
    eid = str(body["exerciseId"])
    if eid != str(planned["exerciseId"]):
        raise error("INVALID_SET", "exerciseId is not the current exercise")
    set_index = int(body["setIndex"])
    if not 1 <= set_index <= int(planned["sets"]):
        raise error("INVALID_SET", "setIndex is outside the planned range")
    if any(s["exerciseId"] == eid and s["setIndex"] == set_index for s in p["sets"]):
        raise error("DUPLICATE_SET", "This set has already been recorded", 409)
    weight, reps = float(body["weight"]), int(body["reps"])
    if weight < 0 or reps < 1:
        raise error("INVALID_SET", "weight must be non-negative and reps must be positive")
    prior = await previous_record_for(session, user, eid)
    is_pr = prior is None or weight * reps > float(prior["weight"]) * int(prior["reps"])
    item = {"setId": new_id(), "exerciseId": eid, "exerciseName": planned.get("name", planned.get("exerciseName", "Exercise")), "setIndex": set_index, "weight": weight, "reps": reps, "completedAt": body.get("completedAt", now_iso()), "pr": is_pr}
    p["sets"] = p["sets"] + [item]
    if is_pr:
        p["prs"] = p["prs"] + [{"exerciseId": eid, "weight": weight, "reps": reps}]
    await save_doc(session, user, "workout", p, workoutId)
    await session.commit()
    count = sum(1 for s in p["sets"] if s["exerciseId"] == eid)
    action = "REST" if count < int(planned["sets"]) else "NEXT_EXERCISE"
    return {**item, "workoutId": workoutId, "nextAction": action, "rest": {"durationSeconds": int(planned.get("restSeconds", 90))} if action == "REST" else None, "version": p["version"]}

@router.get("/exercises/{exerciseId}/previous-record", summary="운동별 이전 기록 조회")
async def previous_record(exerciseId: str, user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session), beforeWorkoutId: str | None = None):
    record = await previous_record_for(session, user, exerciseId)
    if not record:
        return {"exerciseId": exerciseId, "hasPreviousRecord": False}
    return {"exerciseId": exerciseId, "hasPreviousRecord": True, "weight": record["weight"], "reps": record["reps"], "setIndex": record["setIndex"], "performedAt": record["completedAt"]}

@router.get("/workouts/{workoutId}/next-set", summary="다음 세트 조회")
async def next_set(workoutId: str, user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    w = await get_doc(session, user, "workout", workoutId)
    if not w:
        raise error("WORKOUT_OR_NEXT_SET_NOT_FOUND", "Workout not found", 404)
    p = w.payload
    idx = p["currentExerciseIndex"]
    if idx >= len(p["routine"]["exercises"]):
        raise error("WORKOUT_OR_NEXT_SET_NOT_FOUND", "No next set", 404)
    e = p["routine"]["exercises"][idx]
    done = sum(1 for s in p["sets"] if s["exerciseId"] == e["exerciseId"])
    return {"exerciseId": e["exerciseId"], "exerciseName": e.get("name", e.get("exerciseName", "Exercise")), "setIndex": done + 1, "weight": e.get("weight", 0), "reps": e.get("reps", 0), "restEndsAt": None}

@router.patch("/workouts/{workoutId}/current-exercise", summary="다음 운동으로 이동")
async def advance_exercise(workoutId: str, body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    w = await get_doc(session, user, "workout", workoutId)
    if not w:
        raise error("WORKOUT_NOT_FOUND", "Workout not found", 404)
    p = w.payload
    idx = p["currentExerciseIndex"]
    if idx >= len(p["routine"]["exercises"]):
        raise error("INVALID_STATE", "Workout has no current exercise", 409)
    e = p["routine"]["exercises"][idx]
    if body.get("completedExerciseId") != e["exerciseId"]:
        raise error("INVALID_STATE", "completedExerciseId does not match current exercise", 409)
    done = sum(1 for s in p["sets"] if s["exerciseId"] == e["exerciseId"])
    if done < int(e["sets"]):
        raise error("SETS_INCOMPLETE", "Complete all planned sets before advancing")
    p["currentExerciseIndex"] += 1
    nxt = p["routine"]["exercises"][p["currentExerciseIndex"]] if p["currentExerciseIndex"] < len(p["routine"]["exercises"]) else None
    await save_doc(session, user, "workout", p, workoutId)
    await session.commit()
    return {"workoutId": workoutId, "currentExercise": nxt, "nextExercise": nxt, "status": "IN_PROGRESS" if nxt else "READY_TO_COMPLETE", "version": p["version"]}

@router.post("/workouts/{workoutId}/complete", summary="운동 세션 완료")
async def complete_workout(workoutId: str, body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    w = await get_doc(session, user, "workout", workoutId)
    if not w:
        raise error("WORKOUT_NOT_FOUND", "Workout not found", 404)
    p = w.payload
    if p["status"] == "COMPLETED":
        return _completion(p)
    if "expectedVersion" not in body:
        raise error("VALIDATION_ERROR", "expectedVersion is required")
    if int(body["expectedVersion"]) != int(p.get("version", 1)):
        raise error("WORKOUT_VERSION_CONFLICT", "Workout changed; reload its latest version", 409)
    for exercise in p["routine"]["exercises"]:
        done = sum(1 for s in p["sets"] if s["exerciseId"] == exercise["exerciseId"])
        if done < int(exercise["sets"]):
            raise error("WORKOUT_INCOMPLETE", "Complete all planned exercises and sets")
    ended = datetime.fromisoformat(body["endedAt"]) if body.get("endedAt") else datetime.now(timezone.utc)
    started = datetime.fromisoformat(p["startedAt"])
    xp = len(p["sets"]) * 10 + 50
    p.update({"status": "COMPLETED", "endedAt": ended.isoformat(), "duration": max(0, int((ended-started).total_seconds())), "totalSets": len(p["sets"]), "volume": volume(p), "xp": xp, "growthDelta": 10, "completedAt": ended.isoformat()})
    await save_doc(session, user, "workout", p, workoutId)
    await save_doc(session, user, "reward", {"workoutId": workoutId, "xp": xp, "growthDelta": p["growthDelta"], "bonuses": [], "grantedAt": ended.isoformat()}, workoutId)
    await session.commit()
    return _completion(p)

def _completion(p: dict):
    return {"workoutId": p["workoutId"], "status": "COMPLETED", "duration": p.get("duration", 0), "totalSets": p.get("totalSets", len(p.get("sets", []))), "volume": p.get("volume", volume(p)), "prs": p.get("prs", []), "xp": p.get("xp", 0), "growthDelta": p.get("growthDelta", 0), "version": p.get("version", 1)}

@router.get("/workouts/{workoutId}/summary", summary="운동 결과 요약 조회")
async def workout_summary(workoutId: str, user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    w = await get_doc(session, user, "workout", workoutId)
    if not w or w.payload.get("status") != "COMPLETED":
        raise error("SUMMARY_NOT_FOUND", "Completed workout summary not found", 404)
    p = w.payload
    return {"duration": p["duration"], "totalSets": p["totalSets"], "volume": p["volume"], "prs": p["prs"], "growthDelta": p["growthDelta"], "xp": p["xp"], "levelUp": False, "avatarGrowth": await growth_value(session, user), "progressShotAvailable": True}

@router.get("/workouts/{workoutId}", summary="운동 기록 상세 조회")
async def workout_detail(workoutId: str, user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    w = await get_doc(session, user, "workout", workoutId)
    if not w:
        raise error("WORKOUT_NOT_FOUND", "Workout not found", 404)
    p = w.payload
    items = []
    for exercise in p["routine"]["exercises"]:
        items.append({"exerciseId": exercise["exerciseId"], "name": exercise.get("name", exercise.get("exerciseName", "Exercise")), "sets": [s for s in p["sets"] if s["exerciseId"] == exercise["exerciseId"]]})
    return {"workoutId": workoutId, "routine": p["routineName"], "duration": p.get("duration", 0), "totalSets": len(p["sets"]), "volume": volume(p), "exercises": items}

@router.get("/workouts", summary="운동 기록 목록 조회")
async def workout_history(user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session), cursor: str | None = None, size: int = Query(20, ge=1, le=100), from_: date | None = Query(None, alias="from"), to: date | None = None, routineId: str | None = None):
    docs = [d for d in await list_docs(session, user, "workout") if d.payload.get("status") == "COMPLETED"]
    if routineId:
        docs = [d for d in docs if d.payload.get("routineId") == routineId]
    if from_:
        docs = [d for d in docs if date.fromisoformat(d.payload["startedAt"][:10]) >= from_]
    if to:
        docs = [d for d in docs if date.fromisoformat(d.payload["startedAt"][:10]) <= to]
    offset = decode_cursor(cursor)
    page = docs[offset:offset+size]
    return {"items": [{"workoutId": d.id, "date": d.payload["startedAt"], "routineName": d.payload["routineName"], "duration": d.payload["duration"], "totalSets": d.payload["totalSets"], "volume": d.payload["volume"]} for d in page], "nextCursor": encode_cursor(offset, size, len(docs))}

async def growth_value(session: AsyncSession, user: str) -> int:
    completed = sum(1 for w in await list_docs(session, user, "workout") if w.payload.get("status") == "COMPLETED")
    return min(100, completed * 10)

@router.get("/statistics/exercises/{exerciseId}", summary="운동 성장 통계 조회")
async def exercise_statistics(exerciseId: str, user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session), metric: str = "volume", from_: date | None = Query(None, alias="from"), to: date | None = None, interval: str = "week"):
    points: dict[str, float] = {}
    for w in await list_docs(session, user, "workout"):
        if w.payload.get("status") != "COMPLETED":
            continue
        day = date.fromisoformat(w.payload["startedAt"][:10])
        if (from_ and day < from_) or (to and day > to):
            continue
        sets = [s for s in w.payload["sets"] if s["exerciseId"] == exerciseId]
        if not sets:
            continue
        key = day.isoformat() if interval == "day" else (day-timedelta(days=day.weekday())).isoformat()
        value = sum(s["weight"]*s["reps"] for s in sets) if metric == "volume" else max(s["weight"] for s in sets) if metric in {"weight", "estimated1rm"} else len(sets)
        points[key] = points.get(key, 0) + value
    series = [{"date": k, "value": v} for k, v in sorted(points.items())]
    vals = [p["value"] for p in series]
    return {"exerciseId": exerciseId, "metric": metric, "points": series, "summary": {"latest": vals[-1] if vals else None, "best": max(vals) if vals else None}}



