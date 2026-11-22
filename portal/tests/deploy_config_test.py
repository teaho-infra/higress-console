"""Installation must work without an existing private plugin-config.json."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('deploy', Path(__file__).resolve().parents[1] / 'scripts/deploy_demo.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

class DeploymentTest(unittest.TestCase):
    def test_install_from_clean_private_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            (root / 'scripts/money-config.json').write_text((deploy.ROOT / 'scripts/money-config.json').read_text())
            wasm = root / 'plugin.wasm'
            wasm.write_bytes(b'test-artifact')
            with patch.object(deploy, 'ROOT', root), patch.object(deploy, 'OUT', root / '.debug/cluster'), patch.object(deploy, 'PREFIX_POD', return_value='test-pod'), patch.object(deploy, 'kube', return_value='applied'), patch.object(deploy.subprocess, 'check_call'), contextlib.redirect_stdout(io.StringIO()):
                deploy.install(wasm)
            manifest = json.loads((root / '.debug/cluster/custom.private.json').read_text())
            self.assertEqual(manifest['spec']['priority'], 750)
            config = manifest['spec']['matchRules'][0]['config']
            self.assertEqual(config['redis_key_prefix'], 'wmt_portal_money_v2:')
            self.assertEqual(config['dimensions'], ['user', 'app'])
            self.assertTrue(config['redis']['password'])
            self.assertEqual((root / '.env').stat().st_mode & 0o777, 0o600)

if __name__ == '__main__':
    unittest.main()
