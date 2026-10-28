from datetime import date, datetime, timedelta, timezone
import hashlib
import hmac
from pathlib import Path
from typing import Any
from fastapi import APIRouter, BackgroundTasks, Depends, Query, Request, Response, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.api.v1.common import decode_cursor, encode_cursor, error, now_iso, user_id
from app.core.config import settings
from app.db.documents import get_doc, list_docs, new_id, save_doc
from app.db.models import ProgressPhoto
from app.db.session import get_db_session
from app.api.v1.photo_worker import process_photo_job

router = APIRouter()
public_router = APIRouter()

def sign_download(photo_id: str, owner: str, expires: int) -> str:
    import base64
    digest = hmac.new(settings.jwt_secret.get_secret_value().encode(), f"{photo_id}:{owner}:{expires}".encode(), hashlib.sha256).digest()
    return base64.urlsafe_b64encode(digest).decode().rstrip("=")

@router.post("/workouts/{workoutId}/photos", status_code=201, summary="오운완 사진 업로드 준비")
async def prepare_photo(workoutId: str, body: dict[str, Any], user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    workout = await get_doc(session, user, "workout", workoutId)
    if not workout: raise error("WORKOUT_NOT_FOUND", "Workout not found", 404)
    if workout.payload.get("status") != "COMPLETED": raise error("INVALID_WORKOUT_STATE", "Photo can be attached only to a completed workout", 409)
    for key in ("fileName", "contentType", "size"):
        if key not in body: raise error("INVALID_FILE", f"{key} is required")
    size, content_type = int(body["size"]), body["contentType"]
    if size <= 0: raise error("INVALID_FILE", "File size must be positive")
    if size > 20*1024*1024: raise error("FILE_TOO_LARGE", "Maximum photo size is 20 MiB", 413)
    if content_type not in {"image/jpeg", "image/png", "image/webp"}: raise error("INVALID_FILE", "Only JPEG, PNG, or WebP are accepted")
    pid = new_id()
    expires = datetime.now(timezone.utc)+timedelta(minutes=15)
    url = f"{settings.public_base_url}{settings.api_prefix}/photos/{pid}/content"
    payload = {"photoId":pid,"workoutId":workoutId,"fileName":body["fileName"],"contentType":content_type,"size":size,"status":"READY_FOR_UPLOAD","capturedAt":now_iso(),"expiresAt":expires.isoformat()}
    await save_doc(session,user,"photo",payload,pid)
    await session.commit()
    return {"photoId":pid,"uploadUrl":url,"uploadHeaders":{"Content-Type":content_type},"expiresAt":expires.isoformat()}

@router.put("/photos/{photoId}/content", include_in_schema=False)
async def upload_content(photoId: str, request: Request, user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    photo = await get_doc(session,user,"photo",photoId)
    if not photo: raise error("PHOTO_NOT_FOUND", "Photo not found", 404)
    if datetime.fromisoformat(photo.payload["expiresAt"]) < datetime.now(timezone.utc): raise error("UPLOAD_URL_EXPIRED", "Upload URL expired", 403)
    content = await request.body()
    if len(content) != int(photo.payload["size"]): raise error("INVALID_FILE", "Uploaded size does not match metadata")
    ext = {"image/jpeg":".jpg","image/png":".png","image/webp":".webp"}[photo.payload["contentType"]]
    root = Path(settings.media_root); root.mkdir(parents=True,exist_ok=True)
    path = root/f"{photoId}{ext}"; path.write_bytes(content)
    await save_doc(session,user,"photo",photo.payload|{"status":"READY","storagePath":str(path),"uploadedAt":now_iso()},photoId)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)

@router.get("/photos/{photoId}/download-url", summary="사진 다운로드 URL 조회")
async def download_url(photoId: str, user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session)):
    photo = await get_doc(session,user,"photo",photoId)
    if not photo: raise error("PHOTO_NOT_FOUND", "Photo not found", 404)
    if photo.payload.get("status") != "READY": raise error("PHOTO_NOT_READY", "Photo is not ready", 409)
    expires = int((datetime.now(timezone.utc)+timedelta(minutes=10)).timestamp())
    token = sign_download(photoId,user,expires)
    url = f"{settings.public_base_url}{settings.api_prefix}/photos/{photoId}/download?expires={expires}&token={token}"
    return {"photoId":photoId,"downloadUrl":url,"expiresAt":datetime.fromtimestamp(expires,timezone.utc).isoformat()}

