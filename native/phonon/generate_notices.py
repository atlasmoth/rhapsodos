import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tomllib
from html.parser import HTMLParser

TARGETS = (
    'aarch64-linux-android',
    'x86_64-linux-android',
    'aarch64-apple-ios',
    'aarch64-apple-ios-sim',
    'aarch64-apple-darwin',
)


class PlainHTML(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.hidden = 0

    def handle_starttag(self, tag, attrs):
        if tag in ('script', 'style', 'head'):
            self.hidden += 1
        if not self.hidden and tag in ('p', 'div', 'pre', 'h1', 'h2', 'h3', 'li', 'br'):
            self.parts.append('\n')

    def handle_endtag(self, tag):
        if tag in ('script', 'style', 'head'):
            self.hidden -= 1
        if not self.hidden and tag in ('p', 'div', 'pre', 'h1', 'h2', 'h3', 'li'):
            self.parts.append('\n')

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--cargo-home', type=Path, default=os.environ.get('CARGO_HOME'))
    parser.add_argument('--rust-sysroot', type=Path)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    assert (args.source / 'Cargo.lock').read_bytes() == (here / 'Cargo.lock').read_bytes()
    assert args.cargo_home, 'Set CARGO_HOME or --cargo-home'
    lock = tomllib.loads((here / 'Cargo.lock').read_text())
    packages = {(p['name'], p['version']): p for p in lock['package']}
    supplements = json.loads((here / 'license-sources.json').read_text())
    closure = {}
    for target in TARGETS:
        tree = subprocess.check_output([
            'cargo', 'tree', '--locked', '-p', 'ptts-ffi', '--target', target,
            '--edges', 'normal', '--prefix', 'none', '--format', '{p}|{l}',
        ], cwd=args.source, text=True)
        for line in tree.splitlines():
            match = re.match(r'([\w-]+) v([^ |]+)', line)
            if match:
                closure.setdefault(match.groups(), set()).add(target)
    sections = [
        'Phonon runtime third-party notices',
        'Source: https://github.com/gradium-ai/xn-ptts/tree/f07d7952db0c6127d5e4bc3f41c82b3304b6b5aa',
        'Generated from the locked normal dependency closure of ptts-ffi for Android ARM64/x86-64 and Apple ARM64.',
        'The upstream runtime is modified by Rhapsodos to reject speech that reaches its frame limit without EOS.',
        'Model weights and voice assets are separately downloaded and separately licensed.',
        '',
    ]
    inventory = []
    for (name, version), targets in sorted(closure.items()):
        locked = packages[(name, version)]
        matches = list((args.cargo_home / 'registry' / 'src').glob(f'*/{name}-{version}'))
        if 'source' in locked:
            assert len(matches) == 1, f'Missing/ambiguous package: {name} {version}'
            package = matches[0]
        else:
            package = args.source / name
        meta = tomllib.loads((package / 'Cargo.toml').read_text())['package']
        license_name = meta.get('license')
        repository = meta.get('repository')
        if isinstance(license_name, dict):
            license_name = 'MIT OR Apache-2.0'
        if isinstance(repository, dict):
            repository = 'https://github.com/gradium-ai/xn-ptts'
        candidates = sorted(p for p in package.rglob('*') if p.is_file() and re.match(
            r'^(LICENSE|LICENCE|COPYING|NOTICE|COPYRIGHT)([.-]|$)', p.name, re.I))
        files = [(str(p.relative_to(package)), p.read_bytes()) for p in candidates]
        if 'source' not in locked:
            files = [(p.name, p.read_bytes()) for p in (here / 'LICENSE-APACHE', here / 'LICENSE-MIT')]
        for item in supplements:
            if item['crate'] == name and item['version'] == version:
                data = (here / item['file']).read_bytes()
                assert digest(data) == item['sha256'], f'License checksum changed: {item}'
                files.append((item['url'], data))
        if repository == 'https://github.com/madsmtm/objc2':
            files.append(('MIT license terms; selected from the declared license alternatives', (here / 'LICENSE-MIT').read_bytes()))
        assert files, f'No license text: {name} {version}'
        record = {
            'name': name, 'version': version, 'license': license_name, 'repository': repository,
            'authors': meta.get('authors', []),
            'source': locked.get('source', 'https://github.com/gradium-ai/xn-ptts'),
            'checksum': locked.get('checksum'), 'targets': sorted(targets), 'licenseFiles': [],
        }
        sections.extend(['=' * 72, f'{name} {version}', f'License expression: {license_name}', f'Source: {repository}', f'Authors: {meta.get("authors", [])}', ''])
        for location, data in files:
            record['licenseFiles'].append({'source': location, 'sha256': digest(data)})
            sections.extend([f'--- {location} ---', data.decode('utf-8'), ''])
        inventory.append(record)
    sysroot = args.rust_sysroot or Path(subprocess.check_output(['rustc', '--print', 'sysroot'], text=True).strip())
    stdlib = (sysroot / 'share/doc/rust/COPYRIGHT-library.html').read_text()
    html = PlainHTML()
    html.feed(stdlib)
    stdtext = re.sub(r'\n{4,}', '\n\n\n', ''.join(html.parts)).strip()
    sections.extend(['=' * 72, 'Rust 1.99.1 standard library and compiler-builtins', stdtext, ''])
    (here / 'THIRD_PARTY_NOTICES.txt').write_text('\n'.join(sections).replace('\r\n', '\n').replace('\r', '\n'))
    (here / 'dependency-licenses.json').write_text(json.dumps({
        'cargoLockSHA256': digest((here / 'Cargo.lock').read_bytes()),
        'rustStandardLibraryNoticeSHA256': digest(stdlib.encode()),
        'packages': inventory,
    }, indent=2) + '\n')
    print(f'Wrote notices for {len(inventory)} packages and Rust standard library')


if __name__ == '__main__':
    main()
