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

//! Typed process variables.
//!
//! The engine's REST API transports every variable with a type. [`Variable`] keeps that type, so a
//! `Long` never silently becomes an `Integer` or a string.

use std::collections::BTreeMap;

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use serde_json::{json, Map, Value};

use crate::error::{Error, Result};

/// A set of named variables.
pub type Variables = BTreeMap<String, Variable>;

/// A typed variable value.
#[derive(Debug, Clone, PartialEq)]
pub enum Variable {
    /// `String`
    String(String),
    /// `Boolean`
    Boolean(bool),
    /// `Integer`, a 32 bit integer.
    Integer(i32),
    /// `Long`, a 64 bit integer.
    Long(i64),
    /// `Double`
    Double(f64),
    /// `Date`, in the engine's format `yyyy-MM-dd'T'HH:mm:ss.SSSZ`, for example
    /// `2026-01-31T10:15:30.000+0000`.
    Date(String),
    /// `Json`, a JSON document. The engine stores it as text; the client parses it.
    Json(Value),
    /// `Object`, a serialized Java object (or another serialization data format).
    Object {
        /// The serialized value.
        value: String,
        /// The type name, for Java the class name.
        object_type_name: String,
        /// The serialization format, for example `application/json`.
        serialization_data_format: String,
    },
    /// `File`
    File {
        /// File name.
        filename: String,
        /// MIME type, if known.
        mimetype: Option<String>,
        /// Encoding, if known.
        encoding: Option<String>,
        /// The content. The engine does not send it with fetch-and-lock, so received files carry
        /// `None`; sent files carry the bytes.
        content: Option<Vec<u8>>,
    },
    /// `Null`
    Null,
    /// A type the client has no dedicated variant for (for example `Short`, `Bytes` or `Xml`),
    /// kept as received so that it can be sent back unchanged.
    Other {
        /// The engine's type name.
        type_name: String,
        /// The raw value.
        value: Value,
        /// The raw value info.
        value_info: Value,
    },
}

impl Variable {
    /// A `Json` variable from anything serializable.
    pub fn json<T: serde::Serialize>(value: &T) -> Result<Self> {
        serde_json::to_value(value)
            .map(Variable::Json)
            .map_err(|e| Error::decode("JSON variable value", e))
    }

    /// The string value of a `String` variable.
    pub fn as_str(&self) -> Option<&str> {
        match self {
            Variable::String(s) => Some(s),
            _ => None,
        }
    }

