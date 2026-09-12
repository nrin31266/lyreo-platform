"""Core Admin API client with OIDC Authorization Code + PKCE.

The tool is a public OIDC client: no static admin secret, no embedded client
secret, no hard-coded username/password. Core validates the normal ADMIN JWT.
"""

from __future__ import annotations

import base64
import hashlib
import secrets
import threading
import time
import webbrowser
from pathlib import Path
from typing import Any

import httpx


class CoreApiError(RuntimeError):
    pass


class OidcClient:
    """Authorization Code + PKCE (S256) against the Lyreo Keycloak realm."""

    def __init__(self, issuer: str, client_id: str, redirect_uri: str):
        self.issuer = issuer.rstrip("/")
        self.client_id = client_id
        self.redirect_uri = redirect_uri
        self._pending: dict[str, tuple[str, float]] = {}  # state -> (code_verifier, created_at)
        self._active_login_state: str | None = None
        self._lock = threading.Lock()
        self._token_lock = threading.Lock()
        self._access_token: str | None = None
        self._refresh_token: str | None = None
        self._expires_at: float = 0.0
        self._config: dict[str, str] | None = None
        self._http = httpx.Client(timeout=60)

    def _discover(self) -> dict[str, str]:
        if self._config is None:
            try:
                response = self._http.get(
                    f"{self.issuer}/.well-known/openid-configuration"
                )
                response.raise_for_status()
                data = response.json()
                self._config = {
                    "authorization_endpoint": data["authorization_endpoint"],
                    "token_endpoint": data["token_endpoint"],
                }
            except httpx.HTTPError as exc:
                raise CoreApiError(
                    f"Unable to discover Keycloak OIDC endpoints at {self.issuer}: {exc}"
                ) from exc
        return self._config

    def start_login(self) -> str:
        """Returns the authorization URL and stores the PKCE verifier."""
        verifier = secrets.token_urlsafe(64)
        challenge = base64.urlsafe_b64encode(
            hashlib.sha256(verifier.encode()).digest()
        ).rstrip(b"=").decode()
        state = secrets.token_urlsafe(16)
        now = time.monotonic()
        with self._lock:
            # prune abandoned logins older than 5 minutes
            stale = [s for s, (_, created) in self._pending.items() if now - created > 300]
            for s in stale:
                self._pending.pop(s, None)
            self._pending[state] = (verifier, now)
            self._active_login_state = state

        endpoints = self._discover()
        from urllib.parse import urlencode
        params = urlencode({
            "client_id": self.client_id,
            "response_type": "code",
            "scope": "openid",
            "redirect_uri": self.redirect_uri,
            "state": state,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
        })
        return f"{endpoints['authorization_endpoint']}?{params}"

    def login(self) -> bool:
        """Opens the browser and blocks until THIS login's callback completes.

        Waiting for the specific pending state (instead of any access token) makes
        re-login/account switching behave predictably: clicking login again always
        starts a fresh browser flow.
        """
        auth_url = self.start_login()
        webbrowser.open(auth_url)
        state = self._active_login_state
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            with self._lock:
                consumed = state not in self._pending
            if consumed:
                return self._access_token is not None
            time.sleep(0.5)
        return False

    def complete(self, code: str, state: str) -> None:
        with self._lock:
            entry = self._pending.pop(state, None)
        if not entry:
            raise CoreApiError("OIDC callback used an unknown state")
        verifier, _ = entry
        self._exchange("authorization_code", code=code, redirect_uri=self.redirect_uri,
                       code_verifier=verifier)

    def _exchange(self, grant_type: str, **extra: str) -> None:
        endpoints = self._discover()
        data = {
            "client_id": self.client_id,
            "grant_type": grant_type,
        }
        data.update(extra)
        response = self._http.post(endpoints["token_endpoint"], data=data)
        if response.status_code >= 400:
            raise CoreApiError(f"OIDC token exchange failed with HTTP {response.status_code}")
        tokens = response.json()
        self._access_token = tokens["access_token"]
        self._refresh_token = tokens.get("refresh_token", self._refresh_token)
        self._expires_at = time.monotonic() + float(tokens.get("expires_in", 60)) - 15

    def _ensure_token(self) -> str:
        if self._access_token and time.monotonic() < self._expires_at:
            return self._access_token
        # Serialize refresh exchanges: Keycloak revokes the session when the same
        # refresh token is presented twice (refresh token reuse detection).
        with self._token_lock:
            if self._access_token and time.monotonic() < self._expires_at:
                return self._access_token
            if self._refresh_token:
                self._exchange("refresh_token", refresh_token=self._refresh_token)
                return self._access_token or ""
        raise CoreApiError("Not logged in to Core; click Login first")

    def access_token(self) -> str:
        return self._ensure_token()


class CoreApiClient:
    def __init__(self, base_url: str, oidc: OidcClient, timeout: float = 600.0):
        self.base_url = base_url.rstrip("/")
        self.oidc = oidc
        self.timeout = timeout
        self._http = httpx.Client(timeout=timeout)

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.oidc.access_token()}"}

    def _raise(self, response: httpx.Response) -> None:
        try:
            detail = response.json().get("detail", response.text[:200])
        except Exception:
            detail = response.text[:200]
        raise CoreApiError(f"Core API {response.request.method} {response.request.url.path} "
                           f"failed with HTTP {response.status_code}: {detail}")

    def upload_media(self, kind: str, path: Path) -> dict[str, Any]:
        with path.open("rb") as handle:
            files = {"file": (path.name, handle, "application/octet-stream")}
            data = {"kind": kind}
            try:
                response = self._http.post(
                    f"{self.base_url}/api/v1/admin/lessons/media",
                    files=files,
                    data=data,
                    headers=self._headers(),
                )
            except httpx.HTTPError as exc:
                raise CoreApiError(f"Core media upload failed: {exc}") from exc
        if response.status_code >= 400:
            self._raise(response)
        return response.json()
    def health(self) -> bool:
        try:
            response = self._http.get(f"{self.base_url}/actuator/health")
            return response.status_code == 200
        except httpx.HTTPError:
            return False
