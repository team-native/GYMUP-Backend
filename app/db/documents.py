from dataclasses import dataclass
from datetime import date, datetime, timezone
from uuid import uuid4
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession
from app.db.models import (Goal, LegacyDocument, PhotoJob, ProgressPhoto, RestTimer, Reward, Routine, RoutineExercise, UserProfile, UserSetting, WeeklyGoal, Workout, WorkoutSet, AvatarItem)

@dataclass
class DomainRecord:
    id: str
    owner_id: str
    payload: dict
    created_at: datetime
    updated_at: datetime

MODELS = {"profile":UserProfile,"settings":UserSetting,"routine":Routine,"workout":Workout,"goal":Goal,"weekly_goal":WeeklyGoal,"reward":Reward,"rest":RestTimer,"photo":ProgressPhoto,"photo_job":PhotoJob,"avatar_item":AvatarItem}

def new_id() -> str:
    return str(uuid4())

def _dt(value):
    if value is None: return None
    if isinstance(value, datetime): return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    return datetime.fromisoformat(str(value).replace("Z", "+00:00"))

def _date(value):
    return value if isinstance(value, date) else date.fromisoformat(str(value)[:10])

def _iso(value):
    return value.isoformat() if value else None

async def _serialize(session: AsyncSession, kind: str, row):
    p = dict(row.payload or {})
    if kind == "profile":
        p.update({"profileCompleted":row.profile_completed,"experienceLevel":row.experience_level,"goalType":row.goal_type,"weeklyWorkoutTarget":row.weekly_workout_target})
        return DomainRecord(row.id,row.owner_id,p,row.created_at,row.updated_at)
    if kind == "settings":
        p.update({"defaultRestSeconds":row.default_rest_seconds,"updatedAt":_iso(row.updated_at)})
        return DomainRecord(row.id,row.owner_id,p,row.created_at,row.updated_at)
    if kind == "routine":
        result = await session.scalars(select(RoutineExercise).where(RoutineExercise.owner_id==row.owner_id,RoutineExercise.routine_id==row.id).order_by(RoutineExercise.order_index))
        exercises=[]
        for e in result:
            exercises.append(dict(e.extra or {},exerciseId=e.exercise_id,sets=e.set_count,reps=e.reps,weight=e.weight,restSeconds=e.rest_seconds,order=e.order_index,**({"name":e.name} if e.name else {})))
        p.update({"routineId":row.id,"name":row.name,"exercises":exercises,"createdAt":_iso(row.created_at)})
        if row.playlist is not None: p["playlist"]=row.playlist
        else: p.pop("playlist",None)
    elif kind == "workout":
        result=await session.scalars(select(WorkoutSet).where(WorkoutSet.owner_id==row.owner_id,WorkoutSet.workout_id==row.id).order_by(WorkoutSet.completed_at,WorkoutSet.set_index))
        sets=[]
        for s in result:
            sets.append({"setId":s.set_id,"exerciseId":s.exercise_id,"exerciseName":s.exercise_name,"setIndex":s.set_index,"weight":s.weight,"reps":s.reps,"completedAt":_iso(s.completed_at),"pr":s.is_pr})
        p.update({"workoutId":row.id,"routineId":row.routine_id,"routineName":row.routine_name,"status":row.status,"startedAt":_iso(row.started_at),"endedAt":_iso(row.ended_at),"timezone":row.timezone_name,"currentExerciseIndex":row.current_exercise_index,"duration":row.duration,"totalSets":row.total_sets,"volume":row.volume,"xp":row.xp,"growthDelta":row.growth_delta,"version":row.version,"sets":sets,"prs":[{"exerciseId":s["exerciseId"],"weight":s["weight"],"reps":s["reps"]} for s in sets if s["pr"]]})
    elif kind == "goal":
        p.update({"goalId":row.id,"goalType":row.goal_type,"startDate":row.start_date.isoformat(),"endDate":row.end_date.isoformat(),"weeklyWorkoutTarget":row.weekly_workout_target,"status":row.status,"growth":row.growth})
    elif kind == "weekly_goal":
        p.update({"week":row.week_start.isoformat(),"targetCount":row.target_count,"completedCount":row.completed_count,"achieved":row.achieved,"goalId":row.goal_id})
    elif kind == "reward":
        p.update({"workoutId":row.workout_id,"xp":row.xp,"growthDelta":row.growth_delta,"grantedAt":_iso(row.granted_at)})
    elif kind == "rest":
        p.update({"restId":row.id,"workoutId":row.workout_id,"setId":row.set_id,"status":row.status,"startedAt":_iso(row.started_at),"endsAt":_iso(row.ends_at),"durationSeconds":row.duration_seconds})
    elif kind == "photo":
        p.update({"photoId":row.id,"workoutId":row.workout_id,"fileName":row.file_name,"contentType":row.content_type,"size":row.size,"status":row.status,"capturedAt":_iso(row.captured_at),"expiresAt":_iso(row.expires_at)})
        if row.storage_path: p["storagePath"]=row.storage_path
        if row.edited_path: p["editedPath"]=row.edited_path
    elif kind == "photo_job":
        p.update({"jobId":row.id,"photoId":row.photo_id,"kind":row.job_type,"status":row.status})
        p.update(row.options or {})
    elif kind == "avatar_item":
        p.update({"itemId":row.id,"type":row.item_type,"unlocked":row.unlocked})
    row.payload=p
    return row

