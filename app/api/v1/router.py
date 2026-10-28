from fastapi import APIRouter
from app.api.health import router as health_router
from app.api.v1.auth import router as auth_router
from app.api.v1.guides_tools import router as tools_router
from app.api.v1.photos import public_router as photo_public_router, router as photos_router
from app.api.v1.progress import router as progress_router
from app.api.v1.rests import router as rests_router
from app.api.v1.setup_routines import router as setup_router
from app.api.v1.workouts import router as workouts_router

router = APIRouter()
router.include_router(health_router)
router.include_router(auth_router)
router.include_router(setup_router)
router.include_router(workouts_router)
router.include_router(rests_router)
router.include_router(progress_router)
router.include_router(tools_router)
router.include_router(photos_router)
router.include_router(photo_public_router)
