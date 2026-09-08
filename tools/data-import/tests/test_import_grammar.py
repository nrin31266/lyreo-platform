from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
IMPORT_ROOT = HERE.parent
sys.path.insert(0, str(IMPORT_ROOT))

from import_grammar import explanation_policy, question_external_id  # noqa: E402


def test_dautoeic_flat_question_identity_and_source_explanation_policy():
    row = {
        "id": "bank:set-id:question-id",
        "question_id": "53fcfa34-1c21-4350-b0fc-9e3d4139dd6f",
        "question_text": "The lecture will take place at 6:00 P.M, ------- which attendees may ask questions.",
        "correct_answer": "B",
        "explanation_vi": "Cấu trúc after which...",
        "translation_vi": "Bài giảng sẽ diễn ra...",
        "vocabulary": "take place (v): diễn ra\n\nattendee (n): người tham dự",
        "difficulty_level": 3,
        "prefer_ai_explanation": False,
        "topic_id": None,
        "subtopic_id": None,
    }

    assert question_external_id(row) == row["question_id"]
    assert explanation_policy(row) == "SOURCE"
    # Missing classification is valid input; classification/enrichment can happen later.
    assert row["topic_id"] is None
    assert row["subtopic_id"] is None


def test_ai_preferred_explanation_is_explicit_not_inferred_from_missing_source_text():
    row = {
        "id": "q2",
        "explanation_vi": None,
        "prefer_ai_explanation": True,
    }
    assert question_external_id(row) == "q2"
    assert explanation_policy(row) == "AI_PREFERRED"
