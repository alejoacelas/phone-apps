import unittest
from unittest.mock import patch
import httpx
from . import llm

class ClaudeCalls(unittest.TestCase):
    def test_routing_boundary(self):
        self.assertEqual(llm.select_model(parallel_calls=20), llm.MODEL)
        self.assertEqual(llm.select_model(parallel_calls=21), llm.FAST_MODEL)
        self.assertEqual(llm.select_model(fast=True), llm.FAST_MODEL)

    @patch.object(llm, '_env', return_value='test-only')
    def test_only_text_is_parsed_and_haiku_disables_thinking(self, _):
        response = httpx.Response(200, request=httpx.Request('POST', 'https://api.anthropic.com/v1/messages'), json={
            'content': [{'type':'thinking','thinking':'not json'}, {'type':'text','text':'{"ok":true}'}],
            'stop_reason':'end_turn', 'usage':{'input_tokens':100,'output_tokens':10}})
        with patch.object(llm.httpx, 'post', return_value=response) as post:
            self.assertEqual(llm.ask_json('test', fast=True), {'ok': True})
            self.assertEqual(post.call_args.kwargs['json']['thinking'], {'type':'disabled'})
            self.assertNotIn('Authorization',post.call_args.kwargs['headers'])

    @patch.object(llm, '_env', return_value='test-only')
    def test_truncation_is_not_accepted_as_success(self, _):
        response = httpx.Response(200, request=httpx.Request('POST', 'https://api.anthropic.com/v1/messages'), json={
            'content':[{'type':'text','text':'{}'}], 'stop_reason':'max_tokens'})
        with patch.object(llm.httpx, 'post', return_value=response):
            with self.assertRaisesRegex(RuntimeError, 'incomplete'):
                llm.ask_json('test')

    def test_long_haiku_prompt_price(self):
        self.assertAlmostEqual(llm.usage_cost({'input_tokens':100001,'output_tokens':1000},llm.FAST_MODEL),.0525005)

if __name__ == '__main__': unittest.main()
