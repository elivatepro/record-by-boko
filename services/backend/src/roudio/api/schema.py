"""Small helpers for the API's OpenAPI description."""

from typing import Annotated

from fastapi import Path
from pydantic.config import JsonDict

# Byte counts, milliseconds and big ids: int64, so generated clients don't use 32-bit ints.
INT64: JsonDict = {"format": "int64"}

# A numeric id in a URL path, int64 like the ids in the models.
Id64 = Annotated[int, Path(ge=1, json_schema_extra=INT64)]
