"""Keycloak JWT verification for HTTP and WebSocket endpoints."""
from functools import lru_cache
from typing import Any

import jwt
from fastapi import Depends, HTTPException, Query, WebSocket
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jwt import PyJWKClient

from config import settings

bearer_scheme = HTTPBearer(auto_error=False)


def _algorithms() -> list[str]:
    return [value.strip() for value in settings.keycloak_algorithms.split(",") if value.strip()]


def _issuer() -> str | None:
    if settings.keycloak_issuer:
        return settings.keycloak_issuer.rstrip("/")
    if settings.keycloak_server_url and settings.keycloak_realm:
        return (
            f"{settings.keycloak_server_url.rstrip('/')}/realms/{settings.keycloak_realm}"
        )
    return None


def _jwks_url() -> str | None:
    if settings.keycloak_jwks_url:
        return settings.keycloak_jwks_url
    issuer = _issuer()
    if issuer:
        return f"{issuer}/protocol/openid-connect/certs"
    return None


@lru_cache(maxsize=1)
def _jwks_client() -> PyJWKClient:
    jwks_url = _jwks_url()
    if not jwks_url:
        raise RuntimeError("Keycloak JWKS URL is not configured")
    return PyJWKClient(jwks_url)


def _required_scopes() -> set[str]:
    return {value.strip() for value in settings.keycloak_required_scopes.split() if value.strip()}


def _extract_bearer_token(value: str | None) -> str | None:
    if not value:
        return None
    token_type, _, token = value.partition(" ")
    if token_type.lower() != "bearer" or not token:
        return None
    return token.strip()


def _extract_token_from_websocket(websocket: WebSocket) -> str | None:
    auth_header = websocket.headers.get("authorization")
    header_token = _extract_bearer_token(auth_header)
    if header_token:
        return header_token
    query_token = websocket.query_params.get("access_token") or websocket.query_params.get("token")
    return query_token.strip() if query_token else None


def _validate_scopes(payload: dict[str, Any]) -> None:
    required = _required_scopes()
    if not required:
        return

    token_scopes = set(str(payload.get("scope", "")).split())
    realm_roles = set((((payload.get("realm_access") or {}).get("roles")) or []))
    client_roles = set(
        (((payload.get("resource_access") or {}).get(settings.keycloak_client_id or "", {})).get("roles")) or []
    )
    if required.intersection(token_scopes | realm_roles | client_roles):
        return
    raise HTTPException(status_code=403, detail="Insufficient scope")


def verify_token(token: str) -> dict[str, Any]:
    if not settings.auth_enabled:
        return {"sub": "anonymous", "auth_disabled": True}
    if not token:
        raise HTTPException(status_code=401, detail="Missing bearer token")

    audience = settings.keycloak_audience or settings.keycloak_client_id
    issuer = _issuer()
    options = {"verify_aud": bool(audience), "require": ["exp", "iat"]}
    try:
        signing_key = _jwks_client().get_signing_key_from_jwt(token)
        payload = jwt.decode(
            token,
            signing_key.key,
            algorithms=_algorithms(),
            audience=audience,
            issuer=issuer,
            options=options,
        )
    except Exception as exc:
        if settings.auth_allow_unsafe_dev_tokens:
            try:
                payload = jwt.decode(
                    token,
                    options={
                        "verify_signature": False,
                        "verify_exp": False,
                        "verify_aud": False,
                        "verify_iss": False,
                    },
                )
            except Exception as unsafe_exc:
                raise HTTPException(status_code=401, detail=f"Invalid bearer token: {unsafe_exc}") from unsafe_exc
        else:
            raise HTTPException(status_code=401, detail=f"Invalid bearer token: {exc}") from exc

    _validate_scopes(payload)
    return payload


async def require_auth(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> dict[str, Any]:
    token = credentials.credentials if credentials and credentials.scheme.lower() == "bearer" else None
    return verify_token(token or "")


async def require_optional_query_token(access_token: str | None = Query(default=None)) -> dict[str, Any]:
    return verify_token(access_token or "")


async def authorize_websocket(websocket: WebSocket) -> dict[str, Any]:
    try:
        return verify_token(_extract_token_from_websocket(websocket) or "")
    except HTTPException as exc:
        await websocket.close(code=4401, reason=exc.detail)
        raise


AuthClaims = Depends(require_auth)
