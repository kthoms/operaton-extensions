// Copyright 2026 the Operaton Extensions contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! The client: subscriptions, the fetch-and-lock loop and graceful shutdown.

use std::future::Future;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError, Weak};
use std::time::Duration;

use serde_json::{json, Value};
use tokio::sync::{watch, Notify, OwnedSemaphorePermit, Semaphore};
use tokio::task::JoinHandle;
use tracing::{debug, error, info, warn};

use crate::api::Api;
use crate::config::ClientConfig;
use crate::error::{Error, Result, TaskError};
use crate::service::TaskService;
use crate::subscription::{
    millis, Subscription, SubscriptionHandle, SubscriptionOptions, TaskHandler,
};
use crate::task::ExternalTask;

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    // A poisoned lock only means that another thread panicked while holding it; the guarded
    // data (a list of subscriptions, an optional join handle) stays consistent.
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

struct Running {
    shutdown: watch::Sender<bool>,
    poller: JoinHandle<()>,
}

pub(crate) struct Inner {
    config: ClientConfig,
    api: Arc<Api>,
    subscriptions: Mutex<Vec<Subscription>>,
    next_id: AtomicU64,
    wake: Notify,
    permits: Arc<Semaphore>,
    running: Mutex<Option<Running>>,
}

impl Inner {
    pub(crate) fn remove_subscription(&self, id: u64) -> bool {
        let mut subscriptions = lock(&self.subscriptions);
        let before = subscriptions.len();
        subscriptions.retain(|s| s.id != id);
        subscriptions.len() != before
    }

    fn topics(&self) -> Vec<Value> {
        lock(&self.subscriptions)
            .iter()
            .map(|s| s.options.to_topic_json(&s.topic, self.config.lock_duration))
            .collect()
    }

    fn handler_for(&self, topic: &str) -> Option<Arc<dyn TaskHandler>> {
        lock(&self.subscriptions)
            .iter()
            .find(|s| s.topic == topic)
            .map(|s| Arc::clone(&s.handler))
    }
}

/// An external task client. Cheap to clone; all clones are the same client.
///
/// Subscribe handlers with [`Client::subscribe`], then [`Client::start`] the fetch-and-lock loop
/// and [`Client::stop`] it for a graceful shutdown (or [`Client::run_until`]).
#[derive(Clone)]
pub struct Client {
    inner: Arc<Inner>,
}

impl std::fmt::Debug for Client {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Client")
            .field("base_url", &self.inner.config.base_url)
            .field("worker_id", &self.inner.config.worker_id)
            .finish()
    }
}

impl Client {
    /// Creates a client; nothing is fetched until [`Client::start`].
    pub fn new(config: ClientConfig) -> Result<Self> {
        let api = Arc::new(Api::new(&config)?);
        let permits = Arc::new(Semaphore::new(config.max_tasks));
        Ok(Client {
            inner: Arc::new(Inner {
                config,
                api,
                subscriptions: Mutex::new(Vec::new()),
                next_id: AtomicU64::new(1),
                wake: Notify::new(),
                permits,
                running: Mutex::new(None),
            }),
        })
    }

    /// The configuration of this client.
    pub fn config(&self) -> &ClientConfig {
        &self.inner.config
    }

    /// A task service for a task this client fetched, for settling it outside of its handler,
    /// for example after handing the work to another system.
    pub fn task_service(&self, task: &ExternalTask) -> TaskService {
        TaskService::new(
            Arc::clone(&self.inner.api),
            &self.inner.config.worker_id,
            task,
        )
    }

    /// Subscribes `handler` to `topic`. Each topic can be subscribed once. May be called before
    /// or after [`Client::start`].
    pub fn subscribe<H: TaskHandler>(
        &self,
        topic: impl Into<String>,
        handler: H,
        options: SubscriptionOptions,
    ) -> Result<SubscriptionHandle> {
        let topic = topic.into();
        if topic.trim().is_empty() {
            return Err(Error::Config("topic must not be empty".into()));
        }
        if let Some(lock_duration) = options.lock_duration {
            if lock_duration.is_zero() {
                return Err(Error::Config(
                    "the lock duration of a subscription must be greater than 0".into(),
                ));
            }
        }
        let id = self.inner.next_id.fetch_add(1, Ordering::Relaxed);
        {
            let mut subscriptions = lock(&self.inner.subscriptions);
            if subscriptions.iter().any(|s| s.topic == topic) {
                return Err(Error::Config(format!(
                    "topic '{topic}' is already subscribed"
                )));
            }
            subscriptions.push(Subscription {
                id,
                topic: topic.clone(),
                options,
                handler: Arc::new(handler),
            });
        }
        self.inner.wake.notify_one();
        info!(topic = %topic, "subscribed");
        Ok(SubscriptionHandle {
            id,
            topic,
            client: Arc::downgrade(&self.inner),
        })
    }

