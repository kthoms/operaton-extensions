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

//! Client behaviour against a mocked engine (no real engine needed).

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use operaton_external_task_client::{
    Backoff, Client, ClientConfig, Completion, Error, ExternalTask, SubscriptionOptions, TaskError,
    TaskService, Variable, Variables,
};
use serde_json::{json, Value};
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, Request, ResponseTemplate};

const FETCH: &str = "/external-task/fetchAndLock";

fn task_json(id: &str, topic: &str, retries: Value) -> Value {
    json!({
        "id": id, "topicName": topic, "workerId": "w", "retries": retries, "priority": 0,
        "processInstanceId": "pi-1", "processDefinitionId": "pd:1:x",
        "processDefinitionKey": "pd", "activityId": "task", "activityInstanceId": "ai-1",
        "executionId": "ex-1", "tenantId": null, "businessKey": "bk-1",
        "variables": {
            "amount": {"value": 250, "type": "Long", "valueInfo": {}},
            "who": {"value": "alice", "type": "String", "valueInfo": {}}
        }
    })
}

/// Serves `tasks` for the first fetch, then empty answers.
async fn engine_with(tasks: Vec<Value>) -> MockServer {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path(FETCH))
        .respond_with(ResponseTemplate::new(200).set_body_json(tasks))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    empty_fetches(&server).await;
    for action in ["complete", "failure", "bpmnError", "extendLock", "unlock"] {
        Mock::given(method("POST"))
            .and(wiremock::matchers::path_regex(format!(
                "^/external-task/[^/]+/{action}$"
            )))
            .respond_with(ResponseTemplate::new(204))
            .mount(&server)
            .await;
    }
    server
}

async fn empty_fetches(server: &MockServer) {
    Mock::given(method("POST"))
        .and(path(FETCH))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!([]))
                .set_delay(Duration::from_millis(50)),
        )
        .mount(server)
        .await;
}

fn config(server: &MockServer) -> operaton_external_task_client::ClientConfigBuilder {
    ClientConfig::builder(format!("{}/", server.uri()))
        .worker_id("test-worker")
        .max_tasks(3)
        .async_response_timeout(Duration::from_secs(1))
        .retry_timeout(Duration::from_millis(2500))
        .shutdown_timeout(Duration::from_secs(10))
        .backoff(Backoff {
            initial: Duration::from_millis(20),
            max: Duration::from_millis(40),
            factor: 2.0,
        })
}

async fn requests_to(server: &MockServer, suffix: &str) -> Vec<Request> {
    server
        .received_requests()
        .await
        .unwrap_or_default()
        .into_iter()
        .filter(|r| r.url.path().ends_with(suffix))
        .collect()
}

