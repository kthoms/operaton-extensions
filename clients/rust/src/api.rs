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

//! The REST calls of the external task API.

use serde_json::Value;

use crate::config::{Auth, ClientConfig};
use crate::error::{Error, Result};

pub(crate) struct Api {
    http: reqwest::Client,
    base_url: String,
    auth: Auth,
}

impl Api {
    pub(crate) fn new(config: &ClientConfig) -> Result<Self> {
        let http = reqwest::Client::builder()
            .timeout(config.async_response_timeout + config.request_timeout)
            .user_agent(concat!(
                "operaton-external-task-client-rust/",
                env!("CARGO_PKG_VERSION")
            ))
            .build()?;
        Ok(Api {
            http,
            base_url: config.base_url.clone(),
            auth: config.auth.clone(),
        })
    }

    /// POSTs a JSON body (or none) and returns the JSON response, `Null` for an empty one.
    pub(crate) async fn post(&self, path: &str, body: Option<&Value>) -> Result<Value> {
        let mut request = self.http.post(format!("{}{}", self.base_url, path));
        request = match &self.auth {
            Auth::None => request,
            Auth::Basic { username, password } => request.basic_auth(username, Some(password)),
            Auth::Bearer(token) => request.bearer_auth(token),
        };
        request = match body {
            Some(body) => request.json(body),
            None => request,
        };
        let response = request.send().await?;
        let status = response.status();
        let text = response.text().await?;
        if !status.is_success() {
            return Err(engine_error(status.as_u16(), &text));
        }
        if text.trim().is_empty() {
            return Ok(Value::Null);
        }
        serde_json::from_str(&text).map_err(|e| Error::decode(format!("response of {path}"), e))
    }

    pub(crate) async fn fetch_and_lock(&self, body: &Value) -> Result<Vec<Value>> {
        match self.post("/external-task/fetchAndLock", Some(body)).await? {
            Value::Array(tasks) => Ok(tasks),
            other => Err(Error::decode(
                "fetchAndLock response",
                format!("expected an array, got {other}"),
            )),
        }
    }
}

fn engine_error(status: u16, body: &str) -> Error {
    match serde_json::from_str::<Value>(body) {
        Ok(doc) if doc.is_object() => Error::Engine {
            status,
            error_type: doc.get("type").and_then(Value::as_str).map(str::to_string),
            message: doc
                .get("message")
                .and_then(Value::as_str)
                .map(str::to_string)
                .unwrap_or_else(|| body.to_string()),
        },
        _ => Error::Engine {
            status,
            error_type: None,
            message: body.to_string(),
        },
    }
}
