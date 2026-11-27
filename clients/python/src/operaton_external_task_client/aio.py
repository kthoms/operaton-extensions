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
#
# Ported from https://github.com/camunda-community-hub/
# camunda-external-task-client-python3 (Apache-2.0); the original files carry no header,
# copyright the camunda-community-hub contributors. Modernised for Operaton.

"""The asyncio external task client."""

from __future__ import annotations

import asyncio
import contextlib
import inspect
import logging
import signal
from collections.abc import Awaitable, Callable, Mapping, Sequence
from types import TracebackType
from typing import Any

import httpx

from . import _outcome
from ._api import AsyncEngineApi
from .config import ClientConfig, Duration, seconds
from .errors import BpmnError, ConfigurationError, OperatonError
from .service import AsyncTaskService
from .subscription import Subscription
from .task import ExternalTask

log = logging.getLogger("operaton_external_task_client")

AsyncHandler = Callable[
    [ExternalTask, AsyncTaskService],
    Awaitable[Mapping[str, Any] | None] | Mapping[str, Any] | None,
]
"""``handler(task, service)`` as a coroutine function (preferred) or a plain function; a plain
function runs in a worker thread so that it cannot block the event loop. Return ``None`` or a
mapping of variables to complete the task, use ``await service...`` for anything else, or raise."""