    /// The value of a `Boolean` variable.
    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Variable::Boolean(b) => Some(*b),
            _ => None,
        }
    }

    /// The value of an `Integer` or `Long` variable.
    pub fn as_i64(&self) -> Option<i64> {
        match self {
            Variable::Integer(i) => Some(i64::from(*i)),
            Variable::Long(l) => Some(*l),
            _ => None,
        }
    }

    /// The value of a `Double` variable, or of an integer one converted explicitly.
    pub fn as_f64(&self) -> Option<f64> {
        match self {
            Variable::Double(d) => Some(*d),
            Variable::Integer(i) => Some(f64::from(*i)),
            // Long to f64 may round above 2^53; that is the explicit meaning of as_f64.
            Variable::Long(l) => Some(*l as f64),
            _ => None,
        }
    }

    /// The document of a `Json` variable.
    pub fn as_json(&self) -> Option<&Value> {
        match self {
            Variable::Json(v) => Some(v),
            _ => None,
        }
    }

    /// The engine's type name.
    pub fn type_name(&self) -> &str {
        match self {
            Variable::String(_) => "String",
            Variable::Boolean(_) => "Boolean",
            Variable::Integer(_) => "Integer",
            Variable::Long(_) => "Long",
            Variable::Double(_) => "Double",
            Variable::Date(_) => "Date",
            Variable::Json(_) => "Json",
            Variable::Object { .. } => "Object",
            Variable::File { .. } => "File",
            Variable::Null => "Null",
            Variable::Other { type_name, .. } => type_name,
        }
    }

    /// The REST representation: `{"value": ..., "type": ..., "valueInfo": {...}}`.
    pub fn to_json(&self) -> Value {
        match self {
            Variable::String(s) => json!({"value": s, "type": "String"}),
            Variable::Boolean(b) => json!({"value": b, "type": "Boolean"}),
            Variable::Integer(i) => json!({"value": i, "type": "Integer"}),
            Variable::Long(l) => json!({"value": l, "type": "Long"}),
            Variable::Double(d) => json!({"value": d, "type": "Double"}),
            Variable::Date(d) => json!({"value": d, "type": "Date"}),
            // A JSON document is serialized by serde_json, which cannot fail for a Value.
            Variable::Json(v) => json!({"value": v.to_string(), "type": "Json"}),
            Variable::Object {
                value,
                object_type_name,
                serialization_data_format,
            } => json!({
                "value": value,
                "type": "Object",
                "valueInfo": {
                    "objectTypeName": object_type_name,
                    "serializationDataFormat": serialization_data_format,
                },
            }),
            Variable::File {
                filename,
                mimetype,
                encoding,
                content,
            } => {
                let mut info = Map::new();
                info.insert("filename".into(), json!(filename));
                if let Some(m) = mimetype {
                    info.insert("mimetype".into(), json!(m));
                }
                if let Some(e) = encoding {
                    info.insert("encoding".into(), json!(e));
                }
                let value = content
                    .as_ref()
                    .map(|c| Value::String(BASE64.encode(c)))
                    .unwrap_or(Value::Null);
                json!({"value": value, "type": "File", "valueInfo": info})
            }
            Variable::Null => json!({"value": null, "type": "Null"}),
            Variable::Other {
                type_name,
                value,
                value_info,
            } => json!({"value": value, "type": type_name, "valueInfo": value_info}),
        }
    }

    /// Reads the REST representation of a variable.
    pub fn from_json(value: &Value) -> Result<Self> {
        let object = value
            .as_object()
            .ok_or_else(|| Error::decode("variable", "not a JSON object"))?;
        let type_name = object
            .get("type")
            .and_then(Value::as_str)
            .ok_or_else(|| Error::decode("variable", "missing 'type'"))?;
        let raw = object.get("value").cloned().unwrap_or(Value::Null);
        let info = object.get("valueInfo").cloned().unwrap_or(Value::Null);
        let bad = |expected: &str| {
            Error::decode(
                format!("{type_name} variable"),
                format!("expected {expected}, got {raw}"),
            )
        };
        if raw.is_null() && type_name != "File" && type_name != "Null" && type_name != "Object" {
            // The engine sends typed nulls, for example a String variable without a value.
            return Ok(match type_name {
                "String" | "Boolean" | "Integer" | "Long" | "Double" | "Date" | "Json" => {
                    Variable::Null
                }
                other => Variable::Other {
                    type_name: other.to_string(),
                    value: Value::Null,
                    value_info: info,
                },
            });
        }
        Ok(match type_name {
            "String" => Variable::String(raw.as_str().ok_or_else(|| bad("a string"))?.to_string()),
            "Boolean" => Variable::Boolean(raw.as_bool().ok_or_else(|| bad("a boolean"))?),
            "Integer" => {
                let n = raw.as_i64().ok_or_else(|| bad("an integer"))?;
                Variable::Integer(i32::try_from(n).map_err(|_| bad("a 32 bit integer"))?)
            }
            "Long" => Variable::Long(raw.as_i64().ok_or_else(|| bad("a 64 bit integer"))?),
            "Double" => Variable::Double(raw.as_f64().ok_or_else(|| bad("a number"))?),
            "Date" => Variable::Date(
                raw.as_str()
                    .ok_or_else(|| bad("a date string"))?
                    .to_string(),
            ),
            "Json" => {
                let text = raw.as_str().ok_or_else(|| bad("a JSON string"))?;
                Variable::Json(
                    serde_json::from_str(text)
                        .map_err(|e| Error::decode("Json variable value", e))?,
                )
            }
            "Object" => Variable::Object {
                value: match &raw {
                    Value::String(s) => s.clone(),
                    Value::Null => String::new(),
                    other => other.to_string(),
                },
                object_type_name: info
                    .get("objectTypeName")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string(),
                serialization_data_format: info
                    .get("serializationDataFormat")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string(),
            },
            "File" => Variable::File {
                filename: info
                    .get("filename")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string(),
                mimetype: info
                    .get("mimetype")
                    .and_then(Value::as_str)
                    .map(str::to_string),
                encoding: info
                    .get("encoding")
                    .and_then(Value::as_str)
                    .map(str::to_string),
                content: match raw.as_str() {
                    Some(text) => Some(
                        BASE64
                            .decode(text)
                            .map_err(|e| Error::decode("File variable content", e))?,
                    ),
                    None => None,
                },
            },
            "Null" => Variable::Null,
            other => Variable::Other {
                type_name: other.to_string(),
                value: raw,
                value_info: info,
            },
        })
    }
}

/// Converts variables to the REST map `{name: {value, type, valueInfo}}`.
pub fn variables_to_json(variables: &Variables) -> Value {
    Value::Object(
        variables
            .iter()
            .map(|(name, variable)| (name.clone(), variable.to_json()))
            .collect(),
    )
}

/// Reads the REST map `{name: {value, type, valueInfo}}`.
pub fn variables_from_json(value: &Value) -> Result<Variables> {
    match value {
        Value::Null => Ok(Variables::new()),
        Value::Object(map) => map
            .iter()
            .map(|(name, v)| {
                Variable::from_json(v)
                    .map(|variable| (name.clone(), variable))
                    .map_err(|e| Error::decode(format!("variable '{name}'"), e))
            })
            .collect(),
        _ => Err(Error::decode("variables", "not a JSON object")),
    }
}

impl From<&str> for Variable {
    fn from(value: &str) -> Self {
        Variable::String(value.to_string())
    }
}

impl From<String> for Variable {
    fn from(value: String) -> Self {
        Variable::String(value)
    }
}

impl From<bool> for Variable {
    fn from(value: bool) -> Self {
        Variable::Boolean(value)
    }
}

impl From<i32> for Variable {
    fn from(value: i32) -> Self {
        Variable::Integer(value)
    }
}

impl From<i64> for Variable {
    fn from(value: i64) -> Self {
        Variable::Long(value)
    }
}

impl From<f64> for Variable {
    fn from(value: f64) -> Self {
        Variable::Double(value)
    }
}

impl From<Value> for Variable {
    fn from(value: Value) -> Self {
        Variable::Json(value)
    }
}
