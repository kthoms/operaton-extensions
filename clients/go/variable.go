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

// Portions derived from camunda-client-go, Copyright (c) 2019 Citilink LLC, MIT License
// (full notice in LICENSE-THIRD-PARTY and NOTICE).
// Ported from https://github.com/citilinkru/camunda-client-go (MIT): the typed Variable JSON model of external-task.go, reworked (raw values, typed constructors and accessors)

package operaton

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"time"
)

// Variable type names as used by the engine REST API.
const (
	TypeString  = "String"
	TypeBoolean = "Boolean"
	TypeShort   = "Short"
	TypeInteger = "Integer"
	TypeLong    = "Long"
	TypeDouble  = "Double"
	TypeDate    = "Date"
	TypeJSON    = "Json"
	TypeXML     = "Xml"
	TypeObject  = "Object"
	TypeFile    = "File"
	TypeBytes   = "Bytes"
	TypeNull    = "Null"
)

// dateLayout is the engine's date format (yyyy-MM-dd'T'HH:mm:ss.SSSZ).
const dateLayout = "2006-01-02T15:04:05.000-0700"

// ErrTypeMismatch is returned (wrapped) by the Variable accessors when the variable has a type
// the accessor does not read. The client never converts between types implicitly.
var ErrTypeMismatch = errors.New("operaton: variable type mismatch")

// Variable is a typed process variable as exchanged with the engine: a type name, a value and
// optional value info. Build values with the constructors (String, Long, JSON, ...) and read them
// with the As* accessors. The raw value is kept as received, so large Long values and unknown
// types survive a round trip unchanged.
type Variable struct {
	Type      string          `json:"type"`
	Value     json.RawMessage `json:"value"`
	ValueInfo map[string]any  `json:"valueInfo,omitempty"`

	// err records a failure while building the variable; MarshalJSON reports it.
	err error
}

// Variables maps variable names to values.
type Variables map[string]Variable

// MarshalJSON reports an error recorded by a constructor (for example Double(math.NaN()) or JSON
// of an unmarshalable value) instead of sending a wrong value to the engine.
func (v Variable) MarshalJSON() ([]byte, error) {
	if v.err != nil {
		return nil, v.err
	}
	if v.Type == "" {
		return nil, errors.New("operaton: variable without type")
	}
	value := v.Value
	if len(value) == 0 {
		value = json.RawMessage("null")
	}
	type plain struct {
		Type      string          `json:"type"`
		Value     json.RawMessage `json:"value"`
		ValueInfo map[string]any  `json:"valueInfo,omitempty"`
	}
	return json.Marshal(plain{Type: v.Type, Value: value, ValueInfo: v.ValueInfo})
}

func newVariable(typ string, value any) Variable {
	raw, err := json.Marshal(value)
	if err != nil {
		return Variable{Type: typ, err: fmt.Errorf("operaton: encode %s variable: %w", typ, err)}
	}
	return Variable{Type: typ, Value: raw}
}

// String creates a String variable.
func String(s string) Variable { return newVariable(TypeString, s) }

// Boolean creates a Boolean variable.
func Boolean(b bool) Variable { return newVariable(TypeBoolean, b) }

// Integer creates an Integer (32 bit) variable.
func Integer(i int32) Variable { return newVariable(TypeInteger, i) }

// Long creates a Long (64 bit) variable.
func Long(i int64) Variable { return newVariable(TypeLong, i) }

// Double creates a Double variable. NaN and infinities cannot be represented and make the
// variable fail to encode.
func Double(f float64) Variable {
	if math.IsNaN(f) || math.IsInf(f, 0) {
		return Variable{Type: TypeDouble, err: fmt.Errorf("operaton: Double variable cannot hold %v", f)}
	}
	return newVariable(TypeDouble, f)
}

// Date creates a Date variable (millisecond precision).
func Date(t time.Time) Variable { return newVariable(TypeDate, t.Format(dateLayout)) }

// JSON creates a Json variable. A string, []byte or json.RawMessage argument is taken as JSON
// text (and validated); any other value is marshaled with encoding/json.
func JSON(v any) Variable {
	var text []byte
	switch x := v.(type) {
	case string:
		text = []byte(x)
	case []byte:
		text = x
	case json.RawMessage:
		text = x
	default:
		b, err := json.Marshal(v)
		if err != nil {
			return Variable{Type: TypeJSON, err: fmt.Errorf("operaton: encode Json variable: %w", err)}
		}
		text = b
	}
	if !json.Valid(text) {
		return Variable{Type: TypeJSON, err: errors.New("operaton: Json variable is not valid JSON")}
	}
	return newVariable(TypeJSON, string(text))
}

// XML creates an Xml variable from XML text.
func XML(s string) Variable { return newVariable(TypeXML, s) }

// Bytes creates a Bytes variable (base64 on the wire).
func Bytes(b []byte) Variable { return newVariable(TypeBytes, base64.StdEncoding.EncodeToString(b)) }

// Object creates a serialized Object variable: the serialized value, the Java class name the
// engine uses to deserialize it and the serialization data format (for example
// "application/json").
func Object(serialized, objectTypeName, dataFormat string) Variable {
	v := newVariable(TypeObject, serialized)
	v.ValueInfo = map[string]any{
		"objectTypeName":          objectTypeName,
		"serializationDataFormat": dataFormat,
	}
	return v
}

