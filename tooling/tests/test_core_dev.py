from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

from scripts import core_dev


class CoreDevTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.module = self.root / 'apps/core-service'
        self.module.mkdir(parents=True)
        (self.root / 'pom.xml').write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<modules><module>apps/core-service</module><module>modules/example</module></modules>'
            '</project>'
        )
        (self.module / 'pom.xml').write_text('<project/>')
        (self.root / 'modules/example').mkdir(parents=True)
        (self.root / 'modules/example/pom.xml').write_text('<project/>')
        self.source = self.module / 'src/main/java/Example.java'
        self.source.parent.mkdir(parents=True)
        self.source.write_text('class Example {}')

    def test_only_saved_main_sources_and_resources_are_watched(self):
        modules = core_dev.modules(self.root)
        self.assertEqual([self.module, self.root / 'modules/example'], modules)
        before = core_dev.sources(modules)
        for name in ['target/classes/Example.class', 'src/test/java/ExampleTest.java',
                     'src/main/java/.Example.java.swp', 'src/main/resources/application.yml~']:
            path = self.module / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('ignored')
        self.assertEqual(before, core_dev.sources(modules))
        # An editor buffer has no effect until its content is written to the file.
        buffer = 'class Example { int saved; }'
        self.assertEqual(before, core_dev.sources(modules))
        self.source.write_text(buffer)
        self.assertNotEqual(before, core_dev.sources(modules))
        resource = self.root / 'modules/example/src/main/resources/example.properties'
        resource.parent.mkdir(parents=True)
        resource.write_text('example=true')
        self.assertIn(resource, core_dev.sources(modules))

    def test_saves_are_batched_and_saves_during_build_remain_pending(self):
        changes = core_dev.Changes({self.source: (1, 1)})
        changes.update({self.source: (2, 1)}, 10)
        self.assertFalse(changes.ready(10.9))
        other = self.source.with_name('Other.java')
        changes.update({self.source: (2, 1), other: (3, 1)}, 10.5)
        self.assertFalse(changes.ready(11.4))
        self.assertTrue(changes.ready(11.5))
        self.assertEqual({self.source, other}, changes.take())
        self.assertFalse(changes.ready(12))
        changes.update({self.source: (4, 1)}, 12)
        self.assertEqual({self.source, other}, changes.take())

    def test_deleted_source_cleans_only_its_module_output(self):
        modules = core_dev.modules(self.root)
        for module in modules:
            output = module / 'target/classes/Old.class'
            output.parent.mkdir(parents=True)
            output.write_text('stale')
        self.source.unlink()
        core_dev.clean_deleted(modules, {self.source})
        self.assertFalse((self.module / 'target/classes').exists())
        self.assertTrue((modules[1] / 'target/classes/Old.class').exists())

    def test_pom_and_env_changes_are_detected_without_watching_build_output(self):
        modules = core_dev.modules(self.root)
        before = core_dev.configuration(self.root, modules)
        (self.module / '.env').write_text('SERVER_PORT=8080')
        self.assertNotEqual(before, core_dev.configuration(self.root, modules))
        before = core_dev.configuration(self.root, modules)
        (modules[1] / 'pom.xml').write_text('<project><description>changed</description></project>')
        self.assertNotEqual(before, core_dev.configuration(self.root, modules))

    def test_commands_use_classes_and_never_install_or_compile_tests(self):
        runner = core_dev.Runner(self.root)
        command = runner.build_command()
        self.assertIn('compile', command)
        self.assertIn('-Dmaven.test.skip=true', command)
        self.assertNotIn('install', command)
        self.assertNotIn('package', command)
        command = runner.app_command()
        self.assertIn('-Pcore-dev', command)
        self.assertIn('-Dspring-boot.excludeGroupIds=com.lyreo', command)
        classpath = next(arg for arg in command if arg.startswith('-Dspring-boot.run.additional-classpath-elements='))
        self.assertIn(str(self.root / 'modules/example/target/classes'), classpath)
        self.assertIn(str(runner.trigger.parent), classpath)
        self.assertNotIn('.jar', classpath)

    def test_failed_build_does_not_trigger_restart_and_next_save_recovers(self):
        runner = core_dev.Runner(self.root)
        runner.trigger.parent.mkdir(parents=True)
        runner.trigger.write_text('initial')
        before = runner.trigger.stat().st_mtime_ns
        changes = core_dev.Changes(core_dev.sources(runner.modules))
        self.source.write_text('broken Java')
        changes.update(core_dev.sources(runner.modules), 1)
        with patch.object(runner, 'compile', return_value=False):
            runner.rebuild(changes)
        self.assertEqual(before, runner.trigger.stat().st_mtime_ns)
        self.assertFalse(changes.pending)
        self.source.write_text('class Example { int fixed; }')
        changes.update(core_dev.sources(runner.modules), time.monotonic())
        with patch.object(runner, 'compile', return_value=True):
            runner.rebuild(changes)
        self.assertNotEqual(before, runner.trigger.stat().st_mtime_ns)

    def test_new_save_during_build_delays_restart_until_followup_build(self):
        runner = core_dev.Runner(self.root)
        changes = core_dev.Changes(core_dev.sources(runner.modules))
        self.source.write_text('class Example { int first; }')
        changes.update(core_dev.sources(runner.modules), 1)
        def save_during_build(_changed):
            self.source.write_text('class Example { int second; }')
            return True
        with patch.object(runner, 'compile', side_effect=save_during_build):
            runner.rebuild(changes)
        self.assertFalse(runner.trigger.exists())
        self.assertTrue(changes.pending)
        with patch.object(runner, 'compile', return_value=True):
            runner.rebuild(changes)
        self.assertTrue(runner.trigger.exists())

    def test_configuration_changes_stop_before_triggering_restart(self):
        runner = core_dev.Runner(self.root)
        changes = core_dev.Changes(core_dev.sources(runner.modules))
        self.source.write_text('class Example { int first; }')
        changes.update(core_dev.sources(runner.modules), 1)
        def change_configuration(_changed):
            (self.module / '.env').write_text('CHANGED=true')
            return True
        with patch.object(runner, 'compile', side_effect=change_configuration):
            with self.assertRaisesRegex(RuntimeError, 'restart make core'):
                runner.rebuild(changes)
        self.assertFalse(runner.trigger.exists())

    def test_stop_terminates_the_whole_child_process_group(self):
        child_pid = self.root / 'child.pid'
        process = subprocess.Popen(
            ['sh', '-c', 'sleep 120 & echo $! > "$1"; wait', 'sh', str(child_pid)],
            start_new_session=True,
        )
        self.addCleanup(core_dev.stop, process)
        deadline = time.monotonic() + 5
        while not child_pid.exists() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(child_pid.exists())
        pid = int(child_pid.read_text())
        core_dev.stop(process)
        self.assertIsNotNone(process.poll())
        status = Path(f'/proc/{pid}/stat')
        self.assertTrue(not status.exists() or status.read_text().split()[2] == 'Z')
