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

//! Client configuration.

use std::fmt;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::error::{Error, Result};

/// How the client authenticates against the engine's REST API.
#[derive(Clone, Default, PartialEq, Eq)]
pub enum Auth {
    /// No authentication.
    #[default]
    None,
    /// HTTP basic authentication.
    Basic {
        /// User name.
        username: String,
        /// Password.
        password: String,
    },
    /// A bearer token in the `Authorization` header.
    Bearer(String),
}

impl fmt::Debug for Auth {
    // Secrets never appear in logs.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Auth::None => f.write_str("None"),
            Auth::Basic { username, .. } => f
                .debug_struct("Basic")
                .field("username", username)
                .field("password", &"<redacted>")
                .finish(),
            Auth::Bearer(_) => f.write_str("Bearer(<redacted>)"),
        }
    }
}

/// Exponential back-off between polls after an error.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Backoff {
    /// The first delay.
    pub initial: Duration,
    /// The longest delay.
    pub max: Duration,
    /// The factor applied to the delay after each consecutive error; at least 1.
    pub factor: f64,
}

impl Default for Backoff {
    fn default() -> Self {
        Backoff {
            initial: Duration::from_millis(500),
            max: Duration::from_secs(60),
            factor: 2.0,
        }
    }
}

impl Backoff {
    /// The delay that follows `current`; `None` yields the initial delay.
    pub fn next(&self, current: Option<Duration>) -> Duration {
        match current {
            None => self.initial.min(self.max),
            Some(delay) => {
                let secs = (delay.as_secs_f64() * self.factor).min(self.max.as_secs_f64());
                Duration::try_from_secs_f64(secs)
                    .unwrap_or(self.max)
                    .min(self.max)
            }
        }
    }
}

/// Immutable configuration of a [`Client`](crate::Client), built with [`ClientConfig::builder`].
#[derive(Debug, Clone)]
pub struct ClientConfig {
    pub(crate) base_url: String,
    pub(crate) worker_id: String,
    pub(crate) max_tasks: usize,
    pub(crate) async_response_timeout: Duration,
    pub(crate) lock_duration: Duration,
    pub(crate) use_priority: bool,
    pub(crate) auth: Auth,
    pub(crate) backoff: Backoff,
    pub(crate) default_retries: i32,
    pub(crate) retry_timeout: Duration,
    pub(crate) request_timeout: Duration,
    pub(crate) shutdown_timeout: Duration,
}

impl ClientConfig {
    /// Starts a builder for an engine REST API at `base_url`, for example
    /// `http://localhost:8080/engine-rest`.
    pub fn builder(base_url: impl Into<String>) -> ClientConfigBuilder {
        ClientConfigBuilder::new(base_url)
    }

    /// The engine REST API base URL, without a trailing slash.
    pub fn base_url(&self) -> &str {
        &self.base_url
    }

    /// The worker id the tasks are locked with.
    pub fn worker_id(&self) -> &str {
        &self.worker_id
    }

    /// The maximum number of tasks fetched at once, and run concurrently.
    pub fn max_tasks(&self) -> usize {
        self.max_tasks
    }

    /// The long polling timeout of fetch-and-lock; zero disables long polling.
    pub fn async_response_timeout(&self) -> Duration {
        self.async_response_timeout
    }

    /// The default lock duration of subscriptions.
    pub fn lock_duration(&self) -> Duration {
        self.lock_duration
    }

    /// Whether the engine orders fetched tasks by priority.
    pub fn use_priority(&self) -> bool {
        self.use_priority
    }

    /// The authentication.
    pub fn auth(&self) -> &Auth {
        &self.auth
    }

    /// The back-off after errors.
    pub fn backoff(&self) -> Backoff {
        self.backoff
    }
}

/// Builder of a [`ClientConfig`].
#[derive(Debug, Clone)]
pub struct ClientConfigBuilder {
    base_url: String,
    worker_id: Option<String>,
    max_tasks: usize,
    async_response_timeout: Duration,
    lock_duration: Duration,
    use_priority: bool,
    auth: Auth,
    backoff: Backoff,
    default_retries: i32,
    retry_timeout: Duration,
    request_timeout: Duration,
    shutdown_timeout: Duration,
}

