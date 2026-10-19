#!/usr/bin/env python3
"""Create and verify a test-only synthetic Files provider configuration."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib

KEYS = ('UIFileSharingEnabled', 'LSSupportsOpeningDocumentsInPlace')


def read(path):
    if path.is_symlink():
        raise ValueError('Symlink plist is not admitted')
    return path.read_bytes()


def prepare(source, output, receipt):
    if len({source.resolve(), output.resolve(), receipt.resolve()}) != 3 or output.exists() or receipt.exists():
        raise ValueError('Test output must be new and separate from source')
    raw = read(source)
    data = plistlib.loads(raw)
    if any(data.get(key, False) is not False for key in KEYS):
        raise ValueError('Product source must keep Documents sharing disabled')
    test = dict(data, **{key: True for key in KEYS})
    encoded = plistlib.dumps(test)
    with output.open('xb') as stream:
        stream.write(encoded)
    with receipt.open('x') as stream:
        stream.write(json.dumps({'source_sha256': hashlib.sha256(raw).hexdigest(),
                                 'test_sha256': hashlib.sha256(encoded).hexdigest()}))


def verify(source, output, receipt, ordinary_app, test_app):
    expected = json.loads(read(receipt))
    if hashlib.sha256(read(source)).hexdigest() != expected['source_sha256']:
        raise ValueError('Product plist changed during testing')
    if hashlib.sha256(read(output)).hexdigest() != expected['test_sha256']:
        raise ValueError('Isolated test plist changed')
    ordinary, test = plistlib.loads(read(ordinary_app)), plistlib.loads(read(test_app))
    if any(ordinary.get(key, False) is not False for key in KEYS):
        raise ValueError('Ordinary build exposed Documents')
    if any(test.get(key) is not True for key in KEYS):
        raise ValueError('Test build did not expose the synthetic Documents provider')
    if ordinary.get('CFBundleIdentifier') != 'dev.openeos.control' or test.get('CFBundleIdentifier') != 'dev.openeos.control':
        raise ValueError('Unexpected application identity')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('prepare', 'verify'))
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--ordinary-app', type=Path)
    parser.add_argument('--test-app', type=Path)
    args = parser.parse_args()
    if args.operation == 'prepare':
        prepare(args.source, args.output, args.receipt)
    else:
        if args.ordinary_app is None or args.test_app is None:
            parser.error('verify requires both app plists')
        verify(args.source, args.output, args.receipt, args.ordinary_app, args.test_app)


if __name__ == '__main__':
    main()
