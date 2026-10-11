import hashlib
from typing import Any

import pytest
from httpx import AsyncClient

from roudio.api.deps import Services
from roudio.storage import UnconfiguredAudioStore
from tests.conftest import TEST_BUCKET, sign_in

PART = 8 * 1024 * 1024
SERIAL = "8810000004F2A"


@pytest.fixture
async def auth(client: AsyncClient) -> dict[str, str]:
    tokens = await sign_in(client)
    return {"Authorization": f"Bearer {tokens['access_token']}"}


def metadata(data: bytes, **overrides: Any) -> dict[str, Any]:
    return {
        "started_at": "2026-10-11T09:13:54Z",
        "zone_offset_seconds": 3600,
        "duration_ms": 7200,
        "size_bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        **overrides,
    }


async def start(
    client: AsyncClient, auth: dict[str, str], data: bytes, session: int = 1791710034, **extra: Any
) -> dict[str, Any]:
    response = await client.put(
        f"/recordings/{SERIAL}/{session}", json=metadata(data, **extra), headers=auth
    )
    assert response.status_code == 200, response.text
    body: dict[str, Any] = response.json()
    return body


async def send(
    client: AsyncClient, auth: dict[str, str], recording_id: str, number: int, data: bytes
) -> Any:
    return await client.put(
        f"/recordings/{recording_id}/parts/{number}",
        content=data,
        headers={**auth, "Content-Type": "application/octet-stream"},
    )


async def test_uploads_need_a_session(client: AsyncClient) -> None:
    response = await client.put(f"/recordings/{SERIAL}/1", json=metadata(b"x"))
    assert response.status_code == 401


async def test_a_small_recording_goes_up_in_one_part_and_is_checked(
    client: AsyncClient, auth: dict[str, str], s3: Any
) -> None:
    data = b"OggS" + bytes(range(256)) * 40
    upload = await start(client, auth, data)
    assert upload["state"] == "uploading"
    assert (upload["parts"], upload["received_bytes"], upload["next_part"]) == (1, 0, 1)

    sent = (await send(client, auth, upload["id"], 1, data)).json()
    assert (sent["received_bytes"], sent["next_part"]) == (len(data), None)

    done = await client.post(f"/recordings/{upload['id']}/complete", headers=auth)
    assert done.status_code == 200, done.text
    assert done.json()["state"] == "uploaded"
    stored = s3.get_object(Bucket=TEST_BUCKET, Key=f"recordings/{SERIAL}/1791710034.opus")
    assert stored["Body"].read() == data

    # Asking again (say, after the phone lost the answer) just says it's there.
    again = await start(client, auth, data)
    assert (again["id"], again["state"]) == (upload["id"], "uploaded")
    repeat = await client.post(f"/recordings/{upload['id']}/complete", headers=auth)
    assert repeat.json()["state"] == "uploaded"


async def test_an_interrupted_upload_carries_on_from_the_last_part(
    client: AsyncClient, auth: dict[str, str]
) -> None:
    data = bytes(PART) + b"tail of the recording"
    upload = await start(client, auth, data)
    assert upload["parts"] == 2
    await send(client, auth, upload["id"], 1, data[:PART])

    resumed = await start(client, auth, data)
    assert (resumed["id"], resumed["received_bytes"], resumed["next_part"]) == (
        upload["id"],
        PART,
        2,
    )
    await send(client, auth, upload["id"], 2, data[PART:])
    done = await client.post(f"/recordings/{upload['id']}/complete", headers=auth)
    assert done.json()["state"] == "uploaded"


async def test_a_part_of_the_wrong_size_is_refused(
    client: AsyncClient, auth: dict[str, str]
) -> None:
    data = bytes(PART) + b"tail"
    upload = await start(client, auth, data)
    response = await send(client, auth, upload["id"], 1, data[: PART - 1])
    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "wrong_part_size"
    assert (await send(client, auth, upload["id"], 3, b"x")).json()["detail"][
        "code"
    ] == "no_such_part"


async def test_completing_with_parts_missing_is_refused(
    client: AsyncClient, auth: dict[str, str]
) -> None:
    data = bytes(PART) + b"tail"
    upload = await start(client, auth, data)
    await send(client, auth, upload["id"], 2, b"tail")
    response = await client.post(f"/recordings/{upload['id']}/complete", headers=auth)
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "parts_missing"


async def test_a_checksum_mismatch_throws_the_copy_away_and_starts_again(
    client: AsyncClient, auth: dict[str, str], s3: Any
) -> None:
    data = b"what the phone sends"
    upload = await start(client, auth, data, sha256=hashlib.sha256(b"something else").hexdigest())
    await send(client, auth, upload["id"], 1, data)
    response = await client.post(f"/recordings/{upload['id']}/complete", headers=auth)
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "checksum_mismatch"
    assert s3.list_objects_v2(Bucket=TEST_BUCKET).get("KeyCount") == 0

    restarted = await start(client, auth, data)
    assert (restarted["state"], restarted["received_bytes"]) == ("uploading", 0)
    await send(client, auth, restarted["id"], 1, data)
    done = await client.post(f"/recordings/{restarted['id']}/complete", headers=auth)
    assert done.json()["state"] == "uploaded"


async def test_a_different_file_for_an_uploaded_recording_is_a_conflict(
    client: AsyncClient, auth: dict[str, str]
) -> None:
    data = b"first export"
    upload = await start(client, auth, data)
    await send(client, auth, upload["id"], 1, data)
    await client.post(f"/recordings/{upload['id']}/complete", headers=auth)

    response = await client.put(
        f"/recordings/{SERIAL}/1791710034", json=metadata(b"another"), headers=auth
    )
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "already_uploaded"


async def test_no_bucket_means_try_again_later(
    client: AsyncClient, auth: dict[str, str], services: Services
) -> None:
    services.audio = UnconfiguredAudioStore()
    response = await client.put(f"/recordings/{SERIAL}/1", json=metadata(b"x"), headers=auth)
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "storage_unavailable"
