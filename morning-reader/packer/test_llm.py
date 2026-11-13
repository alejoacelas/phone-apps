import json
import unittest
from . import llm

class ClaudeCalls(unittest.TestCase):
    def test_routing_boundary(self):
        self.assertEqual(llm.select_model(parallel_calls=20), llm.MODEL)
        self.assertEqual(llm.select_model(parallel_calls=21), llm.FAST_MODEL)
        self.assertEqual(llm.select_model(fast=True), llm.FAST_MODEL)

    def test_haiku_gets_no_effort_and_no_tools(self):
        cmd = llm.command(llm.FAST_MODEL, "low")
        self.assertNotIn("--effort", cmd)
        self.assertEqual(cmd[cmd.index("--tools") + 1], "")
        self.assertIn("--effort", llm.command(llm.MODEL, "low"))

    def test_fenced_reply_is_parsed(self):
        out = json.dumps({"subtype": "success", "is_error": False, "result": '```json\n{"ok": true}\n```'})
        self.assertEqual(llm.parse(out)[0], {"ok": True})

    def test_error_record_is_not_accepted(self):
        out = json.dumps({"subtype": "error_max_turns", "is_error": True, "result": ""})
        with self.assertRaisesRegex(RuntimeError, "failed"):
            llm.parse(out)

if __name__ == '__main__': unittest.main()
