import tempfile
import unittest
from pathlib import Path
from processor import discover, digest, write_json


class ProcessorTests(unittest.TestCase):
    def test_discovery_excludes_packages_and_deduplicates_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory) / 'series'
            folder.mkdir()
            video = folder / 'Episode.MKV'
            video.touch()
            (folder / 'Episode.lrpack').touch()
            self.assertEqual(discover([directory, str(video)]), [video.resolve()])

    def test_checkpoint_preserves_japanese_and_hash_depends_on_content(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'checkpoint.json'
            write_json(path, {'japanese': '日本語'})
            first = digest(path)
            self.assertIn('日本語', path.read_text(encoding='utf-8'))
            write_json(path, {'japanese': 'こんにちは'})
            self.assertNotEqual(first, digest(path))
            self.assertFalse(path.with_suffix('.json.tmp').exists())


if __name__ == '__main__':
    unittest.main()
