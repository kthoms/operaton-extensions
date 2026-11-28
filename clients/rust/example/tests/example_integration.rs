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

//! Deploys the example model and runs the example worker against a real engine.
//! `OPERATON_URL=http://localhost:8080/engine-rest cargo test --features integration`.
#![cfg(feature = "integration")]

use std::time::Duration;

use operaton_external_task_client::{Client, ClientConfig, SubscriptionOptions};
use operaton_fraud_check_example::{fraud_check, TOPIC};
use serde_json::{json, Value};

const BPMN: &str = include_str!("../fraud-check.bpmn");

async fn get(http: &reqwest::Client, url: &str) -> Value {
    http.get(url)
        .send()
        .await
        .expect("GET")
        .json()
        .await
        .expect("JSON")
}

async fn run(business_key: &str, amount: f64, country: &str) -> (String, Value) {
    let url = std::env::var("OPERATON_URL")
        .unwrap_or_else(|_| "http://localhost:8080/engine-rest".to_string());
    let http = reqwest::Client::new();
    tokio::time::timeout(Duration::from_secs(120), async {
        while !matches!(http.get(format!("{url}/engine")).send().await, Ok(r) if r.status().is_success()) {
            tokio::time::sleep(Duration::from_millis(500)).await;
        }
    })
    .await
    .expect("engine ready");
    let form = reqwest::multipart::Form::new()
        .text("deployment-name", "fraud-check")
        .part(
            "fraud-check.bpmn",
            reqwest::multipart::Part::text(BPMN).file_name("fraud-check.bpmn"),
        );
    http.post(format!("{url}/deployment/create"))
        .multipart(form)
        .send()
        .await
        .expect("deploy")
        .error_for_status()
        .expect("deployed");

    let client = Client::new(
        ClientConfig::builder(&url)
            .async_response_timeout(Duration::from_secs(5))
            .build()
            .expect("config"),
    )
    .expect("client");
    client
        .subscribe(
            TOPIC,
            fraud_check,
            SubscriptionOptions::new().business_key(business_key),
        )
        .expect("subscribe");
    client.start().await.expect("start");

    let started: Value = http
        .post(format!("{url}/process-definition/key/fraud-check/start"))
        .json(&json!({"businessKey": business_key, "variables": {
            "amount": {"value": amount, "type": "Double"},
            "country": {"value": country, "type": "String"}}}))
        .send()
        .await
        .expect("start")
        .json()
        .await
        .expect("JSON");
    let instance = started["id"].as_str().expect("id").to_string();
    let end = tokio::time::timeout(Duration::from_secs(60), async {
        loop {
            let ended = get(&http, &format!(
                "{url}/history/activity-instance?processInstanceId={instance}&activityType=noneEndEvent&finished=true")).await;
            if let Some(id) = ended[0]["activityId"].as_str() {
                return id.to_string();
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    })
    .await
    .expect("process ended");
    client.stop().await.expect("stop");
    let variables = get(
        &http,
        &format!("{url}/history/variable-instance?processInstanceId={instance}"),
    )
    .await;
    (end, variables)
}

#[tokio::test]
async fn a_small_transaction_is_approved() {
    let (end, variables) = run("TX-small", 120.5, "DE").await;
    assert_eq!(end, "transactionApproved");
    let decision = variables
        .as_array()
        .and_then(|a| a.iter().find(|v| v["name"] == "decision"))
        .expect("decision variable");
    assert_eq!(decision["value"], "approved");
}

#[tokio::test]
async fn a_risky_transaction_is_blocked() {
    let (end, _) = run("TX-risky", 25000.0, "KP").await;
    assert_eq!(end, "transactionBlocked");
}
