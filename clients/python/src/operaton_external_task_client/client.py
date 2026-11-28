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

"""The threaded external task client."""

from __future__ import annotations

import logging
import signal
import threading
import time
from collections.abc import Callable, Mapping, Sequence
from concurrent.futures import ThreadPoolExecutor
from types import TracebackType
from typing import Any

import httpx

from . import _outcome
from ._api import EngineApi
from .config import ClientConfig, Duration, seconds
from .errors import BpmnError, ConfigurationError, OperatonError
from .service import TaskService
from .subscription import Subscription
from .task import ExternalTask

log = logging.getLogger("operaton_external_task_client")

Handler = Callable[[ExternalTask, TaskService], Mapping[str, Any] | None]
"""``handler(task, service)``: return ``None`` or a mapping of variables to complete the task,
use ``service`` for anything else, or raise (``BpmnError``, ``TaskFailure`` or any exception)."""


class ExternalTaskClient:
    """Fetches and locks external tasks and runs handlers on a thread pool.

    Usage::

        client = ExternalTaskClient(ClientConfig(base_url="http://localhost:8080/engine-rest"))
        client.subscribe("process-invoice", handler)
        client.run_forever()          # or: with client: ...  /  client.start(); ...; client.stop()

    One polling thread issues long-polling ``fetchAndLock`` requests for all subscribed topics
    and never fetches more tasks than there are free handler slots. Handlers run concurrently
    (``handler_concurrency``). Errors while polling back off exponentially. ``stop()`` stops
    fetching, unlocks tasks that arrive during shutdown, and waits for running handlers.
    """

    def __init__(
        self, config: ClientConfig | None = None, *, http_client: httpx.Client | None = None
    ) -> None:
        self._config = config or ClientConfig()
        self._api = EngineApi(self._config, http_client)
        self._subscriptions: list[Subscription] = []
        self._cond = threading.Condition()
        self._inflight = 0
        self._stop = threading.Event()
        self._started = False
        self._poller: threading.Thread | None = None
        self._executor: ThreadPoolExecutor | None = None

    # --- subscriptions -------------------------------------------------------------------

    def subscribe(
        self,
        topic: str,
        handler: Handler,
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
        """Subscribe ``handler`` to ``topic``; returns the :class:`Subscription`.

        ``variables`` limits the fetched variables (``None``: all, ``[]``: none);
        ``process_variables`` filters on process variable values; ``lock_duration`` overrides the
        configured default for this topic. Can be called while running.
        """
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
        with self._cond:
            if any(s.topic == topic for s in self._subscriptions):
                raise ConfigurationError(f"topic {topic!r} is already subscribed")
            self._subscriptions.append(sub)
            self._cond.notify_all()
        return sub

    def topic(self, name: str, **options: Any) -> Callable[[Handler], Handler]:
        """Decorator form of :meth:`subscribe`: ``@client.topic("t", lock_duration=30)``."""

        def register(handler: Handler) -> Handler:
            self.subscribe(name, handler, **options)
            return handler

        return register

    def _remove(self, sub: Subscription) -> None:
        with self._cond:
            if sub in self._subscriptions:
                self._subscriptions.remove(sub)
            self._cond.notify_all()

    @property
    def subscriptions(self) -> list[Subscription]:
        with self._cond:
            return list(self._subscriptions)

    # --- lifecycle -----------------------------------------------------------------------

    @property
    def config(self) -> ClientConfig:
        return self._config

    @property
    def is_running(self) -> bool:
        return self._started and not self._stop.is_set()

    def start(self) -> None:
        """Start polling in a background thread. A client is started once."""
        with self._cond:
            if self._started:
                raise OperatonError("the client was already started; create a new one")
            if not self._subscriptions:
                raise ConfigurationError("no subscription: call subscribe() before start()")
            self._started = True
        self._executor = ThreadPoolExecutor(
            max_workers=self._config.concurrency, thread_name_prefix="operaton-handler"
        )
        self._poller = threading.Thread(target=self._poll_loop, name="operaton-poller", daemon=True)
        self._poller.start()
        log.info(
            "worker %s started for topics %s",
            self._config.worker_id,
            [s.topic for s in self.subscriptions],
        )

    def stop(self, timeout: float | None = None) -> bool:
        """Stop fetching and wait for running handlers.

        The polling thread may be inside a long poll; it ends when that returns (at most
        ``async_response_timeout`` plus the request timeout), tasks it receives meanwhile are
        unlocked. Returns True if everything finished within ``timeout`` seconds (``None``:
        wait as long as needed).
        """
        with self._cond:
            if not self._started:
                return True
        self._stop.set()
        with self._cond:
            self._cond.notify_all()
        deadline = None if timeout is None else _now() + timeout
        poller = self._poller
        if poller is not None and poller is not threading.current_thread():
            poller.join(None if deadline is None else max(deadline - _now(), 0))
        with self._cond:
            idle = self._cond.wait_for(
                lambda: self._inflight == 0,
                None if deadline is None else max(deadline - _now(), 0),
            )
        finished = idle and not (poller is not None and poller.is_alive())
        if self._executor is not None:
            self._executor.shutdown(wait=finished)
        if finished:
            self._api.close()
            log.info("worker %s stopped", self._config.worker_id)
        else:
            log.warning("worker %s: stop timed out, handlers still running", self._config.worker_id)
        return finished

    def run_forever(self, *, install_signal_handlers: bool = True) -> None:
        """Start, block until ``stop()`` is called (or SIGINT/SIGTERM arrives), then stop.

        Signal handlers are installed only in the main thread and restored afterwards.
        """
        previous: dict[signal.Signals, Any] = {}
        if install_signal_handlers and threading.current_thread() is threading.main_thread():
            for sig in (signal.SIGINT, signal.SIGTERM):
                previous[sig] = signal.signal(sig, lambda *_: self._stop.set())
        try:
            self.start()
            while not self._stop.wait(0.2):
                if self._poller is not None and not self._poller.is_alive():
                    break
        finally:
            try:
                self.stop()
            finally:
                for sig, handler in previous.items():
                    signal.signal(sig, handler)

    def __enter__(self) -> ExternalTaskClient:
        self.start()
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.stop()

    # --- polling -------------------------------------------------------------------------

    def _poll_loop(self) -> None:
        errors = 0
        while not self._stop.is_set():
            with self._cond:
                self._cond.wait_for(
                    lambda: (
                        self._stop.is_set()
                        or (self._subscriptions and self._inflight < self._config.concurrency)
                    )
                )
                if self._stop.is_set():
                    break
                subs = list(self._subscriptions)
                free = self._config.concurrency - self._inflight
            try:
                tasks = self._api.fetch_and_lock(subs, min(self._config.max_tasks, free))
            except Exception as exc:
                errors += 1
                delay = self._config.back_off.delay(errors)
                log.error(
                    "fetchAndLock failed (%d in a row), retrying in %.1fs: %s", errors, delay, exc
                )
                self._stop.wait(delay)
                continue
            errors = 0
            self._dispatch(tasks, {s.topic: s for s in subs})
            if not tasks and seconds(self._config.async_response_timeout, "timeout") == 0:
                self._stop.wait(seconds(self._config.idle_interval, "idle_interval"))

    def _dispatch(self, tasks: list[ExternalTask], by_topic: Mapping[str, Subscription]) -> None:
        for task in tasks:
            sub = by_topic.get(task.topic_name)
            if self._stop.is_set() or sub is None:
                reason = "shutting down" if self._stop.is_set() else "no subscription"
                log.warning("unlocking task %s (%s): %s", task.id, task.topic_name, reason)
                self._unlock_quietly(task)
                continue
            with self._cond:
                self._inflight += 1
            assert self._executor is not None
            self._executor.submit(self._run, task, sub)

    def _unlock_quietly(self, task: ExternalTask) -> None:
        try:
            TaskService(self._api, task, self._config).unlock()
        except Exception:
            log.exception("could not unlock task %s; its lock expires on its own", task.id)

    def _run(self, task: ExternalTask, sub: Subscription) -> None:
        service = TaskService(self._api, task, self._config)
        try:
            log.debug("task %s (%s) started", task.id, task.topic_name)
            try:
                result = sub.handler(task, service)
                if not service.is_finished:
                    service.complete(_outcome.result_variables(result))
            except Exception as exc:
                self._report(task, service, exc)
        except BaseException:  # keep the pool thread alive and the slot accounting right
            log.exception("task %s: unexpected error", task.id)
        finally:
            with self._cond:
                self._inflight -= 1
                self._cond.notify_all()

    def _report(self, task: ExternalTask, service: TaskService, exc: Exception) -> None:
        if service.is_finished:
            log.error("task %s: handler raised after the task was finished", task.id, exc_info=exc)
            return
        outcome = _outcome.classify(exc)
        try:
            if isinstance(outcome, BpmnError):
                log.info("task %s: BPMN error %s", task.id, outcome.code)
                service.handle_bpmn_error(outcome.code, outcome.message, outcome.variables)
            else:
                log.error("task %s failed: %s", task.id, outcome.message, exc_info=exc)
                service.handle_failure(
                    outcome.message, outcome.details, outcome.retries, outcome.retry_timeout
                )
        except Exception:
            log.exception(
                "task %s: could not report the outcome to the engine; the lock expires on its own",
                task.id,
            )


def _now() -> float:
    return time.monotonic()
