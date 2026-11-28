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
#
# Ported from https://github.com/camunda-community-hub/
# camunda-external-task-client-python3 (Apache-2.0); the original files carry no header,
# copyright the camunda-community-hub contributors. Modernised for Operaton.

"""Typed process variables.

The REST API carries every variable as ``{"value": ..., "type": ..., "valueInfo": {...}}``. This
module converts between that wire format and Python values without losing the type: a plain Python
value is mapped to its natural type (see :meth:`Variable.of`), and the ``Variable`` factories
choose a type explicitly.

========== =========================== =============================================
Type       Python value                Notes
========== =========================== =============================================
String     ``str``
Boolean    ``bool``
Integer    ``int``                     32 bit; larger ints are sent as ``Long``
Long       ``int``                     64 bit
Double     ``float``
Date       ``datetime``                naive values are taken as UTC
Json       ``dict`` / ``list`` / ...   serialised with :mod:`json`, parsed on read
Object     ``str`` (serialised form)   with ``object_type_name`` and data format
File       ``bytes``                   with file name, MIME type, encoding
Bytes      ``bytes``
Null       ``None``
========== =========================== =============================================

Other engine types (``Short``, ``Xml``, ...) are kept as they arrive.
"""

from __future__ import annotations

import base64
import json
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from .errors import VariableError

INT_MIN = -(2**31)
INT_MAX = 2**31 - 1
LONG_MIN = -(2**63)
LONG_MAX = 2**63 - 1

_DATE_FORMAT = "%Y-%m-%dT%H:%M:%S.%f%z"


def format_date(value: datetime) -> str:
    """Format as the engine does: ``2026-01-31T12:00:00.000+0000``."""
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    text = value.strftime(_DATE_FORMAT)
    # %f gives microseconds; the engine's format has milliseconds
    head, _, tail = text.partition(".")
    return f"{head}.{tail[:3]}{tail[6:]}"


def parse_date(text: str) -> datetime:
    """Parse the engine's date format (milliseconds optional, ``+0000`` or ``+00:00``)."""
    for fmt in (_DATE_FORMAT, "%Y-%m-%dT%H:%M:%S%z"):
        try:
            return datetime.strptime(text, fmt)
        except ValueError:
            continue
    try:
        return datetime.fromisoformat(text)
    except ValueError as exc:
        raise VariableError(f"not an engine date: {text!r}") from exc


