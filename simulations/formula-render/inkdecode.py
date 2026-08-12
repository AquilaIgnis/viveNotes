"""Decode androidx.ink stroke-input blobs into page-unit polylines.

The blob is gzip over a protobuf `CodedStrokeInputBatch`:

    field 1..n : CodedNumericRun   (1 = x, 2 = y, 3 = elapsed seconds, then pressure/tilt/...)

    CodedNumericRun:
        field 1 : packed varints, zigzag deltas, cumulatively summed
        field 2 : float scale
        field 3 : float offset (absent = 0)

    value[i] = offset + scale * sum(deltas[0..i])
"""

import gzip
import struct
from dataclasses import dataclass, field as dataclass_field
from typing import Any


def _read_varint(data: bytes, index: int) -> tuple[int, int]:
    result: int = 0
    shift: int = 0
    while True:
        byte: int = data[index]
        index += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, index
        shift += 7


def _zigzag(value: int) -> int:
    return (value >> 1) ^ -(value & 1)


def _parse(data: bytes) -> dict[int, list[Any]]:
    fields: dict[int, list[Any]] = {}
    index: int = 0
    while index < len(data):
        key, index = _read_varint(data, index)
        number: int = key >> 3
        wire: int = key & 7
        if wire == 0:
            value, index = _read_varint(data, index)
        elif wire == 5:
            value = struct.unpack("<f", data[index : index + 4])[0]
            index += 4
        elif wire == 1:
            value = struct.unpack("<d", data[index : index + 8])[0]
            index += 8
        elif wire == 2:
            length, index = _read_varint(data, index)
            value = data[index : index + length]
            index += length
        else:
            raise ValueError(f"unsupported wire type {wire}")
        fields.setdefault(number, []).append(value)
    return fields


def _numeric_run(chunk: bytes, zigzag: bool) -> list[float]:
    parsed: dict[int, list[Any]] = _parse(chunk)
    packed: bytes = parsed.get(1, [b""])[0]
    scale: float = float(parsed.get(2, [1.0])[0])
    offset: float = float(parsed.get(3, [0.0])[0])

    deltas: list[int] = []
    position: int = 0
    while position < len(packed):
        raw, position = _read_varint(packed, position)
        deltas.append(_zigzag(raw) if zigzag else raw)

    values: list[float] = []
    total: int = 0
    for delta in deltas:
        total += delta
        values.append(offset + scale * total)
    return values


@dataclass
class DecodedStroke:
    """One stroke as page-unit samples, with the row metadata that styled it."""

    id: str
    seq: int
    brush_family: str
    size_dp: float
    stabilization: int
    xs: list[float] = dataclass_field(default_factory=list)
    ys: list[float] = dataclass_field(default_factory=list)
    times: list[float] = dataclass_field(default_factory=list)
    pressures: list[float] = dataclass_field(default_factory=list)

    @property
    def points(self) -> list[tuple[float, float]]:
        return list(zip(self.xs, self.ys))


def decode_points(blob: bytes, zigzag: bool = True) -> dict[int, list[float]]:
    raw: bytes = gzip.decompress(blob)
    runs: dict[int, list[Any]] = _parse(raw)
    decoded: dict[int, list[float]] = {}
    for number, values in runs.items():
        if isinstance(values[0], bytes):
            decoded[number] = _numeric_run(values[0], zigzag)
    return decoded