class AsyncExternalTaskClient:
    """asyncio counterpart of :class:`ExternalTaskClient` (same configuration and semantics).

    ::

        client = AsyncExternalTaskClient(config)
        client.subscribe("process-invoice", handler)   # at least one, before start
        async with client:                             # starts, stops on exit
            await asyncio.Event().wait()

    or ``await client.run_forever()`` (stops on SIGINT/SIGTERM). ``stop()`` cancels the
    in-flight poll and waits for running handlers.
    """

    def __init__(
        self, config: ClientConfig | None = None, *, http_client: httpx.AsyncClient | None = None
    ) -> None:
        self._config = config or ClientConfig()
        self._api = AsyncEngineApi(self._config, http_client)
        self._subscriptions: list[Subscription] = []
        self._running: set[asyncio.Task[None]] = set()
        self._wake = asyncio.Event()
        self._stopping = asyncio.Event()
        self._started = False
        self._poller: asyncio.Task[None] | None = None

    @property
    def config(self) -> ClientConfig:
        return self._config

    @property
    def is_running(self) -> bool:
        return self._started and not self._stopping.is_set()

    @property
    def subscriptions(self) -> list[Subscription]:
        return list(self._subscriptions)

    def subscribe(
        self,
        topic: str,
        handler: AsyncHandler,
        *,
        lock_duration: Duration | None = None,
        variables: Sequence[str] | None = None,
        local_variables: bool = False,
        business_key: str | None = None,
        process_definition_id: str | None = None,
        process_definition_ids: Sequence[str] = (),
        process_definition_key: str | None = None,
        process_definition_keys: Sequence[str] = (),
        process_definition_version_tag: str | None = None,
        tenant_id: str | None = None,
        tenant_ids: Sequence[str] = (),
        without_tenant_id: bool = False,
        process_variables: Mapping[str, Any] | None = None,
        include_extension_properties: bool | None = None,
    ) -> Subscription:
        """Same parameters as :meth:`ExternalTaskClient.subscribe`."""
        sub = Subscription(
            topic=topic,
            handler=handler,
            lock_duration=lock_duration,
            variables=variables,
            local_variables=local_variables,
            business_key=business_key,
            process_definition_id=process_definition_id,
            process_definition_ids=process_definition_ids,
            process_definition_key=process_definition_key,
            process_definition_keys=process_definition_keys,
            process_definition_version_tag=process_definition_version_tag,
            tenant_id=tenant_id,
            tenant_ids=tenant_ids,
            without_tenant_id=without_tenant_id,
            process_variables=process_variables or {},
            include_extension_properties=include_extension_properties,
            _unsubscribe=self._remove,
        )
        if sub.lock_duration is not None:
            seconds(sub.lock_duration, "lock_duration")
        if any(s.topic == topic for s in self._subscriptions):
            raise ConfigurationError(f"topic {topic!r} is already subscribed")
        self._subscriptions.append(sub)
        self._wake.set()
        return sub

    def topic(self, name: str, **options: Any) -> Callable[[AsyncHandler], AsyncHandler]:
        """Decorator form of :meth:`subscribe`."""

        def register(handler: AsyncHandler) -> AsyncHandler:
            self.subscribe(name, handler, **options)
            return handler

        return register

    def _remove(self, sub: Subscription) -> None:
        if sub in self._subscriptions:
            self._subscriptions.remove(sub)

    # --- lifecycle -----------------------------------------------------------------------

    async def start(self) -> None:
        """Start polling as a background task. A client is started once."""
        if self._started:
            raise OperatonError("the client was already started; create a new one")
        if not self._subscriptions:
            raise ConfigurationError("no subscription: call subscribe() before start()")
        self._started = True
        self._poller = asyncio.create_task(self._poll_loop(), name="operaton-poller")
        log.info(
            "worker %s started for topics %s",
            self._config.worker_id,
            [s.topic for s in self._subscriptions],
        )

    async def stop(self, timeout: float | None = None) -> bool:
        """Stop fetching (cancelling the poll in flight) and wait for running handlers.

        Returns True if all handlers finished within ``timeout`` seconds (``None``: no limit);
        otherwise the remaining handlers are cancelled and False is returned.
        """
        if not self._started:
            return True
        self._stopping.set()
        self._wake.set()
        if self._poller is not None:
            self._poller.cancel()
            await asyncio.gather(self._poller, return_exceptions=True)
        finished = True
        if self._running:
            _, pending = await asyncio.wait(set(self._running), timeout=timeout)
            if pending:
                finished = False
                log.warning(
                    "worker %s: cancelling %d running handlers",
                    self._config.worker_id,
                    len(pending),
                )
                for t in pending:
                    t.cancel()
                await asyncio.gather(*pending, return_exceptions=True)
        await self._api.aclose()
        log.info("worker %s stopped", self._config.worker_id)
        return finished

    async def run_forever(self, *, install_signal_handlers: bool = True) -> None:
        """Start and block until ``stop()`` is called elsewhere or SIGINT/SIGTERM arrives."""
        loop = asyncio.get_running_loop()
        installed: list[signal.Signals] = []
        if install_signal_handlers:
            for sig in (signal.SIGINT, signal.SIGTERM):
                try:
                    loop.add_signal_handler(sig, self._stopping.set)
                    installed.append(sig)
                except (NotImplementedError, RuntimeError):  # Windows / not the main thread
                    break
        try:
            await self.start()
            await self._stopping.wait()
        finally:
            await self.stop()
            for sig in installed:
                loop.remove_signal_handler(sig)

    async def __aenter__(self) -> AsyncExternalTaskClient:
        await self.start()
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.stop()

    # --- polling -------------------------------------------------------------------------

    async def _poll_loop(self) -> None:
        errors = 0
        concurrency = self._config.concurrency
        while not self._stopping.is_set():
            free = concurrency - len(self._running)
            if not self._subscriptions or free < 1:
                self._wake.clear()
                if not self._subscriptions or len(self._running) >= concurrency:
                    await self._wake.wait()
                continue
            subs = list(self._subscriptions)
            try:
                tasks = await self._api.fetch_and_lock(subs, min(self._config.max_tasks, free))
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                errors += 1
                delay = self._config.back_off.delay(errors)
                log.error(
                    "fetchAndLock failed (%d in a row), retrying in %.1fs: %s", errors, delay, exc
                )
                await self._sleep(delay)
                continue
            errors = 0
            by_topic = {s.topic: s for s in subs}
            for task in tasks:
                sub = by_topic.get(task.topic_name)
                if sub is None:
                    log.warning(
                        "unlocking task %s: no subscription for %s", task.id, task.topic_name
                    )
                    await self._unlock_quietly(task)
                    continue
                runner = asyncio.create_task(self._run(task, sub), name=f"operaton-task-{task.id}")
                self._running.add(runner)
                runner.add_done_callback(self._done)
            if not tasks and seconds(self._config.async_response_timeout, "timeout") == 0:
                await self._sleep(seconds(self._config.idle_interval, "idle_interval"))

    def _done(self, runner: asyncio.Task[None]) -> None:
        self._running.discard(runner)
        self._wake.set()

    async def _sleep(self, delay: float) -> None:
        with contextlib.suppress(asyncio.TimeoutError):
            await asyncio.wait_for(self._stopping.wait(), delay)

    async def _unlock_quietly(self, task: ExternalTask) -> None:
        try:
            await AsyncTaskService(self._api, task, self._config).unlock()
        except Exception:
            log.exception("could not unlock task %s; its lock expires on its own", task.id)

    async def _run(self, task: ExternalTask, sub: Subscription) -> None:
        service = AsyncTaskService(self._api, task, self._config)
        try:
            log.debug("task %s (%s) started", task.id, task.topic_name)
            try:
                if inspect.iscoroutinefunction(sub.handler):
                    result = await sub.handler(task, service)
                else:
                    result = await asyncio.to_thread(sub.handler, task, service)
                    if inspect.isawaitable(result):
                        result = await result
                if not service.is_finished:
                    await service.complete(_outcome.result_variables(result))
            except Exception as exc:
                await self._report(task, service, exc)
        except asyncio.CancelledError:
            log.warning("task %s cancelled; its lock expires on its own", task.id)
            raise
        except BaseException:
            log.exception("task %s: unexpected error", task.id)

    async def _report(self, task: ExternalTask, service: AsyncTaskService, exc: Exception) -> None:
        if service.is_finished:
            log.error("task %s: handler raised after the task was finished", task.id, exc_info=exc)
            return
        outcome = _outcome.classify(exc)
        try:
            if isinstance(outcome, BpmnError):
                log.info("task %s: BPMN error %s", task.id, outcome.code)
                await service.handle_bpmn_error(outcome.code, outcome.message, outcome.variables)
            else:
                log.error("task %s failed: %s", task.id, outcome.message, exc_info=exc)
                await service.handle_failure(
                    outcome.message, outcome.details, outcome.retries, outcome.retry_timeout
                )
        except Exception:
            log.exception(
                "task %s: could not report the outcome to the engine; the lock expires on its own",
                task.id,
            )
