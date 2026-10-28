from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from sqlalchemy.exc import IntegrityError
from app.api.v1.router import router as v1_router
from app.core.config import settings

app = FastAPI(title="GymUp Backend API", version="0.1.0", description="GymUp MVP API")
app.include_router(v1_router, prefix=settings.api_prefix)

@app.exception_handler(IntegrityError)
async def integrity_conflict(request: Request, exc: IntegrityError):
    return JSONResponse(status_code=409, content={"detail":{"code":"CONFLICT","message":"The resource changed or already exists"}})