impl ClientConfigBuilder {
    fn new(base_url: impl Into<String>) -> Self {
        ClientConfigBuilder {
            base_url: base_url.into(),
            worker_id: None,
            max_tasks: 10,
            async_response_timeout: Duration::from_secs(30),
            lock_duration: Duration::from_secs(30),
            use_priority: true,
            auth: Auth::None,
            backoff: Backoff::default(),
            default_retries: 3,
            retry_timeout: Duration::from_secs(10),
            request_timeout: Duration::from_secs(30),
            shutdown_timeout: Duration::from_secs(60),
        }
    }

    /// The worker id; generated from the process id and the time if not set.
    #[must_use]
    pub fn worker_id(mut self, value: impl Into<String>) -> Self {
        self.worker_id = Some(value.into());
        self
    }

    /// Maximum tasks per fetch and concurrently running handlers; default 10.
    #[must_use]
    pub fn max_tasks(mut self, value: usize) -> Self {
        self.max_tasks = value;
        self
    }

    /// Long polling timeout of fetch-and-lock; default 30 seconds, zero disables long polling.
    #[must_use]
    pub fn async_response_timeout(mut self, value: Duration) -> Self {
        self.async_response_timeout = value;
        self
    }

    /// Default lock duration of subscriptions; default 30 seconds.
    #[must_use]
    pub fn lock_duration(mut self, value: Duration) -> Self {
        self.lock_duration = value;
        self
    }

    /// Fetch tasks ordered by priority; default true.
    #[must_use]
    pub fn use_priority(mut self, value: bool) -> Self {
        self.use_priority = value;
        self
    }

    /// Authentication; default none.
    #[must_use]
    pub fn auth(mut self, value: Auth) -> Self {
        self.auth = value;
        self
    }

    /// HTTP basic authentication.
    #[must_use]
    pub fn basic_auth(mut self, username: impl Into<String>, password: impl Into<String>) -> Self {
        self.auth = Auth::Basic {
            username: username.into(),
            password: password.into(),
        };
        self
    }

    /// Bearer token authentication.
    #[must_use]
    pub fn bearer_auth(mut self, token: impl Into<String>) -> Self {
        self.auth = Auth::Bearer(token.into());
        self
    }

    /// Back-off after errors; default 500 ms doubling up to 60 s.
    #[must_use]
    pub fn backoff(mut self, value: Backoff) -> Self {
        self.backoff = value;
        self
    }

    /// Retries assumed for a task that has none yet when a handler fails; default 3.
    #[must_use]
    pub fn default_retries(mut self, value: i32) -> Self {
        self.default_retries = value;
        self
    }

    /// Delay before a failed task can be fetched again; default 10 seconds.
    #[must_use]
    pub fn retry_timeout(mut self, value: Duration) -> Self {
        self.retry_timeout = value;
        self
    }

    /// Timeout of requests besides the long polling time; default 30 seconds.
    #[must_use]
    pub fn request_timeout(mut self, value: Duration) -> Self {
        self.request_timeout = value;
        self
    }

    /// How long [`Client::stop`](crate::Client::stop) waits for running handlers; default 60
    /// seconds.
    #[must_use]
    pub fn shutdown_timeout(mut self, value: Duration) -> Self {
        self.shutdown_timeout = value;
        self
    }

