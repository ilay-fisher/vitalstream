"""Request-scoped token of the end user chatting with the copilot.

Kept in a context variable, never in the LLM prompt, tool arguments, logs or events, so the tools
can call clinical-api *as the user* without the model or any other component ever seeing it.
"""

from contextvars import ContextVar

_user_token: ContextVar[str | None] = ContextVar("user_token", default=None)


def get_user_token() -> str | None:
    """The current request's user token, or ``None`` outside an authenticated chat."""
    return _user_token.get()


def set_user_token(value: str | None) -> None:
    """Bind the user token for the current context."""
    _user_token.set(value)
