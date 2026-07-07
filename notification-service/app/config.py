import os
from dataclasses import dataclass


def _env(key: str, default: str) -> str:
    value = os.getenv(key)
    return value if value else default


@dataclass
class Settings:
    rabbitmq_url: str
    auth_service_url: str
    db_host: str
    db_port: str
    db_name: str
    db_user: str
    db_password: str

    @property
    def db_dsn(self) -> str:
        return (
            f"postgresql://{self.db_user}:{self.db_password}"
            f"@{self.db_host}:{self.db_port}/{self.db_name}"
        )


def load_settings() -> Settings:
    return Settings(
        rabbitmq_url=_env("RABBITMQ_URL", "amqp://admin:local_dev_placeholder@localhost:5672"),
        auth_service_url=_env("AUTH_SERVICE_URL", "http://localhost:8081"),
        db_host=_env("DB_HOST", "localhost"),
        db_port=_env("DB_PORT", "5432"),
        db_name=_env("DB_NAME", "easydora"),
        db_user=_env("DB_USER", "admin"),
        db_password=_env("DB_PASSWORD", "local_dev_placeholder"),
    )