/// Polls until a request to `suffix` arrived (bounded by a timeout) and returns its JSON body.
async fn body_of(server: &MockServer, suffix: &str) -> Value {
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            if let Some(request) = requests_to(server, suffix).await.first() {
                return request.body_json::<Value>().unwrap_or(Value::Null);
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap_or_else(|_| panic!("no request to {suffix} within 10 s"))
}

#[tokio::test]
async fn fetches_handles_and_completes() {
    let server = engine_with(vec![task_json("t1", "charge", Value::Null)]).await;
    let client = Client::new(config(&server).basic_auth("demo", "demo").build().unwrap()).unwrap();
    client
        .subscribe(
            "charge",
            |task: ExternalTask, _s: TaskService| async move {
                let amount = task
                    .variable("amount")
                    .and_then(Variable::as_i64)
                    .unwrap_or(0);
                Ok(Completion::new()
                    .variable("doubled", amount * 2)
                    .variable("flag", true)
                    .local_variable("note", "ok"))
            },
            SubscriptionOptions::new()
                .variables(["amount"])
                .lock_duration(Duration::from_secs(7))
                .business_key("bk-1"),
        )
        .unwrap();
    client.start().await.unwrap();

    let complete = body_of(&server, "/external-task/t1/complete").await;
    client.stop().await.unwrap();

    assert_eq!(complete["workerId"], "test-worker");
    assert_eq!(
        complete["variables"]["doubled"],
        json!({"value": 500, "type": "Long"})
    );
    assert_eq!(
        complete["variables"]["flag"],
        json!({"value": true, "type": "Boolean"})
    );
    assert_eq!(
        complete["localVariables"]["note"],
        json!({"value": "ok", "type": "String"})
    );

    let fetch = requests_to(&server, FETCH).await;
    let first: Value = fetch[0].body_json().unwrap();
    assert_eq!(first["workerId"], "test-worker");
    assert_eq!(first["maxTasks"], 3);
    assert_eq!(first["usePriority"], true);
    assert_eq!(first["asyncResponseTimeout"], 1000);
    assert_eq!(
        first["topics"],
        json!([{"topicName": "charge", "lockDuration": 7000, "variables": ["amount"],
                "businessKey": "bk-1"}])
    );
    let auth = fetch[0]
        .headers
        .get("authorization")
        .unwrap()
        .to_str()
        .unwrap();
    assert!(auth.starts_with("Basic "), "{auth}");
}

#[tokio::test]
async fn sends_a_bearer_token() {
    let server = engine_with(vec![]).await;
    let client = Client::new(config(&server).bearer_auth("tok-123").build().unwrap()).unwrap();
    client
        .subscribe(
            "t",
            |_t: ExternalTask, _s: TaskService| async { Ok(Completion::new()) },
            SubscriptionOptions::new(),
        )
        .unwrap();
    client.start().await.unwrap();
    body_of(&server, FETCH).await;
    client.stop().await.unwrap();
    let fetch = requests_to(&server, FETCH).await;
    assert_eq!(
        fetch[0]
            .headers
            .get("authorization")
            .unwrap()
            .to_str()
            .unwrap(),
        "Bearer tok-123"
    );
}

#[tokio::test]
async fn a_failure_counts_retries_down_from_the_default() {
    let server = engine_with(vec![task_json("t1", "t", Value::Null)]).await;
    let client = Client::new(config(&server).build().unwrap()).unwrap();
    client
        .subscribe(
            "t",
            |_t: ExternalTask, _s: TaskService| async {
                Err::<Completion, _>(TaskError::failure("card gateway down").with_details("503"))
            },
            SubscriptionOptions::new(),
        )
        .unwrap();
    client.start().await.unwrap();
    let body = body_of(&server, "/external-task/t1/failure").await;
    client.stop().await.unwrap();
    assert_eq!(body["errorMessage"], "card gateway down");
    assert_eq!(body["errorDetails"], "503");
    assert_eq!(body["retries"], 2);
    assert_eq!(body["retryTimeout"], 2500);
}

#[tokio::test]
async fn a_failure_counts_down_the_tasks_retries_and_ends_at_zero() {
    let server = engine_with(vec![task_json("t1", "t", json!(1))]).await;
    let client = Client::new(config(&server).build().unwrap()).unwrap();
    client
        .subscribe(
            "t",
            |_t: ExternalTask, _s: TaskService| async {
                Err::<Completion, _>(TaskError::failure("boom"))
            },
            SubscriptionOptions::new(),
        )
        .unwrap();
    client.start().await.unwrap();
    let body = body_of(&server, "/external-task/t1/failure").await;
    client.stop().await.unwrap();
    assert_eq!(body["retries"], 0);
}

#[tokio::test]
async fn explicit_retries_and_timeout_win() {
    let server = engine_with(vec![task_json("t1", "t", json!(5))]).await;
    let client = Client::new(config(&server).build().unwrap()).unwrap();
    client
        .subscribe(
            "t",
            |_t: ExternalTask, _s: TaskService| async {
                Err::<Completion, _>(
                    TaskError::failure("later")
                        .with_retries(9)
                        .with_retry_timeout(Duration::from_secs(60)),
                )
            },
            SubscriptionOptions::new(),
        )
        .unwrap();
    client.start().await.unwrap();
    let body = body_of(&server, "/external-task/t1/failure").await;
    client.stop().await.unwrap();
    assert_eq!(body["retries"], 9);
    assert_eq!(body["retryTimeout"], 60000);
}

#[tokio::test]
async fn a_bpmn_error_is_reported_as_such() {
    let server = engine_with(vec![task_json("t1", "t", Value::Null)]).await;
    let client = Client::new(config(&server).build().unwrap()).unwrap();
    client
        .subscribe(
            "t",
            |_t: ExternalTask, _s: TaskService| async {
                let mut vars = Variables::new();
                vars.insert("reason".into(), "limit".into());
                Err::<Completion, _>(TaskError::bpmn_with_variables("LIMIT", "too much", vars))
            },
            SubscriptionOptions::new(),
        )
        .unwrap();
    client.start().await.unwrap();
    let body = body_of(&server, "/external-task/t1/bpmnError").await;
    client.stop().await.unwrap();
    assert_eq!(body["errorCode"], "LIMIT");
    assert_eq!(body["errorMessage"], "too much");
    assert_eq!(
        body["variables"]["reason"],
        json!({"value": "limit", "type": "String"})
    );
    assert!(requests_to(&server, "/external-task/t1/failure")
        .await
        .is_empty());
}

#[tokio::test]
async fn a_panicking_handler_is_reported_as_failure() {
    let server = engine_with(vec![task_json("t1", "t", Value::Null)]).await;
    let client = Client::new(config(&server).build().unwrap()).unwrap();
    client
        .subscribe(
            "t",
            |_t: ExternalTask, _s: TaskService| async {
                if true {
                    panic!("handler bug");
                }
                Ok(Completion::new())
            },
            SubscriptionOptions::new(),
        )
        .unwrap();
    client.start().await.unwrap();
    let body = body_of(&server, "/external-task/t1/failure").await;
    client.stop().await.unwrap();
    assert!(body["errorMessage"].as_str().unwrap().contains("panicked"));
}

#[tokio::test]
async fn a_handler_that_settles_the_task_is_not_reported_again() {
    let server = engine_with(vec![task_json("t1", "t", Value::Null)]).await;
    let client = Client::new(config(&server).build().unwrap()).unwrap();
    client
        .subscribe(
            "t",
            |_t: ExternalTask, service: TaskService| async move {
                service
                    .handle_failure("manual", None, 4, Duration::from_secs(1))
                    .await
                    .map_err(|e| TaskError::failure(e.to_string()))?;
                Ok(Completion::new())
            },
            SubscriptionOptions::new(),
        )
        .unwrap();
    client.start().await.unwrap();
    let body = body_of(&server, "/external-task/t1/failure").await;
    client.stop().await.unwrap();
    assert_eq!(body["retries"], 4);
    assert!(requests_to(&server, "/external-task/t1/complete")
        .await
        .is_empty());
}

#[tokio::test]
async fn an_undecodable_task_is_failed_with_an_incident() {
    let mut broken = task_json("t1", "t", Value::Null);
    broken["variables"] = json!({"x": {"value": "no", "type": "Integer"}});
    let server = engine_with(vec![broken]).await;
    let client = Client::new(config(&server).build().unwrap()).unwrap();
    client
        .subscribe(
            "t",
            |_t: ExternalTask, _s: TaskService| async { Ok(Completion::new()) },
            SubscriptionOptions::new(),
        )
        .unwrap();
    client.start().await.unwrap();
    let body = body_of(&server, "/external-task/t1/failure").await;
    client.stop().await.unwrap();
    assert_eq!(body["retries"], 0);
    assert!(body["errorMessage"]
        .as_str()
        .unwrap()
        .contains("cannot decode"));
}

#[tokio::test]
async fn stop_waits_for_running_handlers() {
    let server = engine_with(vec![task_json("t1", "t", Value::Null)]).await;
    let client = Client::new(config(&server).build().unwrap()).unwrap();
    let started = Arc::new(AtomicBool::new(false));
    let started_in_handler = Arc::clone(&started);
    client
        .subscribe(
            "t",
            move |_t: ExternalTask, _s: TaskService| {
                let started = Arc::clone(&started_in_handler);
                async move {
                    started.store(true, Ordering::SeqCst);
                    tokio::time::sleep(Duration::from_millis(400)).await;
                    Ok(Completion::new().variable("done", true))
                }
            },
            SubscriptionOptions::new(),
        )
        .unwrap();
    client.start().await.unwrap();
    tokio::time::timeout(Duration::from_secs(10), async {
        while !started.load(Ordering::SeqCst) {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();

    client.stop().await.unwrap();

    // stop returned, so the handler finished and its task was completed.
    assert_eq!(
        requests_to(&server, "/external-task/t1/complete")
            .await
            .len(),
        1
    );
}

#[tokio::test]
async fn stop_times_out_with_a_stuck_handler() {
    let server = engine_with(vec![task_json("t1", "t", Value::Null)]).await;
    let client = Client::new(
        config(&server)
            .shutdown_timeout(Duration::from_millis(200))
            .build()
            .unwrap(),
    )
    .unwrap();
    let started = Arc::new(AtomicBool::new(false));
    let flag = Arc::clone(&started);
    client
        .subscribe(
            "t",
            move |_t: ExternalTask, _s: TaskService| {
                let flag = Arc::clone(&flag);
                async move {
                    flag.store(true, Ordering::SeqCst);
                    tokio::time::sleep(Duration::from_secs(30)).await;
                    Ok(Completion::new())
                }
            },
            SubscriptionOptions::new(),
        )
        .unwrap();
    client.start().await.unwrap();
    tokio::time::timeout(Duration::from_secs(10), async {
        while !started.load(Ordering::SeqCst) {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    assert!(matches!(
        client.stop().await,
        Err(Error::ShutdownTimeout(_))
    ));
}

#[tokio::test]
async fn unsubscribe_removes_the_topic_from_fetches() {
    let server = engine_with(vec![]).await;
    let client = Client::new(config(&server).build().unwrap()).unwrap();
    let noop = |_t: ExternalTask, _s: TaskService| async { Ok(Completion::new()) };
    let a = client
        .subscribe("a", noop, SubscriptionOptions::new())
        .unwrap();
    client
        .subscribe("b", noop, SubscriptionOptions::new())
        .unwrap();
    assert!(client
        .subscribe("a", noop, SubscriptionOptions::new())
        .is_err());
    assert_eq!(a.topic(), "a");
    assert!(a.unsubscribe());
    client
        .subscribe("a", noop, SubscriptionOptions::new())
        .unwrap();
    client.start().await.unwrap();
    let body = body_of(&server, FETCH).await;
    client.stop().await.unwrap();
    let topics: Vec<&str> = body["topics"]
        .as_array()
        .unwrap()
        .iter()
        .map(|t| t["topicName"].as_str().unwrap())
        .collect();
    assert_eq!(topics, ["b", "a"]);
}

#[tokio::test]
async fn invalid_subscriptions_and_lifecycle_are_rejected() {
    let server = engine_with(vec![]).await;
    let client = Client::new(config(&server).build().unwrap()).unwrap();
    let noop = |_t: ExternalTask, _s: TaskService| async { Ok(Completion::new()) };
    assert!(matches!(
        client.subscribe(" ", noop, SubscriptionOptions::new()),
        Err(Error::Config(_))
    ));
    assert!(matches!(
        client.subscribe(
            "t",
            noop,
            SubscriptionOptions::new().lock_duration(Duration::ZERO)
        ),
        Err(Error::Config(_))
    ));
    assert!(matches!(client.stop().await, Err(Error::State(_))));
    client.start().await.unwrap();
    assert!(matches!(client.start().await, Err(Error::State(_))));
    client.stop().await.unwrap();
}

#[tokio::test]
async fn fetch_errors_back_off_and_the_loop_recovers() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path(FETCH))
        .respond_with(
            ResponseTemplate::new(500)
                .set_body_json(json!({"type": "RuntimeException", "message": "db down"})),
        )
        .up_to_n_times(3)
        .mount(&server)
        .await;
    empty_fetches(&server).await;
    let client = Client::new(config(&server).build().unwrap()).unwrap();
    client
        .subscribe(
            "t",
            |_t: ExternalTask, _s: TaskService| async { Ok(Completion::new()) },
            SubscriptionOptions::new(),
        )
        .unwrap();
    client.start().await.unwrap();
    tokio::time::timeout(Duration::from_secs(10), async {
        while requests_to(&server, FETCH).await.len() < 5 {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    client.stop().await.unwrap();
}

#[tokio::test]
async fn the_task_service_calls_the_engine() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(204))
        .mount(&server)
        .await;
    let client = Client::new(config(&server).build().unwrap()).unwrap();
    let task = ExternalTask::from_json(&task_json("t9", "t", Value::Null)).unwrap();
    let service = client.task_service(&task);
    assert_eq!(service.task_id(), "t9");

    service.extend_lock(Duration::from_secs(90)).await.unwrap();
    let mut vars = Variables::new();
    vars.insert("n".into(), 3.into());
    service.set_variables(vars.clone()).await.unwrap();
    service.set_local_variables(vars).await.unwrap();
    assert!(!service.is_settled());
    service.unlock().await.unwrap();
    assert!(service.is_settled());

    let extend = body_of(&server, "/external-task/t9/extendLock").await;
    assert_eq!(
        extend,
        json!({"workerId": "test-worker", "newDuration": 90000})
    );
    let set = body_of(&server, "/process-instance/pi-1/variables").await;
    assert_eq!(
        set,
        json!({"modifications": {"n": {"value": 3, "type": "Integer"}}})
    );
    let local = body_of(&server, "/execution/ex-1/localVariables").await;
    assert_eq!(local["modifications"]["n"]["type"], "Integer");
    assert_eq!(
        requests_to(&server, "/external-task/t9/unlock").await.len(),
        1
    );
}

#[tokio::test]
async fn engine_errors_carry_status_type_and_message() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(404).set_body_json(
            json!({"type": "InvalidRequestException", "message": "External task t9 does not exist"}),
        ))
        .mount(&server)
        .await;
    let client = Client::new(config(&server).build().unwrap()).unwrap();
    let task = ExternalTask::from_json(&task_json("t9", "t", Value::Null)).unwrap();
    let error = client.task_service(&task).unlock().await.unwrap_err();
    match error {
        Error::Engine {
            status,
            error_type,
            message,
        } => {
            assert_eq!(status, 404);
            assert_eq!(error_type.as_deref(), Some("InvalidRequestException"));
            assert!(message.contains("does not exist"));
        }
        other => panic!("unexpected {other:?}"),
    }
}
