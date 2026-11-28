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

//! Deploys `fraud-check.bpmn` and starts two transactions: TX-1001 (small, approved) and TX-1002
//! (large, from a high-risk country, blocked). Same environment as the worker.

use serde_json::{json, Value};

const BPMN: &str = include_str!("../../fraud-check.bpmn");

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let url = std::env::var("OPERATON_URL")
        .unwrap_or_else(|_| "http://localhost:8080/engine-rest".to_string());
    let user = std::env::var("OPERATON_USER").unwrap_or_else(|_| "demo".to_string());
    let password = std::env::var("OPERATON_PASSWORD").unwrap_or_else(|_| "demo".to_string());
    let http = reqwest::Client::new();
    let auth = |request: reqwest::RequestBuilder| {
        if user.is_empty() {
            request
        } else {
            request.basic_auth(&user, Some(&password))
        }
    };

    let form = reqwest::multipart::Form::new()
        .text("deployment-name", "fraud-check")
        .text("enable-duplicate-filtering", "true")
        .part(
            "fraud-check.bpmn",
            reqwest::multipart::Part::text(BPMN).file_name("fraud-check.bpmn"),
        );
    auth(http.post(format!("{url}/deployment/create")))
        .multipart(form)
        .send()
        .await?
        .error_for_status()?;
    println!("deployed fraud-check.bpmn");

    for (business_key, amount, country) in [("TX-1001", 120.5, "DE"), ("TX-1002", 25000.0, "KP")] {
        let body = json!({
            "businessKey": business_key,
            "variables": {
                "amount": {"value": amount, "type": "Double"},
                "country": {"value": country, "type": "String"},
            },
        });
        let started: Value =
            auth(http.post(format!("{url}/process-definition/key/fraud-check/start")))
                .json(&body)
                .send()
                .await?
                .error_for_status()?
                .json()
                .await?;
        println!("started {business_key}: process instance {}", started["id"]);
    }
    Ok(())
}
