from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DbResetTest(unittest.TestCase):
    def run_reset(self, *args, **settings):
        with tempfile.TemporaryDirectory() as directory:
            docker = Path(directory) / 'docker'
            docker.write_text('''#!/usr/bin/env python3
import os, sys
args = ' '.join(sys.argv)
if 'printf' in args:
    print(os.environ.get('TEST_DB', 'lyreo_dev') + '\\nlyreo\\nlyreo_keycloak')
elif 'pg_stat_activity' in args:
    print(os.environ.get('TEST_CONNECTIONS', '0'))
elif 'affected_table' in args:
    print('lesson | 12')
else:
    print('MUTATION_ATTEMPT', args)
    sys.exit(90)
''')
            docker.chmod(0o755)
            env = dict(os.environ, PATH=f'{directory}:{os.environ["PATH"]}', APPLY='0', CONFIRM='')
            env.update(settings)
            return subprocess.run([str(ROOT / 'scripts/db-reset.sh'), *args], env=env, text=True, capture_output=True)

    def test_default_is_preview_for_both_scopes(self):
        for args in [(), ('--database',)]:
            result = self.run_reset(*args)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('Preview only', result.stdout)
            self.assertNotIn('MUTATION_ATTEMPT', result.stdout)

    def test_wrong_confirmation_or_active_core_never_mutates(self):
        for settings in [dict(APPLY='1', CONFIRM='other'), dict(APPLY='1', CONFIRM='lyreo_dev', TEST_CONNECTIONS='1')]:
            result = self.run_reset(**settings)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('MUTATION_ATTEMPT', result.stdout)

    def test_refuses_system_keycloak_and_non_dev_targets(self):
        for database in ['postgres', 'lyreo_keycloak', 'lyreo_prod']:
            result = self.run_reset(TEST_DB=database)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('MUTATION_ATTEMPT', result.stdout)

    def test_volume_deletion_requires_explicit_confirmation(self):
        result = subprocess.run(['make', 'down-v'], cwd=ROOT, env=dict(os.environ, CONFIRM=''), text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Refusing volume deletion', result.stderr)
        self.assertNotIn('down -v', result.stdout)
