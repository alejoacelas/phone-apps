"""Direct Claude API calls with JSON replies and estimated list-price usage."""

import json
import os
import re
import threading
import time
from pathlib import Path

import httpx

MODEL = "claude-opus-5-5"
FAST_MODEL = "claude-haiku-5-5"
ROOT = Path(__file__).resolve().parent.parent


def _env(name: str) -> str:
    if os.environ.get(name):
        return os.environ[name]
    for line in (ROOT / ".env").read_text().splitlines():
        key, _, value = line.partition("=")
        if key.strip() == name:
            return value.strip()
    raise RuntimeError(f"{name} missing from environment and .env (see README)")


class Usage:
    """Running totals; `local.cost` is per thread so parallel runs can report their own spend."""
    cost = 0.0
    prompt_tokens = 0
    completion_tokens = 0
    local = threading.local()


def select_model(*, fast: bool = False, parallel_calls: int = 1) -> str:
    return FAST_MODEL if fast or parallel_calls > 20 else MODEL


def usage_cost(usage: dict, model: str) -> float:
    """List-price estimate before monthly credits; token counts come from Claude."""
    input_tokens = usage.get("input_tokens", 0)
    output_tokens = usage.get("output_tokens", 0)
    input_rate, output_rate = (4.0, 20.0)
    if model == FAST_MODEL:
        input_rate, output_rate = (0.1, 0.5) if input_tokens <= 100_000 else (0.5, 2.5)
    return (input_tokens * input_rate + output_tokens * output_rate) / 1_000_000


def ask_json(prompt: str, max_tokens: int = 60000, effort: str = "low", *,
             fast: bool = False, parallel_calls: int = 1) -> dict:
    """Opus by default; Haiku for quick tasks or jobs with over 20 parallel calls."""
    model = select_model(fast=fast, parallel_calls=parallel_calls)
    body = {
        "model": model,
        "system": "Return only valid JSON matching the requested shape. No Markdown fences or commentary.",
        "messages": [{"role": "user", "content": prompt}],
        "output_config": {"effort": effort},
        "max_tokens": max_tokens,
    }
    if model == FAST_MODEL:
        body["thinking"] = {"type": "disabled"}
    headers = {"x-api-key": _env("ANTHROPIC_API_KEY"), "anthropic-version": "2023-06-01"}
    last_error: Exception | None = None
    for attempt in range(4):
        try:
            resp = httpx.post("https://api.anthropic.com/v1/messages", json=body,
                              headers=headers, timeout=600)
            resp.raise_for_status()
            data = resp.json()
            usage = data.get("usage") or {}
            cost = usage_cost(usage, model)
            Usage.cost += cost
            Usage.local.cost = getattr(Usage.local, "cost", 0.0) + cost
            Usage.prompt_tokens += usage.get("input_tokens") or 0
            Usage.completion_tokens += usage.get("output_tokens") or 0
            if data.get("stop_reason") != "end_turn":
                raise RuntimeError(f"Claude reply incomplete: {data.get('stop_reason')}")
            content = "".join(b["text"] for b in data["content"] if b.get("type") == "text")
            content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content.strip())
            return json.loads(content)
        except httpx.HTTPStatusError as err:
            if err.response.status_code not in (408, 429, 500, 502, 503, 504, 529):
                raise
            last_error = err
        except (httpx.TransportError, json.JSONDecodeError, KeyError) as err:
            last_error = err
        if attempt < 3:
            time.sleep(5 * (attempt + 1))
    raise RuntimeError(f"Claude call failed after retries: {last_error}")
