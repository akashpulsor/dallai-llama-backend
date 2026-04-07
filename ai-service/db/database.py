"""Async database setup and lightweight SQL migration runner."""
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Optional
import socket
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine
from urllib.parse import quote_plus

from config import settings

engine: Optional[AsyncEngine] = None
SessionLocal: Optional[async_sessionmaker[AsyncSession]] = None
MIGRATIONS_DIR = Path(__file__).resolve().parent.parent / "migrations"


def _split_sql_statements(sql: str) -> list[str]:
    parts = sql.split(";")
    return [part.strip() for part in parts if part.strip()]

def build_database_url() -> str:
    username = quote_plus(settings.db_username)
    password = quote_plus(settings.db_password)
    host = settings.db_host
    port = settings.db_port
    database = settings.db_name

    return (
        f"postgresql+asyncpg://"
        f"{username}:{password}@{host}:{port}/{database}"
    )

def check_host_port(host: str, port: int, timeout: float = 3.0) -> None:
    """Raise RuntimeError if host:port is unreachable."""
    try:
        with socket.create_connection((host, port), timeout=timeout):
            pass
    except socket.gaierror as e:
        raise RuntimeError(
            f"Database host resolution failed: {host}:{port} ({e})"
        ) from e
    except TimeoutError as e:
        raise RuntimeError(
            f"Database host timeout: {host}:{port}"
        ) from e
    except OSError as e:
        raise RuntimeError(
            f"Database port unreachable: {host}:{port} ({e})"
        ) from e


def init_database() -> None:
    """Initialize the async SQLAlchemy engine if a database URL is configured."""
    global engine, SessionLocal



    if engine is not None:
        return

    # 🔍 check host and port first
    check_host_port(settings.db_host, settings.db_port)
    
    database_url = build_database_url()
    engine = create_async_engine(
        database_url,
        echo=settings.db_echo,
        pool_pre_ping=True,
        pool_size=settings.db_pool_size,
        max_overflow=settings.db_max_overflow,
    )
    SessionLocal = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)


@asynccontextmanager
async def get_session() -> AsyncIterator[AsyncSession]:
    if SessionLocal is None:
        raise RuntimeError("Database is not configured")
    async with SessionLocal() as session:
        yield session


async def close_database() -> None:
    global engine, SessionLocal
    if engine is not None:
        await engine.dispose()
    engine = None
    SessionLocal = None


async def ping_database() -> bool:
    if engine is None:
        return False
    try:
        async with engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
        return True
    except Exception:
        return False


async def run_migrations() -> list[str]:
    if engine is None:
        return []

    applied: list[str] = []
    async with engine.begin() as conn:
        await conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version VARCHAR(255) PRIMARY KEY,
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """
            )
        )

        existing_rows = await conn.execute(text("SELECT version FROM schema_migrations"))
        applied_versions = {row[0] for row in existing_rows.fetchall()}

        for migration_path in sorted(MIGRATIONS_DIR.glob("*.sql")):
            version = migration_path.name
            if version in applied_versions:
                continue

            sql = migration_path.read_text(encoding="utf-8").strip()
            if not sql:
                await conn.execute(
                    text("INSERT INTO schema_migrations (version) VALUES (:version)"),
                    {"version": version},
                )
                applied.append(version)
                continue

            for statement in _split_sql_statements(sql):
                await conn.execute(text(statement))

            await conn.execute(
                text("INSERT INTO schema_migrations (version) VALUES (:version)"),
                {"version": version},
            )
            applied.append(version)

    return applied
