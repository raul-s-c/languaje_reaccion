"""Validate the same v1 fields and limits accepted by Android."""
import hashlib
import json
import sys
import zipfile


def validate(path):
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None, 'CRC inválido'
        assert len(archive.infolist()) <= 8
        assert sum(entry.file_size for entry in archive.infolist()) <= 32 * 1024 * 1024
        payload = archive.read('study.json')
        assert hashlib.sha256(payload).hexdigest() == archive.read('study.sha256').decode().strip()
        study = json.loads(payload)
    assert study['formatVersion'] == 1
    previous = -1
    assert 1 <= len(study['segments']) <= 20000
    for segment in study['segments']:
        assert previous <= segment['startMillis'] < segment['endMillis']
        assert segment['japanese'] and segment['spanish'] and segment['tokens']
        previous = segment['startMillis']
    print(f"OK: {len(study['segments'])} frases; integridad, tiempos y traducciones válidos")


if __name__ == '__main__':
    validate(sys.argv[1])
