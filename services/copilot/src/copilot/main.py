"""Process entrypoint: `copilot` console script / `python -m copilot.main`."""

import uvicorn

from copilot.config import get_settings


def run() -> None:
    """Serve the API on 0.0.0.0 using uvicorn (logging is configured by the app)."""
    settings = get_settings()
    uvicorn.run(
        "copilot.app:create_app",
        factory=True,
        host="0.0.0.0",
        port=settings.port,
        log_config=None,
        access_log=False,
    )


if __name__ == "__main__":
    run()
