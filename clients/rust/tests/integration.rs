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

//! End-to-end tests against a real engine: deploy a process through the REST API, start an
//! instance, let the client work its external task and assert the process result.
//!
//! Run with `OPERATON_URL=http://localhost:8080/engine-rest cargo test --features integration`.
#![cfg(feature = "integration")]

use std::time::Duration;

use operaton_external_task_client::{
    Client, ClientConfig, Completion, ExternalTask, SubscriptionOptions, TaskError, TaskService,
    Variable,
};
use serde_json::{json, Value};

const BPMN: &str = include_str!("resources/rust-client-it.bpmn");

fn engine_url() -> String {
    std::env::var("OPERATON_URL").unwrap_or_else(|_| "http://localhost:8080/engine-rest".into())
}

/// A test-local id so that tests run in parallel against one engine without seeing each other's
/// tasks: it is the topic of the instance's external task.
fn unique(prefix: &str) -> String {
    use std::sync::atomic::{AtomicU32, Ordering};
    static N: AtomicU32 = AtomicU32::new(0);
    format!(
        "{prefix}-{}-{}-{}",
        std::process::id(),
        N.fetch_add(1, Ordering::Relaxed),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or_default()
    )
}

struct Engine {
    http: reqwest::Client,
    url: String,
}

impl Engine {
    async fn connect() -> Engine {
        let engine = Engine {
            http: reqwest::Client::new(),
            url: engine_url(),
        };
        // A fresh container needs a moment; poll with a timeout.
        tokio::time::timeout(Duration::from_secs(120), async {
            loop {
                if let Ok(r) = engine
                    .http
                    .get(format!("{}/engine", engine.url))
                    .send()
                    .await
                {
                    if r.status().is_success() {
                        return;
                    }
                }
                tokio::time::sleep(Duration::from_millis(500)).await;
            }
        })
        .await
        .expect("the engine did not become ready within 120 s");
        let form = reqwest::multipart::Form::new()
            .text("deployment-name", "rust-client-it")
            .text("enable-duplicate-filtering", "true")
            .part(
                "rust-client-it.bpmn",
                reqwest::multipart::Part::text(BPMN).file_name("rust-client-it.bpmn"),
            );
        let response = engine
            .http
            .post(format!("{}/deployment/create", engine.url))
            .multipart(form)
            .send()
            .await
            .expect("deployment request");
        assert!(
            response.status().is_success(),
            "deployment: {:?}",
            response.text().await
        );
        engine
    }

    async fn get(&self, path: &str) -> Value {
        let response = self
            .http
            .get(format!("{}{}", self.url, path))
            .send()
            .await
            .expect("GET");
        assert!(response.status().is_success(), "GET {path}");
        response.json().await.expect("JSON")
    }

    async fn start(&self, topic: &str, variables: Value) -> String {
        let mut vars = variables.as_object().cloned().unwrap_or_default();
        vars.insert("topic".into(), json!({"value": topic, "type": "String"}));
        let response = self
            .http
            .post(format!(
                "{}/process-definition/key/rust-client-it/start",
                self.url
            ))
            .json(&json!({"variables": vars}))
            .send()
            .await
            .expect("start request");
        assert!(
            response.status().is_success(),
            "start: {:?}",
            response.text().await
        );
        let body: Value = response.json().await.expect("JSON");
        body["id"].as_str().expect("instance id").to_string()
    }

