"""Run queued photo transformations outside the HTTP API process."""

import asyncio
import logging
from datetime import datetime, timezone

from sqlalchemy import select

from app.api.v1.photo_worker import process_photo_job
from app.db.models import PhotoJob
from app.db.session import SessionFactory

logger = logging.getLogger("gymup.photo_worker")


async def claim_job() -> tuple[str, str] | None:
    async with SessionFactory() as session:
        async with session.begin():
            job = await session.scalar(
                select(PhotoJob)
                .where(PhotoJob.status == "QUEUED")
                .order_by(PhotoJob.created_at)
                .limit(1)
                .with_for_update(skip_locked=True)
            )
            if job is None:
                return None

            job.status = "PROCESSING"
            job.updated_at = datetime.now(timezone.utc)
            await session.flush()
            return job.owner_id, job.id


async def run_worker() -> None:
    logger.info("GymUp photo worker started")
    while True:
        claimed = await claim_job()
        if claimed is None:
            await asyncio.sleep(1)
            continue

        owner_id, job_id = claimed
        try:
            await process_photo_job(owner_id, job_id)
        except Exception:
            logger.exception("Photo job failed: %s", job_id)


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    try:
        asyncio.run(run_worker())
    except KeyboardInterrupt:
        logger.info("GymUp photo worker stopped")


if __name__ == "__main__":
    main()
