"""Claude calls through `claude -p` on the Claude subscription, with JSON replies."""

import json
import os
import re
import subprocess
import tempfile
import threading
import time
from pathlib import Path

MODEL = "claude-opus-5-5"
FAST_MODEL = "claude-haiku-5-5"
ROOT = Path(__file__).resolve().parent.parent
SYSTEM = "Return only valid JSON matching the requested shape. No Markdown fences or commentary."
# An empty folder to run in, so no project CLAUDE.md or settings reach the call.
_CWD = tempfile.mkdtemp(prefix="pack-claude-")


class Usage:
    """Running totals; `local.cost` is per thread so parallel runs can report their own spend.
    Costs are what Claude Code reports at list price; the subscription pays for the calls."""
    cost = 0.0
    prompt_tokens = 0
    completion_tokens = 0
    local = threading.local()


def select_model(*, fast: bool = False, parallel_calls: int = 1) -> str:
    return FAST_MODEL if fast or parallel_calls > 20 else MODEL


def command(model: str, effort: str) -> list[str]:
    cmd = ["claude", "-p", "--model", model, "--output-format", "json", "--tools", "",
           "--system-prompt", SYSTEM, "--no-session-persistence"]
    if model != FAST_MODEL:
        cmd += ["--effort", effort]
    return cmd


def parse(stdout: str) -> tuple[dict, dict]:
    """Return (reply JSON, Claude Code's result record)."""
    record = json.loads(stdout)
    if record.get("is_error") or record.get("subtype") != "success":
        raise RuntimeError(f"Claude call failed: {record.get('subtype')}: {str(record.get('result'))[:300]}")
    content = re.sub(r"^```(?:json)?\s*|\s*```$", "", (record.get("result") or "").strip())
    return json.loads(content), record


def ask_json(prompt: str, max_tokens: int = 60000, effort: str = "low", *,
             fast: bool = False, parallel_calls: int = 1) -> dict:
    """Opus by default; Haiku for quick tasks or jobs with over 20 parallel calls.
    `max_tokens` is kept for callers; Claude Code sets its own output limit."""
    model = select_model(fast=fast, parallel_calls=parallel_calls)
    env = {k: v for k, v in os.environ.items() if k != "ANTHROPIC_API_KEY"}  # bill the subscription
    last_error: Exception | None = None
    for attempt in range(4):
        try:
            proc = subprocess.run(command(model, effort), input=prompt, capture_output=True, text=True,
                                  timeout=1800, cwd=_CWD, env=env)
            if proc.returncode and not proc.stdout.strip():
                raise RuntimeError(f"claude exited {proc.returncode}: {proc.stderr[-300:]}")
            reply, record = parse(proc.stdout)
            usage = record.get("usage") or {}
            cost = record.get("total_cost_usd") or 0.0
            Usage.cost += cost
            Usage.local.cost = getattr(Usage.local, "cost", 0.0) + cost
            Usage.prompt_tokens += (usage.get("input_tokens") or 0) + (usage.get("cache_read_input_tokens") or 0)
            Usage.completion_tokens += usage.get("output_tokens") or 0
            return reply
        except (RuntimeError, json.JSONDecodeError, subprocess.TimeoutExpired) as err:
            last_error = err
        if attempt < 3:
            time.sleep(5 * (attempt + 1))
    raise RuntimeError(f"Claude call failed after retries: {last_error}")
