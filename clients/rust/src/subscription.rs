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

//! Topic subscriptions and their options.

use std::collections::BTreeMap;
use std::fmt;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Weak};
use std::time::Duration;

use serde_json::{json, Map, Value};

use crate::client::Inner;
use crate::error::TaskError;
use crate::service::TaskService;
use crate::task::ExternalTask;
use crate::variables::{Variable, Variables};

/// What a successful handler hands back: the variables that complete the task.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Completion {
    /// Process variables to set.
    pub variables: Variables,
    /// Variables local to the task's execution.
    pub local_variables: Variables,
}

impl Completion {
    /// A completion without variables.
    pub fn new() -> Self {
        Completion::default()
    }

    /// Adds a process variable.
    #[must_use]
    pub fn variable(mut self, name: impl Into<String>, value: impl Into<Variable>) -> Self {
        self.variables.insert(name.into(), value.into());
        self
    }

    /// Adds a local variable.
    #[must_use]
    pub fn local_variable(mut self, name: impl Into<String>, value: impl Into<Variable>) -> Self {
        self.local_variables.insert(name.into(), value.into());
        self
    }
}

/// The future a handler returns.
pub type HandlerFuture = Pin<Box<dyn Future<Output = Result<Completion, TaskError>> + Send>>;

/// A task handler. It is implemented for async closures of the shape
/// `Fn(ExternalTask, TaskService) -> impl Future<Output = Result<Completion, TaskError>>`.
///
/// - `Ok(completion)` completes the task, unless the handler already settled it through the
///   [`TaskService`].
/// - `Err(TaskError::Bpmn { .. })` is reported as a BPMN error.
/// - Any other error, and a panic, is reported as a failure with counted-down retries.
pub trait TaskHandler: Send + Sync + 'static {
    /// Handles one task.
    fn handle(&self, task: ExternalTask, service: TaskService) -> HandlerFuture;
}

impl<F, Fut> TaskHandler for F
where
    F: Fn(ExternalTask, TaskService) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = Result<Completion, TaskError>> + Send + 'static,
{
    fn handle(&self, task: ExternalTask, service: TaskService) -> HandlerFuture {
        Box::pin(self(task, service))
    }
}

/// Options of a subscription: what to fetch and which tasks.
#[derive(Debug, Clone, Default)]
pub struct SubscriptionOptions {
    pub(crate) lock_duration: Option<Duration>,
    pub(crate) variables: Option<Vec<String>>,
    pub(crate) local_variables: bool,
    pub(crate) business_key: Option<String>,
    pub(crate) process_definition_id: Option<String>,
    pub(crate) process_definition_ids: Vec<String>,
    pub(crate) process_definition_key: Option<String>,
    pub(crate) process_definition_keys: Vec<String>,
    pub(crate) process_definition_version_tag: Option<String>,
    pub(crate) tenant_ids: Vec<String>,
    pub(crate) without_tenant_id: bool,
    pub(crate) process_variables: BTreeMap<String, Value>,
    pub(crate) include_extension_properties: bool,
}

impl SubscriptionOptions {
    /// Options without any filter: all variables, the client's lock duration.
    pub fn new() -> Self {
        SubscriptionOptions::default()
    }

    /// Lock duration for this topic; defaults to the client's.
    #[must_use]
    pub fn lock_duration(mut self, value: Duration) -> Self {
        self.lock_duration = Some(value);
        self
    }

    /// Only fetch these variables (by default all are fetched).
    #[must_use]
    pub fn variables<I, S>(mut self, names: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.variables = Some(names.into_iter().map(Into::into).collect());
        self
    }

    /// Fetch only the variables local to the task's execution.
    #[must_use]
    pub fn local_variables(mut self, value: bool) -> Self {
        self.local_variables = value;
        self
    }

    /// Only tasks of process instances with this business key.
    #[must_use]
    pub fn business_key(mut self, value: impl Into<String>) -> Self {
        self.business_key = Some(value.into());
        self
    }

    /// Only tasks of this process definition id.
    #[must_use]
    pub fn process_definition_id(mut self, value: impl Into<String>) -> Self {
        self.process_definition_id = Some(value.into());
        self
    }

    /// Only tasks of one of these process definition ids.
    #[must_use]
    pub fn process_definition_ids<I, S>(mut self, values: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.process_definition_ids = values.into_iter().map(Into::into).collect();
        self
    }

    /// Only tasks of this process definition key.
    #[must_use]
    pub fn process_definition_key(mut self, value: impl Into<String>) -> Self {
        self.process_definition_key = Some(value.into());
        self
    }

    /// Only tasks of one of these process definition keys.
    #[must_use]
    pub fn process_definition_keys<I, S>(mut self, values: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.process_definition_keys = values.into_iter().map(Into::into).collect();
        self
    }

    /// Only tasks of process definitions with this version tag.
    #[must_use]
    pub fn process_definition_version_tag(mut self, value: impl Into<String>) -> Self {
        self.process_definition_version_tag = Some(value.into());
        self
    }

    /// Only tasks of this tenant.
    #[must_use]
    pub fn tenant_id(mut self, value: impl Into<String>) -> Self {
        self.tenant_ids = vec![value.into()];
        self
    }

