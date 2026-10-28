from pathlib import Path
from datetime import datetime, timezone
from sqlalchemy.ext.asyncio import AsyncSession
from app.core.config import settings
from app.db.documents import get_doc, save_doc
from app.db.session import SessionFactory


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()

async def process_photo_job(owner_id: str, job_id: str) -> None:
    """Small in-process MVP worker. Replace with a durable queue before scaling."""
    async with SessionFactory() as session:
        job = await get_doc(session, owner_id, "photo_job", job_id)
        if not job:
            return
        photo = await get_doc(session, owner_id, "photo", job.payload["photoId"])
        if not photo:
            await save_doc(
                session,
                owner_id,
                "photo_job",
                job.payload | {"status": "FAILED", "error": "PHOTO_NOT_FOUND"},
                job_id,
            )
            await session.commit()
            return
        try:
            from PIL import Image, ImageDraw, ImageEnhance, ImageOps
            source_path = Path(photo.payload["storagePath"])
            image = Image.open(source_path).convert("RGB")
            options = job.payload
            if options["kind"] == "FILTER":
                preset = options["preset"]
                values = {"Natural": (1.03, 1.02, 1.0), "Pump": (1.12, 1.15, 1.0), "Definition": (1.2, 1.0, 1.25), "Mirror": (1.03, 1.0, 1.05)}
                contrast, color, sharpness = values[preset]
                adj = options.get("adjustments", {})
                contrast *= max(0.1, 1 + float(adj.get("contrast", 0))/100)
                sharpness *= max(0.1, 1 + float(adj.get("sharpness", 0))/100)
                image = ImageEnhance.Contrast(image).enhance(contrast)
                image = ImageEnhance.Color(image).enhance(color)
                image = ImageEnhance.Sharpness(image).enhance(sharpness)
                if preset == "Mirror":
                    image = ImageOps.mirror(image)
            else:
                workout = await get_doc(session, owner_id, "workout", photo.payload["workoutId"])
                data = workout.payload if workout else {}
                values = {
                    "routine": data.get("routineName", "Workout"),
                    "duration": f'{int(data.get("duration", 0)) // 60} min',
                    "sets": str(data.get("totalSets", 0)),
                    "growth": str(data.get("growthDelta", 0)),
                    "xp": str(data.get("xp", 0)),
                }
                selected = options.get("fields") or list(values)
                lines = [f"{key.title()}: {values[key]}" for key in selected if key in values]
                if not lines:
                    lines = ["Workout complete"]
                draw = ImageDraw.Draw(image, "RGBA")
                pad = 24
                line_h = 28
                box_h = len(lines)*line_h + pad
                draw.rounded_rectangle((16, image.height-box_h-16, min(image.width-16, 520), image.height-16), radius=12, fill=(0,0,0,165))
                y = image.height-box_h-4
                for line in lines:
                    safe_line = line.encode("latin-1", "replace").decode("latin-1")
                    draw.text((32, y), safe_line, fill=(255,255,255,255))
                    y += line_h
            output = Path(settings.media_root) / f"{photo.id}-edited.jpg"
            image.save(output, format="JPEG", quality=92, optimize=True)
            updated = photo.payload | {"editedPath": str(output), "editedAt": now_iso()}
            await save_doc(session, owner_id, "photo", updated, photo.id)
            await save_doc(
                session,
                owner_id,
                "photo_job",
                job.payload | {"status": "COMPLETED", "completedAt": now_iso()},
                job_id,
            )
        except Exception:
            await save_doc(
                session,
                owner_id,
                "photo_job",
                job.payload | {
                    "status": "FAILED",
                    "error": "IMAGE_PROCESSING_FAILED",
                    "failedAt": now_iso(),
                },
                job_id,
            )
        await session.commit()