    /// Validates and builds the configuration.
    pub fn build(self) -> Result<ClientConfig> {
        let base_url = self.base_url.trim().trim_end_matches('/').to_string();
        if !(base_url.starts_with("http://") || base_url.starts_with("https://")) {
            return Err(Error::Config(format!(
                "base_url must start with http:// or https://, got '{base_url}'"
            )));
        }
        if self.max_tasks == 0 || self.max_tasks > 10_000 {
            return Err(Error::Config(
                "max_tasks must be between 1 and 10000".into(),
            ));
        }
        if self.lock_duration.is_zero() {
            return Err(Error::Config("lock_duration must be greater than 0".into()));
        }
        if self.default_retries < 1 {
            return Err(Error::Config("default_retries must be at least 1".into()));
        }
        if !(self.backoff.factor >= 1.0 && self.backoff.factor.is_finite()) {
            return Err(Error::Config("backoff factor must be at least 1".into()));
        }
        if self.backoff.initial.is_zero() || self.backoff.max < self.backoff.initial {
            return Err(Error::Config(
                "backoff initial must be greater than 0 and not above max".into(),
            ));
        }
        let worker_id = match self.worker_id {
            Some(id) if id.trim().is_empty() => {
                return Err(Error::Config("worker_id must not be empty".into()))
            }
            Some(id) => id,
            None => generate_worker_id(),
        };
        Ok(ClientConfig {
            base_url,
            worker_id,
            max_tasks: self.max_tasks,
            async_response_timeout: self.async_response_timeout,
            lock_duration: self.lock_duration,
            use_priority: self.use_priority,
            auth: self.auth,
            backoff: self.backoff,
            default_retries: self.default_retries,
            retry_timeout: self.retry_timeout,
            request_timeout: self.request_timeout,
            shutdown_timeout: self.shutdown_timeout,
        })
    }
}

fn generate_worker_id() -> String {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or_default();
    format!(
        "operaton-rust-{}-{:x}-{}",
        std::process::id(),
        nanos,
        COUNTER.fetch_add(1, Ordering::Relaxed)
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults() {
        let config = ClientConfig::builder("http://localhost:8080/engine-rest/")
            .build()
            .unwrap();
        assert_eq!(config.base_url(), "http://localhost:8080/engine-rest");
        assert_eq!(config.max_tasks(), 10);
        assert_eq!(config.async_response_timeout(), Duration::from_secs(30));
        assert_eq!(config.lock_duration(), Duration::from_secs(30));
        assert!(config.use_priority());
        assert_eq!(config.auth(), &Auth::None);
        assert!(config.worker_id().starts_with("operaton-rust-"));
    }

    #[test]
    fn worker_ids_are_unique() {
        let a = ClientConfig::builder("http://h").build().unwrap();
        let b = ClientConfig::builder("http://h").build().unwrap();
        assert_ne!(a.worker_id(), b.worker_id());
    }

    #[test]
    fn rejects_invalid_values() {
        assert!(ClientConfig::builder("localhost:8080").build().is_err());
        assert!(ClientConfig::builder("http://h")
            .max_tasks(0)
            .build()
            .is_err());
        assert!(ClientConfig::builder("http://h")
            .lock_duration(Duration::ZERO)
            .build()
            .is_err());
        assert!(ClientConfig::builder("http://h")
            .worker_id(" ")
            .build()
            .is_err());
        assert!(ClientConfig::builder("http://h")
            .default_retries(0)
            .build()
            .is_err());
        assert!(ClientConfig::builder("http://h")
            .backoff(Backoff {
                factor: 0.5,
                ..Backoff::default()
            })
            .build()
            .is_err());
    }

    #[test]
    fn backoff_grows_and_is_capped() {
        let backoff = Backoff {
            initial: Duration::from_millis(100),
            max: Duration::from_millis(350),
            factor: 2.0,
        };
        let first = backoff.next(None);
        let second = backoff.next(Some(first));
        let third = backoff.next(Some(second));
        let fourth = backoff.next(Some(third));
        assert_eq!(first, Duration::from_millis(100));
        assert_eq!(second, Duration::from_millis(200));
        assert_eq!(third, Duration::from_millis(350));
        assert_eq!(fourth, Duration::from_millis(350));
    }

    #[test]
    fn debug_hides_secrets() {
        let basic = format!(
            "{:?}",
            Auth::Basic {
                username: "demo".into(),
                password: "s3cret".into()
            }
        );
        let bearer = format!("{:?}", Auth::Bearer("t0ken".into()));
        assert!(!basic.contains("s3cret") && basic.contains("demo"));
        assert!(!bearer.contains("t0ken"));
    }
}