    /// Only tasks of one of these tenants.
    #[must_use]
    pub fn tenant_ids<I, S>(mut self, values: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.tenant_ids = values.into_iter().map(Into::into).collect();
        self
    }

    /// Only tasks that belong to no tenant.
    #[must_use]
    pub fn without_tenant_id(mut self, value: bool) -> Self {
        self.without_tenant_id = value;
        self
    }

    /// Only tasks of process instances whose variable `name` equals `value`.
    #[must_use]
    pub fn process_variable(mut self, name: impl Into<String>, value: impl Into<Value>) -> Self {
        self.process_variables.insert(name.into(), value.into());
        self
    }

    /// Also fetch the task's extension properties from the model.
    #[must_use]
    pub fn include_extension_properties(mut self, value: bool) -> Self {
        self.include_extension_properties = value;
        self
    }

    /// The topic entry of the fetch-and-lock request.
    pub(crate) fn to_topic_json(&self, topic: &str, default_lock: Duration) -> Value {
        let lock = self.lock_duration.unwrap_or(default_lock);
        let mut topic_json = Map::new();
        topic_json.insert("topicName".into(), json!(topic));
        topic_json.insert("lockDuration".into(), json!(millis(lock)));
        if let Some(variables) = &self.variables {
            topic_json.insert("variables".into(), json!(variables));
        }
        if self.local_variables {
            topic_json.insert("localVariables".into(), json!(true));
        }
        if let Some(v) = &self.business_key {
            topic_json.insert("businessKey".into(), json!(v));
        }
        if let Some(v) = &self.process_definition_id {
            topic_json.insert("processDefinitionId".into(), json!(v));
        }
        if !self.process_definition_ids.is_empty() {
            topic_json.insert(
                "processDefinitionIdIn".into(),
                json!(self.process_definition_ids),
            );
        }
        if let Some(v) = &self.process_definition_key {
            topic_json.insert("processDefinitionKey".into(), json!(v));
        }
        if !self.process_definition_keys.is_empty() {
            topic_json.insert(
                "processDefinitionKeyIn".into(),
                json!(self.process_definition_keys),
            );
        }
        if let Some(v) = &self.process_definition_version_tag {
            topic_json.insert("processDefinitionVersionTag".into(), json!(v));
        }
        if !self.tenant_ids.is_empty() {
            topic_json.insert("tenantIdIn".into(), json!(self.tenant_ids));
        }
        if self.without_tenant_id {
            topic_json.insert("withoutTenantId".into(), json!(true));
        }
        if !self.process_variables.is_empty() {
            topic_json.insert("processVariables".into(), json!(self.process_variables));
        }
        if self.include_extension_properties {
            topic_json.insert("includeExtensionProperties".into(), json!(true));
        }
        Value::Object(topic_json)
    }
}

pub(crate) fn millis(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

pub(crate) struct Subscription {
    pub(crate) id: u64,
    pub(crate) topic: String,
    pub(crate) options: SubscriptionOptions,
    pub(crate) handler: Arc<dyn TaskHandler>,
}

/// A handle on a subscription; unsubscribes it.
pub struct SubscriptionHandle {
    pub(crate) id: u64,
    pub(crate) topic: String,
    pub(crate) client: Weak<Inner>,
}

impl SubscriptionHandle {
    /// The subscribed topic.
    pub fn topic(&self) -> &str {
        &self.topic
    }

    /// Stops fetching tasks for the topic. Tasks already fetched are still handled. Returns
    /// whether the subscription was still active.
    pub fn unsubscribe(self) -> bool {
        match self.client.upgrade() {
            Some(inner) => inner.remove_subscription(self.id),
            None => false,
        }
    }
}

impl fmt::Debug for SubscriptionHandle {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SubscriptionHandle")
            .field("topic", &self.topic)
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn topic_json_has_only_set_options() {
        let plain = SubscriptionOptions::new().to_topic_json("t", Duration::from_secs(5));
        assert_eq!(plain, json!({"topicName": "t", "lockDuration": 5000}));

        let filtered = SubscriptionOptions::new()
            .lock_duration(Duration::from_millis(1500))
            .variables(["a", "b"])
            .local_variables(true)
            .business_key("bk")
            .process_definition_keys(["p1", "p2"])
            .tenant_id("acme")
            .process_variable("region", "eu")
            .to_topic_json("t", Duration::from_secs(5));
        assert_eq!(
            filtered,
            json!({
                "topicName": "t", "lockDuration": 1500, "variables": ["a", "b"],
                "localVariables": true, "businessKey": "bk",
                "processDefinitionKeyIn": ["p1", "p2"], "tenantIdIn": ["acme"],
                "processVariables": {"region": "eu"}
            })
        );
    }

    #[test]
    fn completion_collects_variables() {
        let completion = Completion::new()
            .variable("score", 7)
            .local_variable("note", "ok");
        assert_eq!(
            completion.variables.get("score"),
            Some(&Variable::Integer(7))
        );
        assert_eq!(
            completion.local_variables.get("note"),
            Some(&Variable::String("ok".into()))
        );
    }
}
