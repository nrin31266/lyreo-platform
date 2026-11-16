import runpy
import unittest
from pathlib import Path

measure = runpy.run_path(str(Path(__file__).resolve().parents[2] / 'tools/research/benchmark_lesson_timing.py'))['measure']

class TimingBenchmarkTest(unittest.TestCase):
    def test_no_reference_never_certifies_quality(self):
        self.assertFalse(measure([{'word': 'Hello', 'start_ms': 0, 'end_ms': 300}], 'Hello', 1000)['accepted'])

    def test_visible_boundary_error_vetoes_good_median(self):
        words = [{'word': str(i), 'start_ms': i*200, 'end_ms': i*200+100} for i in range(10)]
        reference = {'reviewer': 'human', 'checked_at': '2026-10-26', 'karaoke_passed': True,
                     'words': [dict(w, index=i) for i, w in enumerate(words)]}
        self.assertTrue(measure(words, ' '.join(str(i) for i in range(10)), 3000, reference)['accepted'])
        reference['words'][-1]['end_ms'] += 500
        self.assertFalse(measure(words, ' '.join(str(i) for i in range(10)), 3000, reference)['accepted'])
