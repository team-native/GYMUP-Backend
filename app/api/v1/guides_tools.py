from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession
from app.api.v1.common import error, user_id
from app.db.documents import list_docs
from app.db.session import get_db_session

router = APIRouter()

@router.get("/exercises/{exerciseId}/guide", summary="운동 자세 가이드 조회")
async def exercise_guide(exerciseId: str, user: str = Depends(user_id)):
    raise error("GUIDE_NOT_FOUND", "No guide is configured for this exercise", 404)

@router.get("/exercises/{exerciseId}/first-time-status", summary="최초 운동 여부 조회")
async def first_time_status(exerciseId: str, user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    workouts = await list_docs(session, user, "workout")
    found = any(w.payload.get("status") == "COMPLETED" and any(s.get("exerciseId") == exerciseId for s in w.payload.get("sets", [])) for w in workouts)
    return {"exerciseId": exerciseId, "isFirstTime": not found, "guideAvailable": False}

@router.post("/tools/plate-calculator", summary="원판 조합 계산")
async def plate_calculator(body: dict, user: str = Depends(user_id)):
    for key in ("targetWeight", "barWeight", "availablePlates"):
        if key not in body: raise error("INVALID_WEIGHT", f"{key} is required")
    target = round((float(body["targetWeight"])-float(body["barWeight"]))/2, 3)
    if target < 0: raise error("INVALID_WEIGHT", "targetWeight must be at least barWeight")
    plates = sorted((float(x) for x in body["availablePlates"] if float(x)>0), reverse=True)
    goal = round(target*1000)
    weights = [round(x*1000) for x in plates]
    best = {0: []}
    for amount in range(goal+1):
        if amount not in best: continue
        for index, weight in enumerate(weights):
            total = amount+weight
            if total <= goal and (total not in best or len(best[amount])+1 < len(best[total])):
                best[total] = best[amount]+[index]
    actual = goal if goal in best else max(best)
    counts = {}
    for i in best[actual]: counts[plates[i]] = counts.get(plates[i],0)+1
    return {"achievable":actual==goal,"eachSide":[{"weight":w,"count":n} for w,n in counts.items()],"actualWeight":float(body["barWeight"])+2*actual/1000,"remainder":2*(goal-actual)/1000}
