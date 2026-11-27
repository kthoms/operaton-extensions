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

//! Round trips of typed variables through their REST representation.

use operaton_external_task_client::{variables_from_json, variables_to_json, Variable, Variables};
use serde_json::json;

fn roundtrip(variable: Variable) {
    let back = Variable::from_json(&variable.to_json()).unwrap();
    assert_eq!(back, variable);
}

#[test]
fn every_type_round_trips() {
    roundtrip(Variable::String("hello".into()));
    roundtrip(Variable::Boolean(true));
    roundtrip(Variable::Integer(-7));
    roundtrip(Variable::Long(9_000_000_000));
    roundtrip(Variable::Double(1.5));
    roundtrip(Variable::Date("2026-01-31T10:15:30.000+0000".into()));
    roundtrip(Variable::Json(json!({"a": [1, 2, {"b": null}]})));
    roundtrip(Variable::Object {
        value: "{\"x\":1}".into(),
        object_type_name: "com.example.Order".into(),
        serialization_data_format: "application/json".into(),
    });
    roundtrip(Variable::File {
        filename: "a.txt".into(),
        mimetype: Some("text/plain".into()),
        encoding: Some("UTF-8".into()),
        content: Some(b"hello".to_vec()),
    });
    roundtrip(Variable::Null);
    roundtrip(Variable::Other {
        type_name: "Xml".into(),
        value: json!("<a/>"),
        value_info: json!({}),
    });
}

#[test]
fn json_is_sent_as_text_and_received_as_a_document() {
    let sent = Variable::Json(json!({"k": 1})).to_json();
    assert_eq!(sent, json!({"value": "{\"k\":1}", "type": "Json"}));
    let received =
        Variable::from_json(&json!({"value": "[1,2]", "type": "Json", "valueInfo": {}})).unwrap();
    assert_eq!(received.as_json(), Some(&json!([1, 2])));
}

#[test]
fn the_type_is_never_widened() {
    let long = Variable::from_json(&json!({"value": 5, "type": "Long"})).unwrap();
    let int = Variable::from_json(&json!({"value": 5, "type": "Integer"})).unwrap();
    assert_eq!(long, Variable::Long(5));
    assert_eq!(int, Variable::Integer(5));
    assert_eq!(long.type_name(), "Long");
}

#[test]
fn wrong_values_are_errors_not_conversions() {
    assert!(Variable::from_json(&json!({"value": "x", "type": "Integer"})).is_err());
    assert!(Variable::from_json(&json!({"value": 3_000_000_000_i64, "type": "Integer"})).is_err());
    assert!(Variable::from_json(&json!({"value": "{bad", "type": "Json"})).is_err());
    assert!(Variable::from_json(&json!({"value": 1})).is_err());
    assert!(Variable::from_json(&json!("x")).is_err());
}

#[test]
fn typed_nulls_are_null() {
    let v =
        Variable::from_json(&json!({"value": null, "type": "String", "valueInfo": {}})).unwrap();
    assert_eq!(v, Variable::Null);
}

#[test]
fn variable_maps_convert() {
    let mut vars = Variables::new();
    vars.insert("a".into(), "x".into());
    vars.insert("b".into(), 1.into());
    let json = variables_to_json(&vars);
    assert_eq!(variables_from_json(&json).unwrap(), vars);
    assert!(variables_from_json(&json!([1])).is_err());
    assert!(variables_from_json(&json!(null)).unwrap().is_empty());
}

#[test]
fn accessors() {
    assert_eq!(Variable::Integer(3).as_i64(), Some(3));
    assert_eq!(Variable::Long(3).as_f64(), Some(3.0));
    assert_eq!(Variable::from("s").as_str(), Some("s"));
    assert_eq!(Variable::from(true).as_bool(), Some(true));
    assert_eq!(Variable::from("s").as_i64(), None);
}
