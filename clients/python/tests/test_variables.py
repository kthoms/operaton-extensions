# Copyright 2026 the Operaton Extensions contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from __future__ import annotations

import base64
import json
from datetime import datetime, timedelta, timezone

import pytest

from operaton_external_task_client import Variable, VariableError
from operaton_external_task_client.variables import (
    decode_variables,
    encode_variables,
    format_date,
    parse_date,
)


@pytest.mark.parametrize(
    ("value", "type_"),
    [
        ("x", "String"),
        (True, "Boolean"),
        (7, "Integer"),
        (2**31, "Long"),
        (-(2**31) - 1, "Long"),
        (1.5, "Double"),
        (None, "Null"),
        (b"\x00\x01", "Bytes"),
        ({"a": [1, 2]}, "Json"),
        ([1, "b"], "Json"),
        (datetime(2026, 1, 2, tzinfo=timezone.utc), "Date"),
    ],
)
def test_natural_types(value, type_):
    assert Variable.of(value).type == type_


def test_bool_is_not_integer():
    assert Variable.of(True).to_rest() == {"value": True, "type": "Boolean"}


def test_unknown_python_type_fails_loudly():
    with pytest.raises(VariableError, match="cannot map set"):
        Variable.of({1, 2})


def test_explicit_factories_validate():
    with pytest.raises(VariableError):
        Variable.integer(2**31)
    with pytest.raises(VariableError):
        Variable.long(2**63)
    with pytest.raises(VariableError):
        Variable.string(5)  # type: ignore[arg-type]
    with pytest.raises(VariableError):
        Variable.json(object())
    assert Variable.long(5).to_rest()["type"] == "Long"
    assert Variable.double(3).to_rest() == {"value": 3.0, "type": "Double"}


def test_json_is_serialised_as_text_and_parsed_back():
    wire = Variable.of({"invoice": {"id": 1}}).to_rest()
    assert wire["type"] == "Json"
    assert json.loads(wire["value"]) == {"invoice": {"id": 1}}
    assert Variable.from_rest(wire).value == {"invoice": {"id": 1}}


def test_date_format_and_roundtrip():
    d = datetime(2026, 3, 4, 5, 6, 7, 123000, tzinfo=timezone.utc)
    assert format_date(d) == "2026-03-04T05:06:07.123+0000"
    assert parse_date("2026-03-04T05:06:07.123+0000") == d
    assert parse_date("2026-03-04T05:06:07+0000") == d.replace(microsecond=0)
    assert parse_date("2026-03-04T05:06:07.123+00:00") == d


def test_date_with_offset_and_naive_as_utc():
    plus2 = datetime(2026, 1, 1, 12, tzinfo=timezone(timedelta(hours=2)))
    assert format_date(plus2) == "2026-01-01T12:00:00.000+0200"
    assert format_date(datetime(2026, 1, 1, 12)) == "2026-01-01T12:00:00.000+0000"


def test_bad_date_fails():
    with pytest.raises(VariableError):
        Variable.from_rest({"type": "Date", "value": "yesterday"})


def test_object_and_file_value_info():
    obj = Variable.object('{"a":1}', "com.acme.Invoice").to_rest()
    assert obj == {
        "value": '{"a":1}',
        "type": "Object",
        "valueInfo": {
            "objectTypeName": "com.acme.Invoice",
            "serializationDataFormat": "application/json",
        },
    }
    f = Variable.file(b"abc", "a.txt", "text/plain", "UTF-8").to_rest()
    assert f["type"] == "File"
    assert base64.b64decode(f["value"]) == b"abc"
    assert f["valueInfo"] == {"filename": "a.txt", "mimeType": "text/plain", "encoding": "UTF-8"}


def test_from_rest_keeps_unknown_types_and_file_metadata():
    v = Variable.from_rest({"type": "Xml", "value": "<a/>", "valueInfo": {}})
    assert (v.type, v.value) == ("Xml", "<a/>")
    f = Variable.from_rest({"type": "File", "value": None, "valueInfo": {"filename": "x.pdf"}})
    assert f.value is None
    assert f.value_info["filename"] == "x.pdf"


def test_from_rest_rejects_untyped():
    with pytest.raises(VariableError):
        Variable.from_rest({"value": 1})


def test_encode_variables():
    out = encode_variables({"a": 1, "b": Variable.long(2), "c": None})
    assert out == {
        "a": {"value": 1, "type": "Integer"},
        "b": {"value": 2, "type": "Long"},
        "c": {"value": None, "type": "Null"},
    }
    assert encode_variables(None) == {}


def test_encode_names_the_bad_variable():
    with pytest.raises(VariableError, match="variable 'x'"):
        encode_variables({"x": object()})
    with pytest.raises(VariableError):
        encode_variables({"": 1})


def test_decode_variables():
    out = decode_variables({"n": {"value": 3, "type": "Long"}})
    assert out["n"] == Variable(3, "Long", {})
