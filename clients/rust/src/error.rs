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

//! Error types of the client.

use std::time::Duration;

use crate::variables::Variables;

/// Errors of the client and of calls to the engine.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// The configuration or a subscription is invalid.
    #[error("invalid configuration: {0}")]
    Config(String),
    /// The HTTP request could not be sent or its response not read.
    #[error("request to the engine failed: {0}")]
    Http(#[from] reqwest::Error),
    /// The engine answered with an error status.
    #[error("the engine answered {status}{}: {message}", .error_type.as_deref().map(|t| format!(" ({t})")).unwrap_or_default())]
    Engine {
        /// HTTP status code.
        status: u16,
        /// The `type` of the engine's error document, if it sent one.
        error_type: Option<String>,
        /// The `message` of the engine's error document, or the raw body.
        message: String,
    },
    /// A response or a variable could not be decoded.
    #[error("cannot decode {what}: {reason}")]
    Decode {
        /// What was decoded.
        what: String,
        /// Why it failed.
        reason: String,
    },
    /// The operation is not allowed in the client's current state.
    #[error("invalid state: {0}")]
    State(String),
    /// Handlers were still running when the shutdown timeout elapsed.
    #[error("shutdown timed out after {0:?} with handlers still running")]
    ShutdownTimeout(Duration),
}

impl Error {
    pub(crate) fn decode(what: impl Into<String>, reason: impl ToString) -> Self {
        Error::Decode {
            what: what.into(),
            reason: reason.to_string(),
        }
    }
}

/// Result alias of the crate.
pub type Result<T> = std::result::Result<T, Error>;

/// The failure of a task handler. It decides how the client reports the task to the engine.
///
/// - [`TaskError::Bpmn`] is reported with `handleBpmnError`, so a boundary error event of the
///   model can catch it.
/// - [`TaskError::Failure`] is reported with `handleFailure`. Without explicit retries the
///   client counts down from the task's retries (or the configured default retries) and an
///   incident is created at zero.
#[derive(Debug, Clone, PartialEq)]
pub enum TaskError {
    /// A business error, caught by an error boundary event with a matching code.
    Bpmn {
        /// The error code of the BPMN error.
        code: String,
        /// Optional error message.
        message: Option<String>,
        /// Variables handed to the process with the error.
        variables: Variables,
    },
    /// A technical failure of the handler.
    Failure {
        /// The error message stored on the task.
        message: String,
        /// Optional details, usually a backtrace or error chain.
        details: Option<String>,
        /// Remaining retries; `None` counts down automatically.
        retries: Option<i32>,
        /// Delay before the task can be fetched again; `None` uses the configured retry timeout.
        retry_timeout: Option<Duration>,
    },
}

impl TaskError {
    /// A BPMN error with the given error code.
    pub fn bpmn(code: impl Into<String>, message: impl Into<String>) -> Self {
        TaskError::Bpmn {
            code: code.into(),
            message: Some(message.into()),
            variables: Variables::new(),
        }
    }

    /// A BPMN error with the given error code and variables.
    pub fn bpmn_with_variables(
        code: impl Into<String>,
        message: impl Into<String>,
        variables: Variables,
    ) -> Self {
        TaskError::Bpmn {
            code: code.into(),
            message: Some(message.into()),
            variables,
        }
    }

    /// A technical failure with automatic retry counting.
    pub fn failure(message: impl Into<String>) -> Self {
        TaskError::Failure {
            message: message.into(),
            details: None,
            retries: None,
            retry_timeout: None,
        }
    }

    /// A technical failure built from an error: its message, and its source chain as details.
    pub fn from_error(error: &(dyn std::error::Error + 'static)) -> Self {
        let mut details = String::new();
        let mut source = error.source();
        while let Some(cause) = source {
            if !details.is_empty() {
                details.push('\n');
            }
            details.push_str("caused by: ");
            details.push_str(&cause.to_string());
            source = cause.source();
        }
        TaskError::Failure {
            message: error.to_string(),
            details: if details.is_empty() {
                None
            } else {
                Some(details)
            },
            retries: None,
            retry_timeout: None,
        }
    }

    /// Sets the details of a failure; other variants are returned unchanged.
    #[must_use]
    pub fn with_details(mut self, value: impl Into<String>) -> Self {
        if let TaskError::Failure { details, .. } = &mut self {
            *details = Some(value.into());
        }
        self
    }

    /// Sets the remaining retries of a failure; other variants are returned unchanged.
    #[must_use]
    pub fn with_retries(mut self, value: i32) -> Self {
        if let TaskError::Failure { retries, .. } = &mut self {
            *retries = Some(value);
        }
        self
    }

    /// Sets the retry timeout of a failure; other variants are returned unchanged.
    #[must_use]
    pub fn with_retry_timeout(mut self, value: Duration) -> Self {
        if let TaskError::Failure { retry_timeout, .. } = &mut self {
            *retry_timeout = Some(value);
        }
        self
    }
}

impl std::fmt::Display for TaskError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TaskError::Bpmn { code, message, .. } => match message {
                Some(m) => write!(f, "BPMN error {code}: {m}"),
                None => write!(f, "BPMN error {code}"),
            },
            TaskError::Failure { message, .. } => write!(f, "task failure: {message}"),
        }
    }
}

impl std::error::Error for TaskError {}
