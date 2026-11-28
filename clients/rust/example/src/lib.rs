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

//! The fraud check of the example: scores a transaction and either approves it or reports the
//! BPMN error `FRAUD_SUSPECTED`, which the process model routes to "Transaction blocked".

use operaton_external_task_client::{Completion, ExternalTask, TaskError, TaskService, Variable};

/// The external task topic of the "Check transaction" service task in `fraud-check.bpmn`.
pub const TOPIC: &str = "fraud-check";

/// The BPMN error code caught by the error boundary event of the model.
pub const FRAUD_SUSPECTED: &str = "FRAUD_SUSPECTED";

/// Risk score at or above which a transaction is blocked.
pub const BLOCK_THRESHOLD: i32 = 80;

const HIGH_RISK_COUNTRIES: [&str; 3] = ["KP", "IR", "SY"];

/// The risk score of a transaction: larger amounts and high-risk countries score higher.
pub fn risk_score(amount: f64, country: &str) -> i32 {
    let mut score = 0;
    if amount > 10_000.0 {
        score += 50;
    } else if amount > 1_000.0 {
        score += 20;
    }
    if HIGH_RISK_COUNTRIES.contains(&country) {
        score += 40;
    }
    score
}

/// Assesses the task's transaction (`amount` and `country` variables).
///
/// - a missing or malformed variable is a technical failure (retried, then an incident);
/// - a score at or above [`BLOCK_THRESHOLD`] is a BPMN error;
/// - otherwise the task completes with `riskScore` (Integer) and `decision` (String).
pub fn assess(task: &ExternalTask) -> Result<Completion, TaskError> {
    let amount = task
        .variable("amount")
        .and_then(Variable::as_f64)
        .ok_or_else(|| TaskError::failure("variable 'amount' is missing or not a number"))?;
    let country = task
        .variable("country")
        .and_then(Variable::as_str)
        .ok_or_else(|| TaskError::failure("variable 'country' is missing or not a string"))?;

    let score = risk_score(amount, country);
    if score >= BLOCK_THRESHOLD {
        return Err(TaskError::bpmn_with_variables(
            FRAUD_SUSPECTED,
            format!("risk score {score} for {amount} from {country}"),
            [("riskScore".to_string(), Variable::Integer(score))]
                .into_iter()
                .collect(),
        ));
    }
    Ok(Completion::new()
        .variable("riskScore", score)
        .variable("decision", "approved"))
}

/// The handler registered for [`TOPIC`].
pub async fn fraud_check(
    task: ExternalTask,
    _service: TaskService,
) -> Result<Completion, TaskError> {
    tracing::info!(task = %task.id, business_key = ?task.business_key, "checking transaction");
    assess(&task)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn task(amount: serde_json::Value, country: &str) -> ExternalTask {
        ExternalTask::from_json(&json!({
            "id": "t", "topicName": TOPIC,
            "variables": {
                "amount": amount,
                "country": {"value": country, "type": "String"}
            }
        }))
        .expect("task")
    }

    #[test]
    fn small_domestic_transactions_are_approved() {
        let completion =
            assess(&task(json!({"value": 120.5, "type": "Double"}), "DE")).expect("ok");
        assert_eq!(
            completion.variables.get("riskScore"),
            Some(&Variable::Integer(0))
        );
        assert_eq!(
            completion.variables.get("decision"),
            Some(&Variable::String("approved".into()))
        );
    }

    #[test]
    fn large_transactions_from_risky_countries_are_blocked() {
        let error =
            assess(&task(json!({"value": 25000, "type": "Long"}), "KP")).expect_err("blocked");
        assert!(matches!(error, TaskError::Bpmn { ref code, .. } if code == FRAUD_SUSPECTED));
    }

    #[test]
    fn a_large_amount_alone_is_not_enough() {
        assert!(assess(&task(json!({"value": 25000, "type": "Long"}), "DE")).is_ok());
    }

    #[test]
    fn malformed_input_is_a_failure() {
        let error =
            assess(&task(json!({"value": "x", "type": "String"}), "DE")).expect_err("fails");
        assert!(matches!(error, TaskError::Failure { .. }));
    }
}
