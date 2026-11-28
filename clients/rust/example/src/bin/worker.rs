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

//! Runs the fraud-check worker until Ctrl-C.
//!
//! Environment: `OPERATON_URL` (default `http://localhost:8080/engine-rest`), `OPERATON_USER` and
//! `OPERATON_PASSWORD` for a protected REST API (default `demo` / `demo`; set `OPERATON_USER` to an
//! empty string for none), `RUST_LOG`.

use std::time::Duration;

use operaton_external_task_client::{Client, ClientConfig, SubscriptionOptions};
use operaton_fraud_check_example::{fraud_check, TOPIC};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let url = std::env::var("OPERATON_URL")
        .unwrap_or_else(|_| "http://localhost:8080/engine-rest".to_string());
    let user = std::env::var("OPERATON_USER").unwrap_or_else(|_| "demo".to_string());
    let password = std::env::var("OPERATON_PASSWORD").unwrap_or_else(|_| "demo".to_string());

    let mut config = ClientConfig::builder(url)
        .worker_id("fraud-check-worker")
        .max_tasks(5)
        .async_response_timeout(Duration::from_secs(20));
    if !user.is_empty() {
        config = config.basic_auth(user, password);
    }
    let client = Client::new(config.build()?)?;

    client.subscribe(
        TOPIC,
        fraud_check,
        SubscriptionOptions::new()
            .variables(["amount", "country"])
            .lock_duration(Duration::from_secs(30))
            .process_definition_key("fraud-check"),
    )?;

    tracing::info!("worker started, press Ctrl-C to stop");
    client
        .run_until(async {
            if let Err(e) = tokio::signal::ctrl_c().await {
                tracing::error!(error = %e, "cannot listen for Ctrl-C, stopping");
            }
        })
        .await?;
    tracing::info!("worker stopped");
    Ok(())
}