@dataclass(frozen=True)
class Variable:
    """A typed process variable: Python ``value``, engine ``type`` and ``value_info``."""

    value: Any
    type: str
    value_info: Mapping[str, Any] = field(default_factory=dict)

    # --- factories -------------------------------------------------------------------------

    @classmethod
    def string(cls, value: str) -> Variable:
        _require(isinstance(value, str), "String", value)
        return cls(value, "String")

    @classmethod
    def boolean(cls, value: bool) -> Variable:
        _require(isinstance(value, bool), "Boolean", value)
        return cls(value, "Boolean")

    @classmethod
    def integer(cls, value: int) -> Variable:
        _require(_is_int(value) and INT_MIN <= value <= INT_MAX, "Integer (32 bit)", value)
        return cls(value, "Integer")

    @classmethod
    def long(cls, value: int) -> Variable:
        _require(_is_int(value) and LONG_MIN <= value <= LONG_MAX, "Long (64 bit)", value)
        return cls(value, "Long")

    @classmethod
    def double(cls, value: float) -> Variable:
        _require(isinstance(value, int | float) and not isinstance(value, bool), "Double", value)
        return cls(float(value), "Double")

    @classmethod
    def date(cls, value: datetime) -> Variable:
        _require(isinstance(value, datetime), "Date", value)
        return cls(value, "Date")

    @classmethod
    def json(cls, value: Any) -> Variable:
        """A JSON document; ``value`` is any JSON-serialisable Python value."""
        try:
            json.dumps(value)
        except (TypeError, ValueError) as exc:
            raise VariableError(f"not JSON serialisable: {exc}") from exc
        return cls(value, "Json")

    @classmethod
    def object(
        cls,
        serialized: str,
        object_type_name: str,
        serialization_data_format: str = "application/json",
    ) -> Variable:
        """A serialised (Java) object: its serialised text, class name and data format."""
        _require(isinstance(serialized, str), "Object (serialised str)", serialized)
        return cls(
            serialized,
            "Object",
            {
                "objectTypeName": object_type_name,
                "serializationDataFormat": serialization_data_format,
            },
        )

    @classmethod
    def file(
        cls,
        content: bytes,
        filename: str,
        mimetype: str | None = None,
        encoding: str | None = None,
    ) -> Variable:
        _require(isinstance(content, bytes | bytearray), "File (bytes)", content)
        info: dict[str, Any] = {"filename": filename}
        if mimetype:
            info["mimeType"] = mimetype
        if encoding:
            info["encoding"] = encoding
        return cls(bytes(content), "File", info)

    @classmethod
    def binary(cls, value: bytes) -> Variable:
        _require(isinstance(value, bytes | bytearray), "Bytes", value)
        return cls(bytes(value), "Bytes")

    @classmethod
    def null(cls) -> Variable:
        return cls(None, "Null")

    @classmethod
    def of(cls, value: Any) -> Variable:
        """Variable for a plain Python value, by its natural type.

        ``bool`` -> Boolean, ``int`` -> Integer (Long when outside 32 bit), ``float`` -> Double,
        ``str`` -> String, ``datetime`` -> Date, ``bytes`` -> Bytes (:meth:`binary`),
        ``dict``/``list``/``tuple`` -> Json, ``None`` -> Null; a :class:`Variable` is returned
        unchanged. Anything else raises
        :class:`VariableError` instead of guessing: choose the type with a factory.
        """
        if isinstance(value, Variable):
            return value
        if value is None:
            return cls.null()
        if isinstance(value, bool):
            return cls.boolean(value)
        if isinstance(value, int):
            return cls.integer(value) if INT_MIN <= value <= INT_MAX else cls.long(value)
        if isinstance(value, float):
            return cls.double(value)
        if isinstance(value, str):
            return cls.string(value)
        if isinstance(value, datetime):
            return cls.date(value)
        if isinstance(value, bytes | bytearray):
            return cls.binary(bytes(value))
        if isinstance(value, Mapping | list | tuple):
            return cls.json(list(value) if isinstance(value, tuple) else value)
        raise VariableError(
            f"cannot map {type(value).__name__} to an Operaton variable type; "
            "use a Variable factory (Variable.string, .json, .object, .file, ...)"
        )

    # --- wire format -----------------------------------------------------------------------

    def to_rest(self) -> dict[str, Any]:
        """The REST representation."""
        info = dict(self.value_info)
        t = self.type
        value: Any = self.value
        if t == "Date" and isinstance(value, datetime):
            value = format_date(value)
        elif t == "Json":
            value = json.dumps(value)
        elif t in ("File", "Bytes") and isinstance(value, bytes | bytearray):
            value = base64.b64encode(value).decode("ascii")
        body: dict[str, Any] = {"value": value, "type": t}
        if info:
            body["valueInfo"] = info
        return body

    @classmethod
    def from_rest(cls, body: Mapping[str, Any]) -> Variable:
        """Decode the REST representation (as fetched with ``deserializeValues=false``)."""
        if not isinstance(body, Mapping) or "type" not in body:
            raise VariableError(f"not a typed variable: {body!r}")
        t = str(body["type"])
        value = body.get("value")
        info = dict(body.get("valueInfo") or {})
        try:
            if value is not None:
                if t == "Json" and isinstance(value, str):
                    value = json.loads(value)
                elif t == "Date" and isinstance(value, str):
                    value = parse_date(value)
                elif t in ("Bytes", "File") and isinstance(value, str):
                    value = base64.b64decode(value)
        except ValueError as exc:
            raise VariableError(f"cannot decode {t} variable: {exc}") from exc
        return cls(value, t, info)


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _require(ok: bool, expected: str, value: Any) -> None:
    if not ok:
        raise VariableError(f"expected {expected}, got {type(value).__name__}: {value!r}")


def encode_variables(variables: Mapping[str, Any] | None) -> dict[str, dict[str, Any]]:
    """REST ``variables`` map for plain values and/or :class:`Variable` objects."""
    if not variables:
        return {}
    if not isinstance(variables, Mapping):
        raise VariableError(f"variables must be a mapping, got {type(variables).__name__}")
    out: dict[str, dict[str, Any]] = {}
    for name, value in variables.items():
        if not isinstance(name, str) or not name:
            raise VariableError(f"variable names must be non-empty strings, got {name!r}")
        try:
            out[name] = Variable.of(value).to_rest()
        except VariableError as exc:
            raise VariableError(f"variable {name!r}: {exc}") from exc
    return out


def decode_variables(body: Mapping[str, Any] | None) -> dict[str, Variable]:
    """Typed variables from a REST ``variables`` map."""
    return {name: Variable.from_rest(v) for name, v in (body or {}).items()}