async def get_doc(session: AsyncSession, owner_id: str, kind: str, doc_id: str, *, for_update: bool = True):
    model=MODELS.get(kind)
    if model:
        query=select(model).where(model.owner_id==owner_id,model.id==doc_id)
        if for_update: query=query.with_for_update()
        row=await session.scalar(query)
        if row is not None: return await _serialize(session,kind,row)
    legacy_query=select(LegacyDocument).where(LegacyDocument.owner_id==owner_id,LegacyDocument.kind==kind,LegacyDocument.id==doc_id)
    if for_update: legacy_query=legacy_query.with_for_update()
    legacy=await session.scalar(legacy_query)
    if legacy is None or model is None:
        return legacy
    payload=dict(legacy.payload)
    parent={"workout":("routine",payload.get("routineId")),"rest":("workout",payload.get("workoutId")),"reward":("workout",payload.get("workoutId")),"photo":("workout",payload.get("workoutId")),"photo_job":("photo",payload.get("photoId"))}.get(kind)
    if parent and parent[1] and await get_doc(session,owner_id,parent[0],str(parent[1])) is None:
        return legacy
    migrated=await save_doc(session,owner_id,kind,payload,doc_id)
    return await _serialize(session,kind,migrated)
async def list_docs(session: AsyncSession, owner_id: str, kind: str):
    model=MODELS.get(kind)
    rows=[]
    known=set()
    if model:
        result=await session.scalars(select(model).where(model.owner_id==owner_id).order_by(model.created_at.desc()))
        for row in result:
            rows.append(await _serialize(session,kind,row)); known.add(row.id)
    legacy=await session.scalars(select(LegacyDocument).where(LegacyDocument.owner_id==owner_id,LegacyDocument.kind==kind).order_by(LegacyDocument.created_at.desc()))
    rows.extend(row for row in legacy if row.id not in known)
    return sorted(rows,key=lambda row:row.created_at,reverse=True)

async def _sync_children(session: AsyncSession, kind: str, row, user: str, payload: dict):
    if kind == "routine":
        await session.execute(delete(RoutineExercise).where(RoutineExercise.owner_id==user,RoutineExercise.routine_id==row.id))
        for i,e in enumerate(payload.get("exercises", [])):
            known={"exerciseId","sets","reps","weight","restSeconds","order","name"}
            session.add(RoutineExercise(owner_id=user,routine_id=row.id,exercise_id=str(e["exerciseId"]),order_index=int(e.get("order",i)),name=e.get("name"),set_count=int(e["sets"]),reps=int(e["reps"]),weight=float(e.get("weight",0)),rest_seconds=int(e.get("restSeconds",90)),extra={k:v for k,v in e.items() if k not in known}))
    elif kind == "workout":
        await session.execute(delete(WorkoutSet).where(WorkoutSet.owner_id==user,WorkoutSet.workout_id==row.id))
        for s in payload.get("sets", []):
            session.add(WorkoutSet(set_id=str(s["setId"]),owner_id=user,workout_id=row.id,exercise_id=str(s["exerciseId"]),exercise_name=s.get("exerciseName"),set_index=int(s["setIndex"]),weight=float(s["weight"]),reps=int(s["reps"]),completed_at=_dt(s.get("completedAt")),is_pr=bool(s.get("pr",False))))

async def save_doc(session: AsyncSession, owner_id: str, kind: str, payload: dict, doc_id: str | None = None):
    model=MODELS.get(kind)
    if model is None:
        raise ValueError(f"No domain model for document kind {kind}")
    key=doc_id or payload.get("id") or payload.get({"profile":"userId","settings":"userId","routine":"routineId","workout":"workoutId","goal":"goalId","reward":"workoutId","rest":"restId","photo":"photoId","photo_job":"jobId","avatar_item":"itemId"}.get(kind,"id")) or new_id()
    row=await session.scalar(select(model).where(model.owner_id==owner_id,model.id==str(key)).with_for_update())
    is_new = row is None
    legacy=None
    if row is None:
        legacy=await session.scalar(select(LegacyDocument).where(LegacyDocument.owner_id==owner_id,LegacyDocument.kind==kind,LegacyDocument.id==str(key)).with_for_update())
        row=model(id=str(key),owner_id=owner_id)
        session.add(row)
        row.created_at=legacy.created_at if legacy else datetime.now(timezone.utc)
        if legacy is not None: row.updated_at=legacy.updated_at
    _store_payload(kind,row,payload,is_new)
    await session.flush()
    await _sync_children(session,kind,row,owner_id,payload)
    if legacy is not None:
        await session.delete(legacy)
    await session.flush()
    if kind == "workout": payload["version"]=row.version
    return row

