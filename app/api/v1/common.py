import base64
from datetime import datetime, timezone
from typing import Any
from fastapi import Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession
from app.core.auth import require_user_id
from app.db.documents import get_doc

async def user_id(user: str = Depends(require_user_id)) -> str:
    return user

def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()

def error(code: str, message: str, status_code: int = 400) -> HTTPException:
    return HTTPException(status_code=status_code, detail={"code": code, "message": message})

def required(body: dict[str, Any], *keys: str) -> None:
    missing = [key for key in keys if key not in body]
    if missing:
        raise error("VALIDATION_ERROR", "Required fields: " + ", ".join(missing))

async def owned(session: AsyncSession, user: str, kind: str, doc_id: str, missing: str):
    doc = await get_doc(session, user, kind, doc_id)
    if doc is None:
        raise error(missing, f"{kind} not found", 404)
    return doc

def encode_cursor(offset: int, size: int, total: int):
    return base64.urlsafe_b64encode(str(offset + size).encode()).decode().rstrip("=") if offset + size < total else None

def decode_cursor(cursor: str | None):
    if cursor is None:
        return 0
    try:
        return max(0, int(base64.urlsafe_b64decode(cursor + "==").decode()))
    except Exception as exc:
        raise error("INVALID_QUERY", "cursor is invalid") from exc

def volume(workout: dict):
    return sum(float(s.get("weight", 0)) * int(s.get("reps", 0)) for s in workout.get("sets", []))
