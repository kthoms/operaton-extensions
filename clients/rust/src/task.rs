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

//! The external task as fetched and locked by the client.

use std::collections::BTreeMap;

use serde::Deserialize;
use serde_json::Value;

use crate::error::{Error, Result};
use crate::variables::{variables_from_json, Variables};

/// A locked external task.
#[derive(Debug, Clone, PartialEq)]
pub struct ExternalTask {
    /// Task id.
    pub id: String,
    /// The topic the task was fetched for.
    pub topic_name: String,
    /// The worker that holds the lock.
    pub worker_id: Option<String>,
    /// Remaining retries; `None` if the task has not failed yet.
    pub retries: Option<i32>,
    /// Priority of the task.
    pub priority: i64,
    /// Id of the process instance.
    pub process_instance_id: String,
    /// Id of the process definition.
    pub process_definition_id: String,
    /// Key of the process definition.
    pub process_definition_key: String,
    /// Version tag of the process definition, if set.
    pub process_definition_version_tag: Option<String>,
    /// Id of the activity (the service task) in the model.
    pub activity_id: String,
    /// Id of the activity instance.
    pub activity_instance_id: String,
    /// Id of the execution.
    pub execution_id: String,
    /// Tenant id, if any.
    pub tenant_id: Option<String>,
    /// Business key of the process instance, if any.
    pub business_key: Option<String>,
    /// Error message of a previous failure, if any.
    pub error_message: Option<String>,
    /// When the lock expires (the engine's timestamp format).
    pub lock_expiration_time: Option<String>,
    /// Extension properties of the task in the model (empty unless requested).
    pub extension_properties: BTreeMap<String, String>,
    /// The fetched variables.
    pub variables: Variables,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawTask {
    id: String,
    topic_name: String,
    #[serde(default)]
    worker_id: Option<String>,
    #[serde(default)]
    retries: Option<i32>,
    #[serde(default)]
    priority: Option<i64>,
    #[serde(default)]
    process_instance_id: Option<String>,
    #[serde(default)]
    process_definition_id: Option<String>,
    #[serde(default)]
    process_definition_key: Option<String>,
    #[serde(default)]
    process_definition_version_tag: Option<String>,
    #[serde(default)]
    activity_id: Option<String>,
    #[serde(default)]
    activity_instance_id: Option<String>,
    #[serde(default)]
    execution_id: Option<String>,
    #[serde(default)]
    tenant_id: Option<String>,
    #[serde(default)]
    business_key: Option<String>,
    #[serde(default)]
    error_message: Option<String>,
    #[serde(default)]
    lock_expiration_time: Option<String>,
    #[serde(default)]
    extension_properties: Option<BTreeMap<String, String>>,
    #[serde(default)]
    variables: Value,
}

impl ExternalTask {
    /// Reads one element of the fetch-and-lock response.
    pub fn from_json(value: &Value) -> Result<Self> {
        let raw = RawTask::deserialize(value).map_err(|e| Error::decode("external task", e))?;
        let id = raw.id;
        let variables = variables_from_json(&raw.variables)
            .map_err(|e| Error::decode(format!("variables of task {id}"), e))?;
        Ok(ExternalTask {
            id,
            topic_name: raw.topic_name,
            worker_id: raw.worker_id,
            retries: raw.retries,
            priority: raw.priority.unwrap_or_default(),
            process_instance_id: raw.process_instance_id.unwrap_or_default(),
            process_definition_id: raw.process_definition_id.unwrap_or_default(),
            process_definition_key: raw.process_definition_key.unwrap_or_default(),
            process_definition_version_tag: raw.process_definition_version_tag,
            activity_id: raw.activity_id.unwrap_or_default(),
            activity_instance_id: raw.activity_instance_id.unwrap_or_default(),
            execution_id: raw.execution_id.unwrap_or_default(),
            tenant_id: raw.tenant_id,
            business_key: raw.business_key,
            error_message: raw.error_message,
            lock_expiration_time: raw.lock_expiration_time,
            extension_properties: raw.extension_properties.unwrap_or_default(),
            variables,
        })
    }

    /// The variable `name`, if it was fetched.
    pub fn variable(&self, name: &str) -> Option<&crate::variables::Variable> {
        self.variables.get(name)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::variables::Variable;
    use serde_json::json;

    #[test]
    fn reads_a_fetched_task() {
        let task = ExternalTask::from_json(&json!({
            "id": "t1", "topicName": "fraud-check", "workerId": "w", "retries": null,
            "priority": 5, "processInstanceId": "pi", "processDefinitionId": "pd:1",
            "processDefinitionKey": "pd", "activityId": "check", "activityInstanceId": "ai",
            "executionId": "ex", "tenantId": null, "businessKey": "bk",
            "variables": {"amount": {"value": 12, "type": "Long", "valueInfo": {}}}
        }))
        .unwrap();
        assert_eq!(task.id, "t1");
        assert_eq!(task.retries, None);
        assert_eq!(task.priority, 5);
        assert_eq!(task.business_key.as_deref(), Some("bk"));
        assert_eq!(task.variable("amount"), Some(&Variable::Long(12)));
    }

    #[test]
    fn rejects_a_task_without_id() {
        assert!(ExternalTask::from_json(&json!({"topicName": "x"})).is_err());
    }
}
