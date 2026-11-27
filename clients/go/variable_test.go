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

package operaton

import (
	"encoding/json"
	"errors"
	"math"
	"testing"
	"time"
)

func TestVariableWireFormat(t *testing.T) {
	date := time.Date(2026, 3, 4, 5, 6, 7, 123_000_000, time.FixedZone("x", 2*3600))
	cases := []struct {
		name string
		v    Variable
		want string
	}{
		{"string", String("hi"), `{"type":"String","value":"hi"}`},
		{"boolean", Boolean(true), `{"type":"Boolean","value":true}`},
		{"integer", Integer(-7), `{"type":"Integer","value":-7}`},
		{"long keeps precision", Long(9007199254740993), `{"type":"Long","value":9007199254740993}`},
		{"double", Double(1.5), `{"type":"Double","value":1.5}`},
		{"date", Date(date), `{"type":"Date","value":"2026-03-04T05:06:07.123+0200"}`},
		{"json from value", JSON(map[string]int{"a": 1}), `{"type":"Json","value":"{\"a\":1}"}`},
		{"json from text", JSON(`{"b":2}`), `{"type":"Json","value":"{\"b\":2}"}`},
		{"xml", XML("<a/>"), "{\"type\":\"Xml\",\"value\":\"\\u003ca/\\u003e\"}"},
		{"bytes", Bytes([]byte("abc")), `{"type":"Bytes","value":"YWJj"}`},
		{"null", Null(), `{"type":"Null","value":null}`},
		{"object", Object(`{"x":1}`, "com.acme.X", "application/json"),
			`{"type":"Object","value":"{\"x\":1}","valueInfo":{"objectTypeName":"com.acme.X","serializationDataFormat":"application/json"}}`},
		{"file", File("a.txt", "text/plain", []byte("abc")),
			`{"type":"File","value":"YWJj","valueInfo":{"filename":"a.txt","mimetype":"text/plain"}}`},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, err := json.Marshal(c.v)
			if err != nil {
				t.Fatal(err)
			}
			if string(got) != c.want {
				t.Fatalf("got  %s\nwant %s", got, c.want)
			}
		})
	}
}

func TestVariableEncodeErrorsAreReported(t *testing.T) {
	for name, v := range map[string]Variable{
		"nan":           Double(math.NaN()),
		"inf":           Double(math.Inf(1)),
		"invalid json":  JSON("{nope"),
		"unmarshalable": JSON(make(chan int)),
		"no type":       {},
	} {
		if _, err := json.Marshal(Variables{"x": v}); err == nil {
			t.Errorf("%s: expected an encode error", name)
		}
	}
}

func TestVariableAccessors(t *testing.T) {
	s, err := String("x").AsString()
	if err != nil || s != "x" {
		t.Fatalf("AsString = %q, %v", s, err)
	}
	if b, err := Boolean(true).AsBool(); err != nil || !b {
		t.Fatalf("AsBool = %v, %v", b, err)
	}
	if i, err := Long(1 << 60).AsInt64(); err != nil || i != 1<<60 {
		t.Fatalf("AsInt64 = %v, %v", i, err)
	}
	if i, err := Integer(5).AsInt64(); err != nil || i != 5 {
		t.Fatalf("AsInt64(Integer) = %v, %v", i, err)
	}
	if f, err := Double(2.25).AsFloat64(); err != nil || f != 2.25 {
		t.Fatalf("AsFloat64 = %v, %v", f, err)
	}
	now := time.Date(2026, 1, 2, 3, 4, 5, 6_000_000, time.UTC)
	if got, err := Date(now).AsTime(); err != nil || !got.Equal(now) {
		t.Fatalf("AsTime = %v, %v", got, err)
	}
	var out struct{ A int }
	if err := JSON(map[string]int{"A": 4}).AsJSON(&out); err != nil || out.A != 4 {
		t.Fatalf("AsJSON = %+v, %v", out, err)
	}
	if b, err := Bytes([]byte("zz")).AsBytes(); err != nil || string(b) != "zz" {
		t.Fatalf("AsBytes = %q, %v", b, err)
	}
	f := File("n.bin", "application/octet-stream", []byte{1, 2})
	if b, err := f.AsBytes(); err != nil || len(b) != 2 || f.FileName() != "n.bin" || f.MimeType() != "application/octet-stream" {
		t.Fatalf("file accessors: %v %v", b, err)
	}
	if o := Object("{}", "a.B", "application/json"); o.ObjectTypeName() != "a.B" {
		t.Fatalf("ObjectTypeName = %q", o.ObjectTypeName())
	}
	if !Null().IsNull() || String("x").IsNull() {
		t.Fatal("IsNull wrong")
	}
}

func TestVariableNoImplicitConversion(t *testing.T) {
	if _, err := Long(1).AsString(); !errors.Is(err, ErrTypeMismatch) {
		t.Errorf("Long as string: %v", err)
	}
	if _, err := String("1").AsInt64(); !errors.Is(err, ErrTypeMismatch) {
		t.Errorf("String as int: %v", err)
	}
	if _, err := Integer(1).AsFloat64(); !errors.Is(err, ErrTypeMismatch) {
		t.Errorf("Integer as float: %v", err)
	}
	if _, err := Null().AsString(); err == nil {
		t.Error("null as string must fail")
	}
}

func TestVariableDecodesEngineResponse(t *testing.T) {
	body := `{"id":"t1","retries":null,"lockExpirationTime":"2026-03-04T05:06:07.123+0000","variables":{
		"n":{"type":"Long","value":9007199254740993,"valueInfo":{}},
		"o":{"type":"Object","value":"{}","valueInfo":{"objectTypeName":"a.B"}}}}`
	var task Task
	if err := json.Unmarshal([]byte(body), &task); err != nil {
		t.Fatal(err)
	}
	n, ok := task.Variable("n")
	if !ok {
		t.Fatal("variable n missing")
	}
	if i, err := n.AsInt64(); err != nil || i != 9007199254740993 {
		t.Fatalf("n = %d, %v", i, err)
	}
	if task.Retries != nil {
		t.Fatalf("retries should be nil, got %d", *task.Retries)
	}
	if task.LockExpirationTime.Year() != 2026 {
		t.Fatalf("lock expiration = %v", task.LockExpirationTime)
	}
	if _, err := task.RequireVariable("missing"); err == nil {
		t.Fatal("RequireVariable must fail for a missing variable")
	}
}
