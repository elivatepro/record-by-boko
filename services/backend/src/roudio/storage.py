"""Original audio in the bucket (spec §2, decision 7), behind a small interface.

The api only ever streams parts in and reads a finished file back to check it; boto3 is
synchronous, so every call runs in a thread.
"""

import asyncio
import hashlib
from collections.abc import Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING, Protocol

import boto3
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError

if TYPE_CHECKING:
    from mypy_boto3_s3 import S3Client
    from mypy_boto3_s3.type_defs import CompletedPartTypeDef


class StorageError(Exception):
    """The bucket couldn't be reached or refused the request."""


@dataclass(frozen=True)
class Part:
    number: int
    size: int
    etag: str


class AudioStore(Protocol):
    async def start_upload(self, key: str) -> str: ...

    async def put_part(self, key: str, upload_id: str, number: int, data: bytes) -> None: ...

    async def parts(self, key: str, upload_id: str) -> list[Part]: ...

    async def complete(self, key: str, upload_id: str, parts: list[Part]) -> None: ...

    async def abort(self, key: str, upload_id: str) -> None: ...

    async def digest(self, key: str) -> tuple[str, int]:
        """SHA-256 (hex) and size of a stored file, read back from the bucket."""
        ...

    async def delete(self, key: str) -> None: ...


class S3AudioStore:
    def __init__(
        self,
        bucket: str,
        *,
        endpoint: str | None,
        access_key_id: str | None,
        secret_access_key: str | None,
        region: str,
        path_style: bool = False,
    ) -> None:
        self._bucket = bucket
        self._s3: S3Client = boto3.client(
            "s3",
            endpoint_url=endpoint,
            aws_access_key_id=access_key_id,
            aws_secret_access_key=secret_access_key,
            region_name=region,
            config=Config(
                s3={"addressing_style": "path" if path_style else "virtual"},
                retries={"max_attempts": 5, "mode": "standard"},
            ),
        )

    async def start_upload(self, key: str) -> str:
        def call() -> str:
            response = self._s3.create_multipart_upload(
                Bucket=self._bucket, Key=key, ContentType="audio/ogg"
            )
            return response["UploadId"]

        return await self._run(call)

    async def put_part(self, key: str, upload_id: str, number: int, data: bytes) -> None:
        def call() -> None:
            self._s3.upload_part(
                Bucket=self._bucket, Key=key, UploadId=upload_id, PartNumber=number, Body=data
            )

        await self._run(call)

    async def parts(self, key: str, upload_id: str) -> list[Part]:
        def call() -> list[Part]:
            found: list[Part] = []
            marker = 0
            while True:
                response = self._s3.list_parts(
                    Bucket=self._bucket, Key=key, UploadId=upload_id, PartNumberMarker=marker
                )
                found += [
                    Part(p["PartNumber"], p["Size"], p["ETag"]) for p in response.get("Parts", [])
                ]
                if not response.get("IsTruncated"):
                    return sorted(found, key=lambda p: p.number)
                marker = response["NextPartNumberMarker"]

        return await self._run(call)

    async def complete(self, key: str, upload_id: str, parts: list[Part]) -> None:
        completed: list[CompletedPartTypeDef] = [
            {"PartNumber": p.number, "ETag": p.etag} for p in parts
        ]

        def call() -> None:
            self._s3.complete_multipart_upload(
                Bucket=self._bucket,
                Key=key,
                UploadId=upload_id,
                MultipartUpload={"Parts": completed},
            )

        await self._run(call)

    async def abort(self, key: str, upload_id: str) -> None:
        def call() -> None:
            try:
                self._s3.abort_multipart_upload(Bucket=self._bucket, Key=key, UploadId=upload_id)
            except ClientError as exc:
                if exc.response.get("Error", {}).get("Code") != "NoSuchUpload":
                    raise

        await self._run(call)

    async def digest(self, key: str) -> tuple[str, int]:
        def call() -> tuple[str, int]:
            body = self._s3.get_object(Bucket=self._bucket, Key=key)["Body"]
            sha = hashlib.sha256()
            size = 0
            for chunk in body.iter_chunks(1024 * 1024):
                sha.update(chunk)
                size += len(chunk)
            return sha.hexdigest(), size

        return await self._run(call)

    async def delete(self, key: str) -> None:
        def call() -> None:
            self._s3.delete_object(Bucket=self._bucket, Key=key)

        await self._run(call)

    @staticmethod
    async def _run[T](call: Callable[[], T]) -> T:
        try:
            return await asyncio.to_thread(call)
        except (BotoCoreError, ClientError) as exc:
            raise StorageError(str(exc)) from exc


class UnconfiguredAudioStore:
    """Used when no bucket is set: every call fails, so uploads answer "not set up yet"."""

    message = "audio storage isn't configured (AUDIO_BUCKET)"

    async def start_upload(self, key: str) -> str:
        raise StorageError(self.message)

    async def put_part(self, key: str, upload_id: str, number: int, data: bytes) -> None:
        raise StorageError(self.message)

    async def parts(self, key: str, upload_id: str) -> list[Part]:
        raise StorageError(self.message)

    async def complete(self, key: str, upload_id: str, parts: list[Part]) -> None:
        raise StorageError(self.message)

    async def abort(self, key: str, upload_id: str) -> None:
        raise StorageError(self.message)

    async def digest(self, key: str) -> tuple[str, int]:
        raise StorageError(self.message)

    async def delete(self, key: str) -> None:
        raise StorageError(self.message)