@public_router.get("/photos/{photoId}/download", include_in_schema=False)
async def download_content(photoId: str, expires: int, token: str, session: AsyncSession = Depends(get_db_session)):
    photo = await session.scalar(select(ProgressPhoto).where(ProgressPhoto.id==photoId))
    if not photo: raise error("PHOTO_NOT_FOUND", "Photo not found", 404)
    if expires < int(datetime.now(timezone.utc).timestamp()) or not hmac.compare_digest(token,sign_download(photoId,photo.owner_id,expires)):
        raise error("DOWNLOAD_URL_EXPIRED", "Download URL is invalid or expired", 403)
    path = Path(photo.payload.get("editedPath") or photo.payload.get("storagePath", ""))
    if not path.is_file(): raise error("PHOTO_NOT_READY", "Photo file is missing", 409)
    filename = Path(photo.payload["fileName"]).name.replace('"','')
    return Response(content=path.read_bytes(),media_type="image/jpeg" if photo.payload.get("editedPath") else photo.payload["contentType"],headers={"Content-Disposition":f'attachment; filename="{filename}"'})

@router.get("/photos", summary="Progress Album 조회")
async def photo_album(user: str = Depends(user_id), session: AsyncSession = Depends(get_db_session), cursor: str|None=None, size: int=Query(20,ge=1,le=100), from_: date|None=Query(None,alias="from"), to: date|None=None, sort: str="desc"):
    docs=await list_docs(session,user,"photo")
    if from_: docs=[d for d in docs if date.fromisoformat(d.payload["capturedAt"][:10])>=from_]
    if to: docs=[d for d in docs if date.fromisoformat(d.payload["capturedAt"][:10])<=to]
    if sort.lower()=="asc": docs.reverse()
    offset=decode_cursor(cursor); page=docs[offset:offset+size]
    return {"items":[{"photoId":d.id,"workoutId":d.payload["workoutId"],"capturedAt":d.payload["capturedAt"],"thumbnailUrl":None} for d in page],"nextCursor":encode_cursor(offset,size,len(docs))}

@router.post("/photos/{photoId}/filters", status_code=202, summary="사진 필터 적용")
async def apply_filter(photoId: str, body: dict[str,Any], background_tasks: BackgroundTasks, user: str=Depends(user_id), session: AsyncSession=Depends(get_db_session)):
    photo=await get_doc(session,user,"photo",photoId)
    if not photo: raise error("PHOTO_NOT_FOUND","Photo not found",404)
    if photo.payload.get("status")!="READY": raise error("PHOTO_NOT_READY","Photo is not ready",409)
    preset=body.get("preset")
    if preset not in {"Natural","Pump","Definition","Mirror"}: raise error("INVALID_FILTER","Unknown filter preset")
    jid=new_id()
    await save_doc(session,user,"photo_job",{"jobId":jid,"photoId":photoId,"kind":"FILTER","preset":preset,"adjustments":body.get("adjustments",{}),"status":"PROCESSING","createdAt":now_iso()},jid)
    await session.commit()
    background_tasks.add_task(process_photo_job,user,jid)
    return {"jobId":jid,"photoId":photoId,"status":"PROCESSING"}

@router.post("/photos/{photoId}/stamps", status_code=202, summary="Workout Stamp 합성")
async def apply_stamp(photoId: str, body: dict[str,Any], background_tasks: BackgroundTasks, user: str=Depends(user_id), session: AsyncSession=Depends(get_db_session)):
    photo=await get_doc(session,user,"photo",photoId)
    if not photo: raise error("PHOTO_NOT_FOUND","Photo not found",404)
    if photo.payload.get("status")!="READY": raise error("PHOTO_NOT_READY","Photo is not ready",409)
    if "layoutId" not in body: raise error("INVALID_LAYOUT","layoutId is required")
    jid=new_id()
    await save_doc(session,user,"photo_job",{"jobId":jid,"photoId":photoId,"kind":"STAMP","layoutId":body["layoutId"],"fields":body.get("fields",[]),"status":"PROCESSING","createdAt":now_iso()},jid)
    await session.commit()
    background_tasks.add_task(process_photo_job,user,jid)
    return {"jobId":jid,"photoId":photoId,"status":"PROCESSING"}

@router.get("/photos/jobs/{jobId}", summary="사진 편집 작업 상태 조회")
async def photo_job_status(jobId: str, user: str=Depends(user_id), session: AsyncSession=Depends(get_db_session)):
    job=await get_doc(session,user,"photo_job",jobId)
    if not job: raise error("JOB_NOT_FOUND","Photo job not found",404)
    return {"jobId":jobId,"photoId":job.payload["photoId"],"status":job.payload["status"],"error":job.payload.get("error"),"completedAt":job.payload.get("completedAt")}


