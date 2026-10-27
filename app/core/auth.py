from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
import jwt
from jwt import InvalidTokenError

from app.core.config import settings

bearer_scheme = HTTPBearer(auto_error=False)


async def require_user_id(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> str:
    """Validate a user access token and return its subject claim.

    Token issuance is intentionally separate: the supplied API catalog has no login endpoint.
    """
    if credentials is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "UNAUTHORIZED", "message": "Bearer token is required"},
            headers={"WWW-Authenticate": "Bearer"},
        )
    try:
        claims = jwt.decode(
            credentials.credentials,
            settings.jwt_secret.get_secret_value(),
            algorithms=[settings.jwt_algorithm],
            options={"require": ["sub", "exp"]},
        )
    except InvalidTokenError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "UNAUTHORIZED", "message": "Bearer token is invalid or expired"},
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc
    return str(claims["sub"])
