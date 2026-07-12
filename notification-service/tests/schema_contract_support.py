"""Shared boilerplate for this service's test_contract_*.py modules --
deduplicates schema-loading, not a new abstraction: every contract test
still owns its own assertion and its own example payload.
"""
import json
import os


def load_schema(file_name: str) -> dict:
    for candidate in (
        os.path.join("..", "schemas", "json", file_name),
        os.path.join("schemas", "json", file_name),
    ):
        if os.path.exists(candidate):
            with open(candidate, "r", encoding="utf-8") as f:
                return json.load(f)
    raise FileNotFoundError(
        f"shared schema {file_name} not found relative to notification-service or repo root"
    )
