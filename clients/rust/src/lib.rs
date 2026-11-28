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

//! External task client for [Operaton](https://operaton.org).
//!
//! The client fetches and locks external tasks over the engine's REST API with long polling,
//! runs one async handler per task and reports the result: `complete` for an `Ok`,
//! `handleBpmnError` for [`TaskError::Bpmn`], `handleFailure` with counted-down retries for any
//! other error. [`Client::stop`] waits for handlers that are still running.
//!
//! ```no_run
//! use operaton_external_task_client::{
//!     Client, ClientConfig, Completion, ExternalTask, SubscriptionOptions, TaskError, TaskService,
//! };
//!
//! # async fn run() -> operaton_external_task_client::Result<()> {
//! let config = ClientConfig::builder("http://localhost:8080/engine-rest")
//!     .basic_auth("demo", "demo")
//!     .build()?;
//! let client = Client::new(config)?;
//! client.subscribe(
//!     "charge-card",
//!     |task: ExternalTask, _service: TaskService| async move {
//!         let amount = task.variable("amount").and_then(|v| v.as_f64());
//!         match amount {
//!             Some(a) if a > 0.0 => Ok(Completion::new().variable("charged", true)),
//!             _ => Err(TaskError::bpmn("INVALID_AMOUNT", "amount must be positive")),
//!         }
//!     },
//!     SubscriptionOptions::new().variables(["amount"]),
//! )?;
//! client.start().await?;
//! // ... later
//! client.stop().await?;
//! # Ok(())
//! # }
//! ```

#![deny(missing_docs)]
#![deny(clippy::unwrap_used, clippy::expect_used)]
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used))]

mod api;
mod client;
mod config;
mod error;
mod service;
mod subscription;
mod task;
mod variables;

pub use client::Client;
pub use config::{Auth, Backoff, ClientConfig, ClientConfigBuilder};
pub use error::{Error, Result, TaskError};
pub use service::TaskService;
pub use subscription::{
    Completion, HandlerFuture, SubscriptionHandle, SubscriptionOptions, TaskHandler,
};
pub use task::ExternalTask;
pub use variables::{variables_from_json, variables_to_json, Variable, Variables};
