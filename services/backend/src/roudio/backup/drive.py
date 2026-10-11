"""Google Drive with the owner's `drive.file` grant: Roudio only sees the files it creates."""

import asyncio
import hashlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol

import httpx

from roudio.auth.google import TOKEN_URL

FOLDER = "application/vnd.google-apps.folder"
API = "https://www.googleapis.com/drive/v3/files"
UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"


class DriveError(Exception):
    """Drive refused or couldn't be reached; worth trying again later."""


class DriveAccessLost(DriveError):
    """The grant was revoked or expired: the owner has to reconnect Drive."""


@dataclass(frozen=True)
class DriveFileInfo:
    id: str
    md5: str
    size: int


class Drive(Protocol):
    async def folder(self, name: str, parent: str | None) -> str:
        """The id of a folder Roudio made with this name, creating it if needed."""
        ...

    async def upload(
        self, path: Path, name: str, parent: str, mime: str, existing: str | None
    ) -> DriveFileInfo:
        """Upload a new file, or replace the content of `existing`."""
        ...

    async def info(self, file_id: str) -> DriveFileInfo: ...

    async def read_head(self, file_id: str) -> bytes:
        """The first bytes of the file, to prove it can be read back."""
        ...


class HttpDrive:
    def __init__(self, client_id: str, client_secret: str, refresh_token: str) -> None:
        self._client_id = client_id
        self._client_secret = client_secret
        self._refresh = refresh_token
        self._access: str | None = None
        self._http = httpx.AsyncClient(timeout=httpx.Timeout(600.0, connect=30.0))

    async def _token(self) -> str:
        if self._access:
            return self._access
        response = await self._http.post(
            TOKEN_URL,
            data={
                "client_id": self._client_id,
                "client_secret": self._client_secret,
                "refresh_token": self._refresh,
                "grant_type": "refresh_token",
            },
        )
        if response.status_code in (400, 401):
            raise DriveAccessLost("Drive access was removed. Reconnect Google Drive.")
        if not response.is_success:
            raise DriveError(f"Google token refresh failed: HTTP {response.status_code}")
        self._access = str(response.json()["access_token"])
        return self._access

    async def _request(
        self,
        method: str,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        params: dict[str, str] | None = None,
        json: object = None,
        content: bytes | None = None,
    ) -> httpx.Response:
        for attempt in range(2):
            try:
                response = await self._http.request(
                    method,
                    url,
                    headers={"Authorization": f"Bearer {await self._token()}", **(headers or {})},
                    params=params,
                    json=json,
                    content=content,
                )
            except httpx.HTTPError as exc:
                raise DriveError(f"Couldn't reach Drive: {exc}") from exc
            if response.status_code == 401 and attempt == 0:
                self._access = None  # expired: refresh once and try again
                continue
            if response.status_code in (401, 403) and "insufficient" in response.text.lower():
                raise DriveAccessLost("Roudio no longer has Drive access. Reconnect Google Drive.")
            if not response.is_success:
                raise DriveError(f"Drive answered {response.status_code}: {response.text[:200]}")
            return response
        raise DriveError("Drive kept refusing the token")

    async def folder(self, name: str, parent: str | None) -> str:
        safe = name.replace("\\", "\\\\").replace("'", "\\'")
        query = f"name = '{safe}' and mimeType = '{FOLDER}' and trashed = false"
        if parent:
            query += f" and '{parent}' in parents"
        found = (
            await self._request(
                "GET", API, params={"q": query, "fields": "files(id)", "spaces": "drive"}
            )
        ).json()
        if found.get("files"):
            return str(found["files"][0]["id"])
        body: dict[str, object] = {"name": name, "mimeType": FOLDER}
        if parent:
            body["parents"] = [parent]
        created = (await self._request("POST", API, json=body, params={"fields": "id"})).json()
        return str(created["id"])

    async def upload(
        self, path: Path, name: str, parent: str, mime: str, existing: str | None
    ) -> DriveFileInfo:
        params = {"uploadType": "resumable", "fields": "id,md5Checksum,size"}
        if existing:
            start = await self._request(
                "PATCH",
                f"{UPLOAD}/{existing}",
                params=params,
                json={"name": name},
                headers={"X-Upload-Content-Type": mime},
            )
        else:
            start = await self._request(
                "POST",
                UPLOAD,
                params=params,
                json={"name": name, "parents": [parent]},
                headers={"X-Upload-Content-Type": mime},
            )
        location = start.headers.get("location")
        if not location:
            raise DriveError("Drive didn't start the upload")
        response = await self._request(
            "PUT",
            location,
            content=await asyncio.to_thread(path.read_bytes),
            headers={"Content-Type": mime},
        )
        body = response.json()
        return DriveFileInfo(
            str(body["id"]), str(body.get("md5Checksum", "")), int(body.get("size", 0))
        )

    async def info(self, file_id: str) -> DriveFileInfo:
        body = (
            await self._request(
                "GET", f"{API}/{file_id}", params={"fields": "id,md5Checksum,size,trashed"}
            )
        ).json()
        if body.get("trashed"):
            raise DriveError("The Drive copy is in the bin")
        return DriveFileInfo(
            str(body["id"]), str(body.get("md5Checksum", "")), int(body.get("size", 0))
        )

    async def read_head(self, file_id: str) -> bytes:
        response = await self._request(
            "GET", f"{API}/{file_id}", params={"alt": "media"}, headers={"Range": "bytes=0-4095"}
        )
        return response.content


@dataclass
class FakeDrive:
    """In-memory Drive for tests."""

    folders: dict[tuple[str, str | None], str] = field(default_factory=dict)
    files: dict[str, tuple[str, str, bytes]] = field(
        default_factory=dict
    )  # id → (name, parent, content)
    broken: bool = False
    corrupt: bool = False
    uploads: int = 0

    async def folder(self, name: str, parent: str | None) -> str:
        if self.broken:
            raise DriveError("Drive answered 500")
        return self.folders.setdefault((name, parent), f"folder-{len(self.folders) + 1}")

    async def upload(
        self, path: Path, name: str, parent: str, mime: str, existing: str | None
    ) -> DriveFileInfo:
        if self.broken:
            raise DriveError("Drive answered 500")
        self.uploads += 1
        file_id = existing or f"file-{len(self.files) + 1}"
        content = await asyncio.to_thread(path.read_bytes)
        self.files[file_id] = (name, parent, content[:-1] if self.corrupt else content)
        return await self.info(file_id)

    async def info(self, file_id: str) -> DriveFileInfo:
        _, _, content = self.files[file_id]
        return DriveFileInfo(file_id, hashlib.md5(content).hexdigest(), len(content))  # noqa: S324

    async def read_head(self, file_id: str) -> bytes:
        return self.files[file_id][2][:4096]
