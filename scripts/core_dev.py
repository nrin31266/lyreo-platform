#!/usr/bin/env python3
"""Run Core with DevTools; rebuild only after the editor saves files."""
from pathlib import Path
import os
import shutil
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def modules(root):
    result = []
    def visit(directory):
        pom = ET.parse(directory / 'pom.xml').getroot()
        if pom.findtext('{*}packaging') != 'pom' and directory != root:
            result.append(directory)
        for child in pom.findall('{*}modules/{*}module'):
            visit(directory / child.text)
    visit(root)
    return result


def signatures(paths):
    result = {}
    for path in paths:
        try:
            stat = path.stat()
            result[path] = (stat.st_mtime_ns, stat.st_size)
        except FileNotFoundError:
            pass  # Editors can replace files atomically during a scan.
    return result


def sources(projects):
    return signatures(path for project in projects for directory in ['java', 'resources']
                      for path in (project / 'src/main' / directory).rglob('*')
                      if path.is_file() and (directory != 'java' or path.suffix == '.java')
                      and not path.name.endswith(('~', '.swp', '.swo')))


def configuration(root, projects):
    return signatures([root / 'pom.xml', root / '.mvn/maven.config', root / '.mvn/jvm.config',
                       root / 'apps/core-service/.env', *(p / 'pom.xml' for p in projects)])


class Changes:
    def __init__(self, snapshot):
        self.observed = snapshot
        self.pending = set()
        self.changed_at = 0

    def update(self, snapshot, now):
        changed = {p for p in self.observed.keys() | snapshot.keys()
                   if self.observed.get(p) != snapshot.get(p)}
        if changed:
            self.pending.update(changed)
            self.changed_at = now
            self.observed = snapshot

    def ready(self, now):
        return bool(self.pending) and now - self.changed_at >= 1

    def take(self):
        pending, self.pending = self.pending, set()
        return pending


def clean_deleted(projects, changed):
    for project in projects:
        if any(path.is_relative_to(project / 'src/main') and not path.exists() for path in changed):
            try:
                shutil.rmtree(project / 'target/classes')
            except FileNotFoundError:
                pass


def stop(process):
    if process is None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()


def log(message):
    print(f'[core-dev] {message}', flush=True)


class Runner:
    def __init__(self, root):
        self.root = root
        self.modules = modules(root)
        self.core = root / 'apps/core-service'
        self.trigger = self.core / 'target/core-dev/.reloadtrigger'
        self.config = configuration(root, self.modules)
        self.app = self.compiler = None

    def build_command(self):
        return [str(self.root / 'mvnw'), '-q', '-Pcore-dev', '-pl', 'apps/core-service', '-am',
                '-Dmaven.test.skip=true', 'compile']

    def app_command(self):
        classpath = [str(p / 'target/classes') for p in self.modules if p != self.core]
        classpath.append(str(self.trigger.parent))
        return [str(self.root / 'mvnw'), '-q', '-Pcore-dev', '-Dmaven.test.skip=true',
                '-Dspring-boot.excludeGroupIds=com.lyreo',
                '-Dspring-boot.run.additional-classpath-elements=' + ','.join(classpath),
                '-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.trigger-file=.reloadtrigger '
                '-Dspring.devtools.livereload.enabled=false', 'spring-boot:run']

    def check_configuration(self):
        if configuration(self.root, self.modules) != self.config:
            raise RuntimeError('POM/.env/Maven configuration changed; restart make core. '
                               'Run make deps-java first if dependencies changed.')

    def compile(self, changed):
        clean_deleted(self.modules, changed)
        log('Compiling main sources (no package/install or tests)...')
        started = time.monotonic()
        self.compiler = subprocess.Popen(self.build_command(), cwd=self.root, start_new_session=True)
        success = self.compiler.wait() == 0
        self.compiler = None
        log(f'Compile {"succeeded" if success else "failed"} in {time.monotonic() - started:.1f}s.')
        return success

    def rebuild(self, changes):
        success = self.compile(changes.take())
        self.check_configuration()
        changes.update(sources(self.modules), time.monotonic())
        if success and not changes.pending:
            self.trigger.parent.mkdir(parents=True, exist_ok=True)
            self.trigger.write_text(str(time.time_ns()))
            log('Build complete; DevTools reload trigger updated.')
        elif not success:
            log('No restart requested. Save a source file to retry.')
        else:
            log('More files saved during build; waiting for the next batch.')
        return success

    def run(self):
        changes = Changes(sources(self.modules))
        while True:
            if not self.rebuild(changes):
                log('For missing internal Maven artifacts, run make deps-java, then make core.')
                return 1
            if not changes.pending:
                break
            time.sleep(1)
        self.app = subprocess.Popen(self.app_command(), cwd=self.core, start_new_session=True)
        log('Watching saved src/main files. Ctrl+C stops Core and the watcher.')
        while self.app.poll() is None:
            self.check_configuration()
            changes.update(sources(self.modules), time.monotonic())
            if changes.ready(time.monotonic()):
                self.rebuild(changes)
            time.sleep(0.5)
        return self.app.returncode

    def close(self):
        stop(self.compiler)
        stop(self.app)


def main():
    runner = Runner(Path(__file__).resolve().parents[1])
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    signal.signal(signal.SIGHUP, lambda *_: sys.exit(129))  # Closing the terminal must stop the JVM too.
    try:
        return runner.run()
    except KeyboardInterrupt:
        return 130
    except (RuntimeError, OSError) as error:
        log(str(error))
        return 1
    finally:
        runner.close()


if __name__ == '__main__':
    sys.exit(main())
