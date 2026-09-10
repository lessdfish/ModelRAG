import secrets

from fastapi import HTTPException

from .config import Settings


def require_internal_auth(authorization: str | None, settings: Settings) -> None:
    """Require the internal service credential; it has no end-user ACL meaning."""
    if not settings.auth_token:
        raise HTTPException(status_code=503, detail="AI service authentication is not configured")
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="AI service authentication failed")
    presented = authorization[7:].strip()
    if not presented or not secrets.compare_digest(presented, settings.auth_token):
        raise HTTPException(status_code=401, detail="AI service authentication failed")
