import unittest
from .proofread import apply_rules, apply_edits

class Rules(unittest.TestCase):
    def test_rules(self):
        self.assertEqual(apply_rules("the novel--the ball"), "the novel—the ball")
        self.assertEqual(apply_rules("a ---- rule"), "a ---- rule")
        self.assertEqual(apply_rules("he said “ Creator,” and ‘ whispering"), "he said “Creator,” and ‘whispering")
        self.assertEqual(apply_rules("conversational power^ The elder"), "conversational power The elder")
        self.assertEqual(apply_rules("ﬁnd the \x03"), "find the ")
        self.assertEqual(apply_rules("2^3 stays"), "2^3 stays")
        self.assertEqual(apply_rules("he wrote:--'I never'"), "he wrote:—'I never'")
        self.assertEqual(apply_rules('of " the association of ideas " from which'), 'of "the association of ideas" from which')
        self.assertEqual(apply_rules('" For the pangs," she says, " there is none."'), '"For the pangs," she says, "there is none."')
        self.assertEqual(apply_rules('" A quote that runs on'), '" A quote that runs on')
        self.assertEqual(apply_rules("a ---- rule and x -- y"), "a ---- rule and x—y")

class Edits(unittest.TestCase):
    def pack(self):
        return {"blocks": [{"id": "b", "paragraphs": ["Eosamond came.", "12", "INTRO", "half a sen", "tence here."]}]}

    def test_ops_apply_from_the_end(self):
        pack = self.pack()
        edits = [{"block": "b", "para": 0, "op": "replace", "find": "Eosamond", "replace": "Rosamond"},
                 {"block": "b", "para": 1, "op": "delete"},
                 {"block": "b", "para": 2, "op": "heading"},
                 {"block": "b", "para": 4, "op": "join"},
                 {"block": "b", "para": 0, "op": "replace", "find": "came", "replace": "a whole new sentence"},
                 {"block": "x", "para": 0, "op": "delete"}]
        applied = apply_edits(pack, edits)
        self.assertEqual(pack["blocks"][0]["paragraphs"], ["Rosamond came.", "## INTRO", "half a sen tence here."])
        self.assertEqual(len(applied), 4)

if __name__ == "__main__": unittest.main()
