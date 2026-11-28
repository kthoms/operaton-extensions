# Copyright 2026 the Operaton Extensions contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Authentication for the engine REST API."""

from __future__ import annotations

from collections.abc import Callable, Generator

import httpx

__all__ = ["BasicAuth", "BearerAuth"]


class BasicAuth(httpx.BasicAuth):
    """HTTP basic authentication. The password is never shown by ``repr``."""

    def __init__(self, username: str, password: str) -> None:
        super().__init__(username, password)
        self._username = username

    def __repr__(self) -> str:
        return f"BasicAuth(username={self._username!r}, password='***')"


class BearerAuth(httpx.Auth):
    """Bearer token authentication.

    ``token`` is a string or a callable returning the current token; the callable is invoked for
    every request, so a refreshing token provider works without rebuilding the client.
    """

    def __init__(self, token: str | Callable[[], str]) -> None:
        if isinstance(token, str) and not token:
            raise ValueError("bearer token is empty")
        self._token = token

    def auth_flow(self, request: httpx.Request) -> Generator[httpx.Request, httpx.Response, None]:
        token = self._token() if callable(self._token) else self._token
        request.headers["Authorization"] = f"Bearer {token}"
        yield request

    def __repr__(self) -> str:
        return "BearerAuth(token='***')"
