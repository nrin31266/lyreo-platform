from __future__ import annotations

import sys
import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from build_grammar_toeic_release import (  # noqa: E402
    clean_html,
    detect_document_type,
    document_fragments,
    item_from_grammar,
    item_from_toeic,
    media_catalog,
    validate_item,
    visible_tokens,
)
from validate_grammar_toeic_release import table_layout, validate_membership_hierarchy  # noqa: E402


class ReleaseContentTest(unittest.TestCase):
    def test_membership_subtopic_must_belong_to_topic(self):
        subtopics = {"subtopic-a": {"topic_id": "topic-a"}}
        validate_membership_hierarchy(
            {"id": "valid", "topic_id": "topic-a", "subtopic_id": "subtopic-a"},
            subtopics,
        )
        with self.assertRaisesRegex(ValueError, "Subtopic topic mismatch: invalid"):
            validate_membership_hierarchy(
                {"id": "invalid", "topic_id": "topic-b", "subtopic_id": "subtopic-a"},
                subtopics,
            )

    def test_shared_part_five_content_uses_meaning_not_misnamed_field(self):
        source = {
            "id": "shared-1", "part": 5, "question_text": "What is -------? ",
            "option_a": "A", "option_b": " B ", "option_c": "C", "option_d": "D",
            "correct_answer": "B", "difficulty_level": 2,
            "explanation_en": "Câu hỏi là gì?\nA. A\nB. B", "explanation_vi": "Vì B đúng.",
            "dich_nghia": "Đây là gì?", "dich_nghia_dap_an": "B. B",
            "tu_vung": "what: gì", "prefer_ai_explanation": False,
        }
        grammar = {
            "id": "shared-1", "question_text": "What is -------?", "option_a": "A",
            "option_b": "B", "option_c": "C", "option_d": "D", "correct_answer": "B",
            "difficulty_level": 2, "explanation_en": source["explanation_en"],
            "explanation_vi": source["explanation_vi"], "translation_vi": "Đây là gì?",
            "answer_translation_vi": "B. B", "vocabulary": "what: gì",
            "prefer_ai_explanation": False,
        }
        toeic_item = item_from_toeic(source)
        grammar_item = item_from_grammar(grammar)
        for field in ("stem_en", "options", "correct_option", "annotations"):
            self.assertEqual(toeic_item[field], grammar_item[field])
        self.assertNotIn("explanation_en", toeic_item["annotations"])
        self.assertEqual("Câu hỏi là gì?\nA. A\nB. B", toeic_item["annotations"]["question_options_translation_vi"])

    def test_part_two_three_options_and_part_six_nullable_stem(self):
        base = {"id": "q", "part": 2, "question_text": "Where?", "passage_text": "Where? A/B/C",
                "option_a": "Here", "option_b": "There", "option_c": "Elsewhere", "option_d": None,
                "correct_answer": "C", "difficulty_level": 1}
        item = item_from_toeic(base)
        validate_item(item)
        self.assertEqual(3, len(item["options"]))
        self.assertEqual("Where? A/B/C", item["transcript_en"])
        part_six = dict(base, part=6, option_d="D", question_text="Questions 131-134 refer to the following text.")
        self.assertIsNone(item_from_toeic(part_six)["stem_en"])

    def test_sanitized_html_preserves_content_and_splits_clear_documents(self):
        source = '<div><div style="border:1px solid #999"><p>First <b>table</b></p></div><div style="border:1px solid #999"><p>Second text</p></div></div>'
        fragments, status = document_fragments(source, 7, 5)
        self.assertEqual("STRUCTURAL", status)
        self.assertEqual(2, len(fragments))
        self.assertEqual(visible_tokens(source), visible_tokens(clean_html(source)))
        unsafe = '<p onclick="alert(1)">Safe <a href="javascript:alert(1)">link</a></p><script>bad()</script>'
        output = clean_html(unsafe)
        self.assertNotIn("onclick", output)
        self.assertNotIn("href", output)
        self.assertNotIn("<script", output)

    def test_explicit_separators_split_without_losing_text(self):
        source = "<div><p>One</p><hr><p>Two</p><hr><p>Three</p></div>"
        fragments, status = document_fragments(source, 7, 5)
        self.assertEqual("STRUCTURAL", status)
        self.assertEqual(3, len(fragments))
        self.assertEqual(["One", "Two", "Three"], [part for part in ["One", "Two", "Three"] if part in clean_html(source)])

    def test_styleless_documents_and_decorative_paragraph_separator(self):
        fragments, status = document_fragments('<div class="email"><table><tr><td>First</td></tr></table></div><p>-----</p><div>Second</div>', 7, 5)
        self.assertEqual((2, "STRUCTURAL"), (len(fragments), status))
        self.assertIn("<table>", fragments[0])
        self.assertEqual(([], "UNSPLIT"), document_fragments('<p>One</p><p>Two</p>', 7, 5))

    def test_literal_email_address_survives_sanitization(self):
        source = '<p>To: Alex <alex@example.com></p><p>From: Sam &lt;sam@example.com&gt;</p>'
        html = clean_html(source)
        self.assertIn('&lt;alex@example.com&gt;', html)
        self.assertIn('&lt;sam@example.com&gt;', html)
        self.assertEqual(html, clean_html(html))

    def test_duplicated_attribute_quotes_keep_table_spans_and_text(self):
        source = '<table><tr><td colspan=""4"" style=""text-align: right"">Total</td><td>$224.00</td></tr></table>'
        html = clean_html(source)
        self.assertIn('colspan="4"', html)
        self.assertIn('text-align: right', html)
        self.assertEqual(['Total', '$', '224', '.', '00'], visible_tokens(html))
        self.assertEqual(html, clean_html(html))

    def test_structural_split_with_trailing_text_keeps_complete_group_fallback(self):
        source = '<div style="border: 1px solid black">First</div><div style="border: 1px solid black">Second</div>tail'
        self.assertEqual(([], "UNSPLIT"), document_fragments(source, 7, 5))
        self.assertIn("tail", clean_html(source))

    def test_reviewed_article_schedule_split_preserves_text_and_table(self):
        source = ('<div class="article"><div><h3>Article</h3><p>Road work</p></div>'
                  '<div><h3>Schedule</h3><table><tr><td colspan="2">Monday</td></tr></table></div></div>'
                  '<div class="email"><p>Mail</p></div>')
        repairs = {hashlib.sha256(source.encode()).hexdigest(): {"repair": "ARTICLE_SCHEDULE_WRAPPER"}}
        self.assertEqual(2, len(document_fragments(source, 7, 5)[0]))
        with patch.dict("build_grammar_toeic_release.DOCUMENT_REPAIRS", repairs):
            fragments, status = document_fragments(source, 7, 5)
            self.assertEqual("STRUCTURAL", status)
            self.assertEqual(["article", "schedule", "email"], [detect_document_type(fragment) for fragment in fragments])
            self.assertEqual(visible_tokens(source), [token for fragment in fragments for token in visible_tokens(fragment)])
            self.assertEqual(table_layout(source), [table for fragment in fragments for table in table_layout(fragment)])
            self.assertEqual(2, len(document_fragments(source.replace("Monday", "Friday"), 7, 5)[0]))

    def test_reviewed_email_dividers_keep_each_header_with_its_body(self):
        source = ('<div><p>To: A</p><p>From: B</p><p>Date: Monday</p><p>Subject: Q</p><hr>'
                  '<p>Question</p><br><p>To: B</p><p>From: A</p><p>Date: Tuesday</p>'
                  '<p>Subject: Re: Q</p><hr><p>Reply</p><br>'
                  '<div><h3>Form</h3><table><tr><td>Order</td></tr></table></div></div>')
        repairs = {hashlib.sha256(source.encode()).hexdigest(): {"repair": "EMAIL_HEADERS_AND_FORM"}}
        with patch.dict("build_grammar_toeic_release.DOCUMENT_REPAIRS", repairs):
            fragments, status = document_fragments(source, 7, 5)
        self.assertEqual("STRUCTURAL", status)
        self.assertEqual(["email", "email", "form"], [detect_document_type(fragment) for fragment in fragments])
        self.assertIn("Question", fragments[0])
        self.assertIn("Reply", fragments[1])
        self.assertEqual(visible_tokens(source), [token for fragment in fragments for token in visible_tokens(fragment)])
        self.assertEqual(table_layout(source), [table for fragment in fragments for table in table_layout(fragment)])

    def test_identical_media_files_share_one_asset_across_tests(self):
        with tempfile.TemporaryDirectory() as temp:
            raw = Path(temp) / "raw"
            output = Path(temp) / "release"
            tests = {"one": {"_year": 2019, "name": "Test 1", "media_version": 1},
                     "two": {"_year": 2020, "name": "Test 1", "media_version": 1}}
            questions = []
            for test_id, test in tests.items():
                path = raw / "mock_test_data" / "downloads" / str(test["_year"]) / test["name"] / "audio" / "1.mp3"
                path.parent.mkdir(parents=True)
                path.write_bytes(b"ID3same-audio")
                questions.append({"id": test_id + "-q", "test_id": test_id, "audio_url": "1.mp3", "image_url": None, "passage_id": None})
            assets, uses, sources = media_catalog(raw, output, tests, questions, [], [])
            self.assertEqual((1, 2, 2), (len(assets), len(uses), len(sources)))
            self.assertEqual(uses[0]["asset_id"], uses[1]["asset_id"])
            self.assertEqual(2, len(assets[0]["source_paths"]))


if __name__ == "__main__":
    unittest.main()
