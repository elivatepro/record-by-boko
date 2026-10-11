"""Small helpers for the API's OpenAPI description."""

from pydantic.config import JsonDict

# Byte counts, milliseconds and big ids: int64, so generated clients don't use 32-bit ints.
INT64: JsonDict = {"format": "int64"}
