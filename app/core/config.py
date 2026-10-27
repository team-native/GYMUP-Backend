from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="GYMUP_", extra="ignore")
    database_url: str = "postgresql+asyncpg://gymup:gymup@localhost:5432/gymup"
    jwt_secret: SecretStr
    jwt_algorithm: str = "HS256"
    api_prefix: str = "/api/v1"
    environment: str = "production"
    public_base_url: str = "http://localhost:8000"
    media_root: str = "./data/media"

settings = Settings()