    /// Starts the fetch-and-lock loop in the background.
    pub async fn start(&self) -> Result<()> {
        let mut running = lock(&self.inner.running);
        if running.is_some() {
            return Err(Error::State("the client is already started".into()));
        }
        let (shutdown, receiver) = watch::channel(false);
        let poller = tokio::spawn(poll_loop(Arc::clone(&self.inner), receiver));
        *running = Some(Running { shutdown, poller });
        info!(worker_id = %self.inner.config.worker_id, "client started");
        Ok(())
    }

    /// Stops fetching and waits until the running handlers have finished and reported their
    /// tasks, at most the configured shutdown timeout.
    ///
    /// A fetch request in flight is cancelled; tasks the engine locked for it become available
    /// again when their lock expires.
    pub async fn stop(&self) -> Result<()> {
        let running = lock(&self.inner.running).take();
        let Some(running) = running else {
            return Err(Error::State("the client is not started".into()));
        };
        // The loop may already be gone; then there is nobody to signal.
        let _ = running.shutdown.send(true);
        if let Err(e) = running.poller.await {
            error!(error = %e, "the polling loop ended abnormally");
        }
        let all = u32::try_from(self.inner.config.max_tasks)
            .map_err(|_| Error::Config("max_tasks is too large".into()))?;
        let limit = self.inner.config.shutdown_timeout;
        match tokio::time::timeout(limit, self.inner.permits.acquire_many(all)).await {
            Ok(_) => {
                info!("client stopped");
                Ok(())
            }
            Err(_) => Err(Error::ShutdownTimeout(limit)),
        }
    }

    /// Starts the client, waits for `signal` (for example `tokio::signal::ctrl_c()`) and stops it
    /// gracefully.
    pub async fn run_until<F: Future>(&self, signal: F) -> Result<()> {
        self.start().await?;
        signal.await;
        self.stop().await
    }
}

async fn wait_shutdown(shutdown: &mut watch::Receiver<bool>) {
    loop {
        if *shutdown.borrow_and_update() {
            return;
        }
        if shutdown.changed().await.is_err() {
            return;
        }
    }
}

async fn pause(duration: Duration, shutdown: &mut watch::Receiver<bool>) {
    tokio::select! {
        _ = tokio::time::sleep(duration) => {}
        _ = wait_shutdown(shutdown) => {}
    }
}

async fn poll_loop(inner: Arc<Inner>, mut shutdown: watch::Receiver<bool>) {
    let config = &inner.config;
    let mut error_delay: Option<Duration> = None;
    loop {
        if *shutdown.borrow() {
            break;
        }
        let topics = inner.topics();
        if topics.is_empty() {
            tokio::select! {
                _ = inner.wake.notified() => {}
                _ = pause(Duration::from_secs(1), &mut shutdown) => {}
            }
            continue;
        }

        // One permit per task that may run; free permits bound the size of the fetch.
        let first = tokio::select! {
            permit = Arc::clone(&inner.permits).acquire_owned() => permit,
            _ = wait_shutdown(&mut shutdown) => break,
        };
        let mut permits: Vec<OwnedSemaphorePermit> = match first {
            Ok(permit) => vec![permit],
            Err(_) => break,
        };
        while permits.len() < config.max_tasks {
            match Arc::clone(&inner.permits).try_acquire_owned() {
                Ok(permit) => permits.push(permit),
                Err(_) => break,
            }
        }

        let mut body = json!({
            "workerId": config.worker_id,
            "maxTasks": permits.len(),
            "usePriority": config.use_priority,
            "topics": topics,
        });
        if !config.async_response_timeout.is_zero() {
            body["asyncResponseTimeout"] = json!(millis(config.async_response_timeout));
        }

        let fetched = tokio::select! {
            result = inner.api.fetch_and_lock(&body) => result,
            _ = wait_shutdown(&mut shutdown) => break,
        };
        match fetched {
            Ok(tasks) => {
                error_delay = None;
                let empty = tasks.is_empty();
                debug!(count = tasks.len(), "fetched tasks");
                for raw in tasks {
                    dispatch(&inner, &raw, permits.pop());
                }
                drop(permits);
                if empty && config.async_response_timeout.is_zero() {
                    pause(config.backoff.initial, &mut shutdown).await;
                }
            }
            Err(e) => {
                drop(permits);
                let delay = config.backoff.next(error_delay);
                error_delay = Some(delay);
                warn!(error = %e, retry_in = ?delay, "fetch and lock failed");
                pause(delay, &mut shutdown).await;
            }
        }
    }
    debug!("polling loop ended");
}

