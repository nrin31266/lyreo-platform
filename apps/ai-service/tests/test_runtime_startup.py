import json
import os
import subprocess
import sys

import pytest


@pytest.mark.parametrize('overrides,expected', [
    ({}, ['2', '2']),
    ({'OMP_NUM_THREADS': '3', 'MKL_NUM_THREADS': '5'}, ['3', '5']),
])
def test_thread_defaults_respect_environment_without_loading_torch(overrides, expected):
    env = os.environ.copy()
    for key in ('OMP_NUM_THREADS', 'MKL_NUM_THREADS'):
        env.pop(key, None)
    env.update(overrides)
    result = subprocess.run([
        sys.executable, '-c',
        'import json, os, sys; import app.main; '
        'print(json.dumps([os.environ["OMP_NUM_THREADS"], os.environ["MKL_NUM_THREADS"], '
        '"torch" in sys.modules]))',
    ], env=env, capture_output=True, text=True, check=True, timeout=15)
    assert json.loads(result.stdout) == [*expected, False]


def test_local_model_defaults():
    from app.config import Settings

    cfg = Settings(_env_file=None)
    assert cfg.qwen_max_inference_batch_size == 1
    assert cfg.kokoro_device == 'cpu'
