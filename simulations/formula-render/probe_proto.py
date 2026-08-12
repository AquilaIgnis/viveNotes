"""Inspect the androidx.ink stroke-input blob: gunzip, then walk the protobuf wire format."""

import gzip
import sqlite3
import struct
from typing import Any

DB: str = "live.db"
STROKE_ID: str = "019fedc7-179b-7485-a2de-0517d2ca17d5"


def read_varint(data: bytes, index: int) -> tuple[int, int]:
    result: int = 0
    shift: int = 0
    while True:
        byte: int = data[index]
        index += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, index
        shift += 7


def zigzag(value: int) -> int:
    return (value >> 1) ^ -(value & 1)


def parse(data: bytes, depth: int = 0) -> list[tuple[int, int, Any]]:
    fields: list[tuple[int, int, Any]] = []
    index: int = 0
    while index < len(data):
        key, index = read_varint(data, index)
        field: int = key >> 3
        wire: int = key & 7
        if wire == 0:
            value, index = read_varint(data, index)
            fields.append((field, wire, value))
        elif wire == 5:
            value = struct.unpack("<f", data[index : index + 4])[0]
            index += 4
            fields.append((field, wire, value))
        elif wire == 1:
            value = struct.unpack("<d", data[index : index + 8])[0]
            index += 8
            fields.append((field, wire, value))
        elif wire == 2:
            length, index = read_varint(data, index)
            chunk: bytes = data[index : index + length]
            index += length
            fields.append((field, wire, chunk))
        else:
            raise ValueError(f"unsupported wire type {wire} for field {field}")
    return fields


def show(data: bytes, indent: str = "") -> None:
    for field, wire, value in parse(data):
        if wire == 2:
            assert isinstance(value, bytes)
            print(f"{indent}field {field} (len {len(value)}) bytes={value[:24].hex()}")
            try:
                nested = parse(value)
            except Exception:
                nested = []
            if nested and all(f > 0 for f, _, _ in nested):
                print(f"{indent}  -> as message:")
                show(value, indent + "    ")
            packed: list[int] = []
            try:
                position: int = 0
                while position < len(value):
                    item, position = read_varint(value, position)
                    packed.append(item)
            except Exception:
                packed = []
            if packed:
                print(f"{indent}  -> as packed varints ({len(packed)}): {packed[:16]}")
                print(f"{indent}  -> as zigzag           : {[zigzag(v) for v in packed[:16]]}")
        else:
            print(f"{indent}field {field} wire {wire} = {value}")


def main() -> None:
    connection = sqlite3.connect(DB)
    blob: bytes = connection.execute(
        "select points from ink_strokes where id = ?", (STROKE_ID,)
    ).fetchone()[0]
    connection.close()
    raw: bytes = gzip.decompress(blob)
    print("inflated bytes:", len(raw))
    show(raw)


main()