/// Starts the handler for one fetched task, or settles the task if it cannot be handled; a
/// locked task is never dropped silently.
fn dispatch(inner: &Arc<Inner>, raw: &Value, permit: Option<OwnedSemaphorePermit>) {
    let task = match ExternalTask::from_json(raw) {
        Ok(task) => task,
        Err(e) => {
            let id = raw.get("id").and_then(Value::as_str).map(str::to_string);
            error!(task = ?id, error = %e, "cannot decode a fetched task");
            if let Some(id) = id {
                let inner = Arc::clone(inner);
                tokio::spawn(async move {
                    let _permit = permit;
                    report_undecodable(&inner, &id, &e).await;
                });
            }
            return;
        }
    };
    let weak: Weak<Inner> = Arc::downgrade(inner);
    let inner = Arc::clone(inner);
    match inner.handler_for(&task.topic_name) {
        Some(handler) => {
            tokio::spawn(async move {
                let _permit = permit;
                run_task(&inner, handler, task).await;
                drop(weak);
            });
        }
        None => {
            // Unsubscribed between fetch and dispatch: give the task back.
            tokio::spawn(async move {
                let _permit = permit;
                warn!(task = %task.id, topic = %task.topic_name, "no subscription any more, unlocking");
                let service =
                    TaskService::new(Arc::clone(&inner.api), &inner.config.worker_id, &task);
                if let Err(e) = service.unlock().await {
                    error!(task = %task.id, error = %e, "cannot unlock the task");
                }
            });
        }
    }
}

async fn report_undecodable(inner: &Inner, id: &str, cause: &Error) {
    let body = json!({
        "workerId": inner.config.worker_id,
        "errorMessage": format!("the client cannot decode the task: {cause}"),
        "retries": 0,
        "retryTimeout": 0,
    });
    if let Err(e) = inner
        .api
        .post(&format!("/external-task/{id}/failure"), Some(&body))
        .await
    {
        error!(task = %id, error = %e, "cannot report the undecodable task");
    }
}

async fn run_task(inner: &Arc<Inner>, handler: Arc<dyn TaskHandler>, task: ExternalTask) {
    let service = TaskService::new(Arc::clone(&inner.api), &inner.config.worker_id, &task);
    debug!(task = %task.id, topic = %task.topic_name, "handling task");
    // The handler runs in its own task so that a panic is caught and reported as a failure.
    let joined = {
        let task = task.clone();
        let service = service.clone();
        tokio::spawn(async move { handler.handle(task, service).await }).await
    };
    let result = match joined {
        Ok(result) => result,
        Err(e) => Err(TaskError::failure(format!(
            "the handler panicked or was cancelled: {e}"
        ))),
    };
    report(inner, &task, &service, result).await;
}

/// The retries to report for a failure that sets none: one less than the task has, or one less
/// than the configured default for a task that has not failed before.
fn counted_down_retries(task_retries: Option<i32>, default_retries: i32) -> i32 {
    task_retries
        .unwrap_or(default_retries)
        .saturating_sub(1)
        .max(0)
}

async fn report(
    inner: &Inner,
    task: &ExternalTask,
    service: &TaskService,
    result: std::result::Result<crate::Completion, TaskError>,
) {
    if service.is_settled() {
        if let Err(e) = &result {
            warn!(task = %task.id, error = %e, "the handler failed after settling the task");
        }
        return;
    }
    let config = &inner.config;
    let outcome = match result {
        Ok(completion) => {
            service
                .complete_with_local(completion.variables, completion.local_variables)
                .await
        }
        Err(TaskError::Bpmn {
            code,
            message,
            variables,
        }) => {
            debug!(task = %task.id, code = %code, "BPMN error");
            service
                .handle_bpmn_error(&code, message.as_deref(), variables)
                .await
        }
        Err(TaskError::Failure {
            message,
            details,
            retries,
            retry_timeout,
        }) => {
            let retries = retries
                .unwrap_or_else(|| counted_down_retries(task.retries, config.default_retries));
            warn!(task = %task.id, retries, message = %message, "task failed");
            service
                .handle_failure(
                    &message,
                    details.as_deref(),
                    retries,
                    retry_timeout.unwrap_or(config.retry_timeout),
                )
                .await
        }
    };
    if let Err(e) = outcome {
        error!(task = %task.id, error = %e, "cannot report the task result to the engine; the lock will expire");
    }
}

#[cfg(test)]
mod tests {
    use super::counted_down_retries;

    #[test]
    fn retries_count_down() {
        assert_eq!(counted_down_retries(None, 3), 2);
        assert_eq!(counted_down_retries(Some(2), 3), 1);
        assert_eq!(counted_down_retries(Some(1), 3), 0);
        assert_eq!(counted_down_retries(Some(0), 3), 0);
    }
}
