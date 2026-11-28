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

//! The task service: what a handler can do with its task.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use serde_json::{json, Value};

use crate::api::Api;
use crate::error::Result;
use crate::subscription::millis;
use crate::task::ExternalTask;
use crate::variables::{variables_to_json, Variables};

struct ServiceInner {
    api: Arc<Api>,
    worker_id: String,
    task_id: String,
    process_instance_id: String,
    execution_id: String,
    settled: AtomicBool,
}

/// The operations on one locked task. Cheap to clone; all clones share the task.
///
/// `complete`, `handle_failure` and `handle_bpmn_error` settle the task. When a handler settles the
/// task itself, the client does not report its return value again.
#[derive(Clone)]
pub struct TaskService {
    inner: Arc<ServiceInner>,
}

impl std::fmt::Debug for TaskService {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TaskService")
            .field("task_id", &self.inner.task_id)
            .finish()
    }
}

impl TaskService {
    pub(crate) fn new(api: Arc<Api>, worker_id: &str, task: &ExternalTask) -> Self {
        TaskService {
            inner: Arc::new(ServiceInner {
                api,
                worker_id: worker_id.to_string(),
                task_id: task.id.clone(),
                process_instance_id: task.process_instance_id.clone(),
                execution_id: task.execution_id.clone(),
                settled: AtomicBool::new(false),
            }),
        }
    }

    /// The id of the task this service belongs to.
    pub fn task_id(&self) -> &str {
        &self.inner.task_id
    }

    /// Whether the task was settled (completed, failed or ended with a BPMN error) through this
    /// service.
    pub fn is_settled(&self) -> bool {
        self.inner.settled.load(Ordering::SeqCst)
    }

    fn path(&self, action: &str) -> String {
        format!("/external-task/{}/{}", self.inner.task_id, action)
    }

    /// Completes the task, setting process variables.
    pub async fn complete(&self, variables: Variables) -> Result<()> {
        self.complete_with_local(variables, Variables::new()).await
    }

    /// Completes the task, setting process variables and variables local to its execution.
    pub async fn complete_with_local(
        &self,
        variables: Variables,
        local_variables: Variables,
    ) -> Result<()> {
        let body = json!({
            "workerId": self.inner.worker_id,
            "variables": variables_to_json(&variables),
            "localVariables": variables_to_json(&local_variables),
        });
        self.inner
            .api
            .post(&self.path("complete"), Some(&body))
            .await?;
        self.inner.settled.store(true, Ordering::SeqCst);
        Ok(())
    }

    /// Reports a failure. With `retries` at 0 the engine creates an incident; otherwise the task
    /// can be fetched again after `retry_timeout`.
    pub async fn handle_failure(
        &self,
        message: &str,
        details: Option<&str>,
        retries: i32,
        retry_timeout: Duration,
    ) -> Result<()> {
        let body = json!({
            "workerId": self.inner.worker_id,
            "errorMessage": message,
            "errorDetails": details,
            "retries": retries,
            "retryTimeout": millis(retry_timeout),
        });
        self.inner
            .api
            .post(&self.path("failure"), Some(&body))
            .await?;
        self.inner.settled.store(true, Ordering::SeqCst);
        Ok(())
    }

    /// Reports a BPMN error, caught by an error boundary event with the same code (or without a
    /// code).
    pub async fn handle_bpmn_error(
        &self,
        code: &str,
        message: Option<&str>,
        variables: Variables,
    ) -> Result<()> {
        let body = json!({
            "workerId": self.inner.worker_id,
            "errorCode": code,
            "errorMessage": message,
            "variables": variables_to_json(&variables),
        });
        self.inner
            .api
            .post(&self.path("bpmnError"), Some(&body))
            .await?;
        self.inner.settled.store(true, Ordering::SeqCst);
        Ok(())
    }

    /// Sets the lock to expire `new_duration` from now.
    pub async fn extend_lock(&self, new_duration: Duration) -> Result<()> {
        let body = json!({
            "workerId": self.inner.worker_id,
            "newDuration": millis(new_duration),
        });
        self.inner
            .api
            .post(&self.path("extendLock"), Some(&body))
            .await?;
        Ok(())
    }

    /// Releases the lock so that any worker can fetch the task again.
    pub async fn unlock(&self) -> Result<()> {
        self.inner.api.post(&self.path("unlock"), None).await?;
        self.inner.settled.store(true, Ordering::SeqCst);
        Ok(())
    }

    /// Sets process variables of the task's process instance without completing the task.
    pub async fn set_variables(&self, variables: Variables) -> Result<()> {
        let body = modifications(&variables);
        self.inner
            .api
            .post(
                &format!(
                    "/process-instance/{}/variables",
                    self.inner.process_instance_id
                ),
                Some(&body),
            )
            .await?;
        Ok(())
    }

    /// Sets variables local to the task's execution without completing the task.
    pub async fn set_local_variables(&self, variables: Variables) -> Result<()> {
        let body = modifications(&variables);
        self.inner
            .api
            .post(
                &format!("/execution/{}/localVariables", self.inner.execution_id),
                Some(&body),
            )
            .await?;
        Ok(())
    }
}

fn modifications(variables: &Variables) -> Value {
    json!({ "modifications": variables_to_json(variables) })
}