    /// Polls until the check returns a value (bounded by a timeout).
    async fn until<T, F, Fut>(&self, what: &str, check: F) -> T
    where
        F: Fn() -> Fut,
        Fut: std::future::Future<Output = Option<T>>,
    {
        tokio::time::timeout(Duration::from_secs(60), async {
            loop {
                if let Some(value) = check().await {
                    return value;
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        })
        .await
        .unwrap_or_else(|_| panic!("timed out waiting for {what}"))
    }

    async fn end_activity(&self, instance: &str) -> String {
        self.until("the process instance to end", || async {
            let ended = self
                .get(&format!(
                    "/history/activity-instance?processInstanceId={instance}&activityType=noneEndEvent&finished=true"
                ))
                .await;
            ended
                .as_array()
                .and_then(|a| a.first())
                .and_then(|a| a["activityId"].as_str())
                .map(str::to_string)
        })
        .await
    }

    async fn history_variable(&self, instance: &str, name: &str) -> Value {
        let found = self
            .get(&format!(
                "/history/variable-instance?processInstanceId={instance}&variableName={name}"
            ))
            .await;
        found
            .as_array()
            .and_then(|a| a.first())
            .cloned()
            .unwrap_or_else(|| panic!("no historic variable {name}"))
    }
}

fn client(engine: &Engine) -> Client {
    Client::new(
        ClientConfig::builder(&engine.url)
            .max_tasks(5)
            .async_response_timeout(Duration::from_secs(5))
            .retry_timeout(Duration::from_millis(100))
            .shutdown_timeout(Duration::from_secs(30))
            .build()
            .expect("config"),
    )
    .expect("client")
}

#[tokio::test]
async fn completes_a_task_with_typed_variables() {
    let engine = Engine::connect().await;
    let topic = unique("complete");
    let client = client(&engine);
    client
        .subscribe(
            topic.clone(),
            |task: ExternalTask, _s: TaskService| async move {
                let amount = task
                    .variable("amount")
                    .and_then(Variable::as_i64)
                    .unwrap_or(-1);
                assert_eq!(task.business_key, None);
                Ok(Completion::new()
                    .variable("doubled", amount * 2)
                    .variable("approved", true)
                    .variable("details", Variable::Json(json!({"checked": ["a", "b"]}))))
            },
            SubscriptionOptions::new().variables(["amount"]),
        )
        .expect("subscribe");
    client.start().await.expect("start");

    let instance = engine
        .start(&topic, json!({"amount": {"value": 21, "type": "Long"}}))
        .await;
    assert_eq!(engine.end_activity(&instance).await, "done");
    client.stop().await.expect("stop");

    let doubled = engine.history_variable(&instance, "doubled").await;
    assert_eq!(doubled["value"], 42);
    assert_eq!(doubled["type"], "Long");
    assert_eq!(
        engine.history_variable(&instance, "approved").await["value"],
        true
    );
    let details = engine.history_variable(&instance, "details").await;
    assert_eq!(details["type"], "Json");
}

#[tokio::test]
async fn a_bpmn_error_reaches_the_error_boundary_event() {
    let engine = Engine::connect().await;
    let topic = unique("bpmn-error");
    let client = client(&engine);
    client
        .subscribe(
            topic.clone(),
            |_t: ExternalTask, _s: TaskService| async {
                Err::<Completion, _>(TaskError::bpmn("IT_ERROR", "rejected by the worker"))
            },
            SubscriptionOptions::new(),
        )
        .expect("subscribe");
    client.start().await.expect("start");

    let instance = engine.start(&topic, json!({})).await;
    assert_eq!(engine.end_activity(&instance).await, "rejected");
    client.stop().await.expect("stop");
}

#[tokio::test]
async fn failures_count_retries_down_and_end_in_an_incident() {
    let engine = Engine::connect().await;
    let topic = unique("failure");
    let client = client(&engine);
    let attempts = std::sync::Arc::new(std::sync::atomic::AtomicU32::new(0));
    let counter = std::sync::Arc::clone(&attempts);
    client
        .subscribe(
            topic.clone(),
            move |_t: ExternalTask, _s: TaskService| {
                let counter = std::sync::Arc::clone(&counter);
                async move {
                    counter.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                    Err::<Completion, _>(TaskError::failure("flaky backend").with_details("503"))
                }
            },
            SubscriptionOptions::new(),
        )
        .expect("subscribe");
    client.start().await.expect("start");

    let instance = engine.start(&topic, json!({})).await;
    // default_retries is 3: three attempts, then the retries are 0 and an incident exists.
    let incident = engine
        .until("an incident", || async {
            let incidents = engine
                .get(&format!("/incident?processInstanceId={instance}"))
                .await;
            incidents.as_array().and_then(|a| a.first()).cloned()
        })
        .await;
    client.stop().await.expect("stop");

    assert_eq!(incident["incidentType"], "failedExternalTask");
    assert_eq!(incident["incidentMessage"], "flaky backend");
    assert_eq!(attempts.load(std::sync::atomic::Ordering::SeqCst), 3);
    let tasks = engine
        .get(&format!("/external-task?processInstanceId={instance}"))
        .await;
    assert_eq!(tasks[0]["retries"], 0);
    assert_eq!(tasks[0]["errorMessage"], "flaky backend");
}

#[tokio::test]
async fn filters_select_the_tasks() {
    let engine = Engine::connect().await;
    let topic = unique("filter");
    let client = client(&engine);
    // Only handles instances whose variable "mine" is "yes": the other instance stays untouched.
    client
        .subscribe(
            topic.clone(),
            |_t: ExternalTask, _s: TaskService| async { Ok(Completion::new()) },
            SubscriptionOptions::new()
                .process_definition_key("rust-client-it")
                .process_variable("mine", "yes"),
        )
        .expect("subscribe");
    client.start().await.expect("start");

    let other = engine
        .start(&topic, json!({"mine": {"value": "no", "type": "String"}}))
        .await;
    let mine = engine
        .start(&topic, json!({"mine": {"value": "yes", "type": "String"}}))
        .await;
    assert_eq!(engine.end_activity(&mine).await, "done");
    client.stop().await.expect("stop");

    let tasks = engine
        .get(&format!("/external-task?processInstanceId={other}"))
        .await;
    assert_eq!(
        tasks.as_array().map(Vec::len),
        Some(1),
        "the other instance is untouched"
    );
}
