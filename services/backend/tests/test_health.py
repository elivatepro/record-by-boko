from httpx import AsyncClient


async def test_health_reports_database(client: AsyncClient) -> None:
    response = await client.get("/health")
    assert response.status_code == 200
    assert response.json()["database"] == "ok"


def test_deployed_api_starts_without_google_or_plaud_settings() -> None:
    from roudio.api.app import build_services
    from roudio.config import Settings
    from roudio.plaud import UnconfiguredPlaudAuth

    # _env_file=None: not the developer's .env, which may hold real keys.
    settings = Settings(
        _env_file=None, environment="production", session_secret="a-real-secret-" + "x" * 40
    )
    settings.check_for_deploy()
    assert isinstance(build_services(settings).plaud, UnconfiguredPlaudAuth)


def test_deployed_api_refuses_the_dev_session_secret() -> None:
    import pytest

    from roudio.config import Settings

    with pytest.raises(RuntimeError):
        Settings(_env_file=None, environment="production").check_for_deploy()
