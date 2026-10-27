from datetime import datetime, timedelta, timezone
import jwt
from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field
from app.core.config import settings

router = APIRouter(tags=["auth"])
class DevTokenRequest(BaseModel):
    userId: str | None = Field(default=None, min_length=1, max_length=128)

@router.post("/auth/dev-token", summary="개발용 사용자 JWT 발급")
async def issue_dev_token(body: DevTokenRequest):
    if settings.environment != "development":
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")
    user_id = body.userId or "local-user"
    expires_at = datetime.now(timezone.utc) + timedelta(hours=12)
    token = jwt.encode({"sub": user_id, "iat": datetime.now(timezone.utc), "exp": expires_at}, settings.jwt_secret.get_secret_value(), algorithm=settings.jwt_algorithm)
    return {"accessToken": token, "tokenType": "Bearer", "expiresAt": expires_at.isoformat()}