def _store_payload(kind: str, row, p: dict, is_new: bool):
    if kind == "profile":
        row.profile_completed=bool(p.get("profileCompleted",False)); row.experience_level=p.get("experienceLevel"); row.goal_type=p.get("goalType"); row.weekly_workout_target=p.get("weeklyWorkoutTarget")
        row.payload={k:v for k,v in p.items() if k not in {"profileCompleted","experienceLevel","goalType","weeklyWorkoutTarget","userId"}}
    elif kind == "settings":
        row.default_rest_seconds=p.get("defaultRestSeconds"); row.payload={k:v for k,v in p.items() if k not in {"defaultRestSeconds","updatedAt"}}
    elif kind == "routine":
        row.name=str(p.get("name",row.name if row.name else "Routine")); row.playlist=p.get("playlist")
        row.payload={k:v for k,v in p.items() if k not in {"routineId","name","exercises","playlist","createdAt","updatedAt"}}
    elif kind == "workout":
        row.routine_id=str(p["routineId"]); row.routine_name=str(p.get("routineName","Workout")); row.status=p["status"]; row.started_at=_dt(p["startedAt"]); row.ended_at=_dt(p.get("endedAt")); row.timezone_name=str(p.get("timezone","UTC")); row.current_exercise_index=int(p.get("currentExerciseIndex",0)); row.duration=p.get("duration"); row.total_sets=int(p.get("totalSets",len(p.get("sets",[])))); row.volume=float(p.get("volume",0)); row.xp=int(p.get("xp",0)); row.growth_delta=int(p.get("growthDelta",0))
        row.version = int(p.get("version", 1)) if is_new else int(row.version or 1) + 1
        row.payload={k:v for k,v in p.items() if k not in {"workoutId","routineId","routineName","status","startedAt","endedAt","timezone","currentExerciseIndex","duration","totalSets","volume","xp","growthDelta","sets","prs","version","completedAt"}}
    elif kind == "goal":
        row.goal_type=str(p["goalType"]); row.start_date=_date(p["startDate"]); row.end_date=_date(p["endDate"]); row.weekly_workout_target=int(p["weeklyWorkoutTarget"]); row.status=str(p["status"]); row.growth=int(p.get("growth",0)); row.payload={k:v for k,v in p.items() if k not in {"goalId","goalType","startDate","endDate","weeklyWorkoutTarget","status","growth"}}
    elif kind == "weekly_goal":
        row.week_start=_date(p.get("week",row.id)); row.target_count=int(p.get("targetCount",0)); row.completed_count=int(p.get("completedCount",0)); row.achieved=bool(p.get("achieved",False)); row.goal_id=p.get("goalId"); row.payload={}
    elif kind == "reward":
        row.workout_id=str(p["workoutId"]); row.xp=int(p.get("xp",0)); row.growth_delta=int(p.get("growthDelta",0)); row.granted_at=_dt(p.get("grantedAt")); row.payload={k:v for k,v in p.items() if k not in {"workoutId","xp","growthDelta","grantedAt"}}
    elif kind == "rest":
        row.workout_id=str(p["workoutId"]); row.set_id=str(p["setId"]); row.status=str(p["status"]); row.started_at=_dt(p["startedAt"]); row.ends_at=_dt(p["endsAt"]); row.duration_seconds=int(p["durationSeconds"]); row.payload={k:v for k,v in p.items() if k not in {"restId","workoutId","setId","status","startedAt","endsAt","durationSeconds"}}
    elif kind == "photo":
        row.workout_id=str(p["workoutId"]); row.file_name=str(p["fileName"]); row.content_type=str(p["contentType"]); row.size=int(p["size"]); row.status=str(p["status"]); row.storage_path=p.get("storagePath"); row.edited_path=p.get("editedPath"); row.captured_at=_dt(p["capturedAt"]); row.expires_at=_dt(p["expiresAt"]); row.payload={k:v for k,v in p.items() if k not in {"photoId","workoutId","fileName","contentType","size","status","storagePath","editedPath","capturedAt","expiresAt"}}
    elif kind == "photo_job":
        row.photo_id=str(p["photoId"]); row.job_type=str(p.get("kind","FILTER")); row.status=str(p["status"]); row.options={k:v for k,v in p.items() if k not in {"jobId","photoId","kind","status"}}; row.payload={}
    elif kind == "avatar_item":
        row.item_type=p.get("type"); row.unlocked=bool(p.get("unlocked",False)); row.payload={k:v for k,v in p.items() if k not in {"itemId","type","unlocked"}}








