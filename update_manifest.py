"""Validation helpers for future update clients; not an updater by itself."""
import hashlib
import re
from pathlib import Path
from urllib.parse import urlsplit

SHA256 = re.compile(r'^[0-9a-f]{64}$')

def validate(manifest, platform):
    if not isinstance(manifest, dict) or manifest.get('schema_version') != 1:
        raise ValueError('unsupported manifest')
    if manifest.get('platform') != platform:
        raise ValueError('wrong platform')
    version = manifest.get('version_code')
    size = manifest.get('size_bytes')
    if type(version) is not int or version <= 0 or type(size) is not int or not 0 < size <= 2_000_000_000:
        raise ValueError('invalid version or size')
    if not isinstance(manifest.get('sha256'), str) or not SHA256.fullmatch(manifest['sha256']):
        raise ValueError('invalid digest')
    url = manifest.get('url')
    if not isinstance(url, str):
        raise ValueError('invalid URL')
    try:
        u = urlsplit(url)
        if u.scheme != 'https' or not u.hostname or u.username is not None or u.password is not None or u.fragment:
            raise ValueError('invalid URL')
        _ = u.port
    except ValueError:
        raise ValueError('invalid URL') from None
    return manifest

def verify_file(path, manifest):
    digest = hashlib.sha256()
    size = 0
    with Path(path).open('rb') as f:
        while True:
            block = f.read(1024 * 1024)
            if not block:
                break
            size += len(block)
            if size > manifest['size_bytes']:
                return False
            digest.update(block)
    return size == manifest['size_bytes'] and digest.hexdigest() == manifest['sha256']
