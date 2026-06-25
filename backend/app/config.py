from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    service_name: str = "open-eos-control"
    default_camera_url: str = "http://localhost:18080"
    use_fake_camera: bool = True
    request_timeout_seconds: float = 5.0

    model_config = SettingsConfigDict(env_prefix="OEC_", env_file=".env", extra="ignore")


settings = Settings()