// File creates a File variable.
func File(name, mimeType string, content []byte) Variable {
	v := newVariable(TypeFile, base64.StdEncoding.EncodeToString(content))
	v.ValueInfo = map[string]any{"filename": name}
	if mimeType != "" {
		v.ValueInfo["mimetype"] = mimeType
	}
	return v
}

// Null creates a Null variable.
func Null() Variable { return Variable{Type: TypeNull, Value: json.RawMessage("null")} }

// IsNull reports whether the variable holds no value.
func (v Variable) IsNull() bool {
	return v.Type == TypeNull || len(v.Value) == 0 || string(v.Value) == "null"
}

func (v Variable) mismatch(want string) error {
	return fmt.Errorf("%w: variable has type %q, cannot read it as %s", ErrTypeMismatch, v.Type, want)
}

func (v Variable) decode(want string, out any) error {
	if v.IsNull() {
		return fmt.Errorf("operaton: variable of type %q is null, cannot read it as %s", v.Type, want)
	}
	if err := json.Unmarshal(v.Value, out); err != nil {
		return fmt.Errorf("operaton: decode %q variable as %s: %w", v.Type, want, err)
	}
	return nil
}

// AsString reads a String, Json (the JSON text), Xml or serialized Object variable.
func (v Variable) AsString() (string, error) {
	switch v.Type {
	case TypeString, TypeJSON, TypeXML, TypeObject:
		var s string
		return s, v.decode("string", &s)
	}
	return "", v.mismatch("string")
}

// AsBool reads a Boolean variable.
func (v Variable) AsBool() (bool, error) {
	if v.Type != TypeBoolean {
		return false, v.mismatch("bool")
	}
	var b bool
	return b, v.decode("bool", &b)
}

// AsInt64 reads a Short, Integer or Long variable.
func (v Variable) AsInt64() (int64, error) {
	switch v.Type {
	case TypeShort, TypeInteger, TypeLong:
		var i int64
		return i, v.decode("int64", &i)
	}
	return 0, v.mismatch("int64")
}

// AsFloat64 reads a Double variable.
func (v Variable) AsFloat64() (float64, error) {
	if v.Type != TypeDouble {
		return 0, v.mismatch("float64")
	}
	var f float64
	return f, v.decode("float64", &f)
}

// AsTime reads a Date variable.
func (v Variable) AsTime() (time.Time, error) {
	if v.Type != TypeDate {
		return time.Time{}, v.mismatch("time.Time")
	}
	var s string
	if err := v.decode("time.Time", &s); err != nil {
		return time.Time{}, err
	}
	return parseEngineTime(s)
}

// AsJSON decodes a Json variable into out (a pointer, as for json.Unmarshal).
func (v Variable) AsJSON(out any) error {
	if v.Type != TypeJSON {
		return v.mismatch("JSON")
	}
	var s string
	if err := v.decode("JSON", &s); err != nil {
		return err
	}
	if err := json.Unmarshal([]byte(s), out); err != nil {
		return fmt.Errorf("operaton: decode Json variable: %w", err)
	}
	return nil
}

// AsBytes reads a Bytes or File variable's content.
func (v Variable) AsBytes() ([]byte, error) {
	if v.Type != TypeBytes && v.Type != TypeFile {
		return nil, v.mismatch("[]byte")
	}
	var s string
	if err := v.decode("[]byte", &s); err != nil {
		return nil, err
	}
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return nil, fmt.Errorf("operaton: decode %q variable: %w", v.Type, err)
	}
	return b, nil
}

// FileName returns the file name of a File variable.
func (v Variable) FileName() string {
	s, _ := v.ValueInfo["filename"].(string)
	return s
}

// MimeType returns the MIME type of a File variable.
func (v Variable) MimeType() string {
	s, _ := v.ValueInfo["mimetype"].(string)
	return s
}

// ObjectTypeName returns the class name of a serialized Object variable.
func (v Variable) ObjectTypeName() string {
	s, _ := v.ValueInfo["objectTypeName"].(string)
	return s
}

func parseEngineTime(s string) (time.Time, error) {
	for _, layout := range []string{dateLayout, time.RFC3339Nano, "2006-01-02T15:04:05-0700"} {
		if t, err := time.Parse(layout, s); err == nil {
			return t, nil
		}
	}
	return time.Time{}, fmt.Errorf("operaton: cannot parse date %q", s)
}

// Timestamp is a time in the engine's date format.
type Timestamp struct{ time.Time }

// UnmarshalJSON parses the engine's date format; null leaves the zero time.
func (t *Timestamp) UnmarshalJSON(b []byte) error {
	if string(b) == "null" {
		return nil
	}
	var s string
	if err := json.Unmarshal(b, &s); err != nil {
		return err
	}
	parsed, err := parseEngineTime(s)
	if err != nil {
		return err
	}
	t.Time = parsed
	return nil
}

// MarshalJSON writes the engine's date format.
func (t Timestamp) MarshalJSON() ([]byte, error) {
	if t.IsZero() {
		return []byte("null"), nil
	}
	return json.Marshal(t.Format(dateLayout))
}
