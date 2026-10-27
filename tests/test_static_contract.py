"""Sanity checks runnable without the Android SDK. NOT a substitute for APK/device testing."""
from pathlib import Path
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/automaforge/android'
ASSETS = ROOT / 'app/src/main/assets'


def test_js_syntax():
    subprocess.run(['node', '--check', str(ASSETS/'web/app.js')], check=True)


def test_all_dom_ids_are_defined():
    js = (ASSETS/'web/app.js').read_text()
    html = (ASSETS/'web/index.html').read_text()
    dynamic = {'confirmProposal', 'rejectProposal'}
    ids = set(re.findall(r"\$\(['\"]([A-Za-z][\w-]*)['\"]\)", js))
    missing = {x for x in ids if f'id="{x}"' not in html and x not in dynamic}
    assert not missing, f'Undefined HTML id(s): {missing}'


def test_schemas_are_strict_and_complete():
    for filename in ('rules','board','card','proposal'):
        schema = json.loads((ASSETS/f'schemas/{filename}.json').read_text())
        assert schema['type'] == 'object'
        assert schema['additionalProperties'] is False
        assert set(schema['required']) == set(schema['properties'])


def test_server_exposes_ui_contract():
    server = (JAVA/'LanServer.java').read_text()
    for route in ('/api/health','/api/rooms','/rules','/rules/pages','/rules/interpret',
                  '/board/photo','/board/analyze','/cards/private','/automa/manual',
                  '/automa/propose','/automa/reject','/automa/confirm','/state'):
        assert route in server
    assert 'new ServerSocket()' in server
    assert 'sec-websocket-key' in server


def test_rule_manual_accepts_pdf():
    html = (ASSETS/'web/index.html').read_text()
    server = (JAVA/'LanServer.java').read_text()
    ai = (JAVA/'AiClient.java').read_text()
    assert 'accept="application/pdf,.pdf,image/*"' in html
    assert '"application/pdf"' in server
    assert 'isPdf(content.bytes)' in server
    assert '"type", "input_file"' in ai
    assert '"file_data", "data:application/pdf;base64," + encoded' in ai


def test_private_card_not_stored_with_public_state():
    store = (JAVA/'GameStore.java').read_text()
    server = (JAVA/'LanServer.java').read_text()
    assert 'safe.remove("private_cards")' in store
    assert 'private final Map<String, JSONArray> privateCards' in store
    assert 'No image, card text or any sensitive interpretation written to disk' in server
    assert 'privateCards.put(reading)' in server


def test_native_camera_and_build_workflow():
    manifest=(ROOT/'app/src/main/AndroidManifest.xml').read_text()
    camera=(JAVA/'CameraActivity.java').read_text()
    assert 'android.permission.CAMERA' in manifest
    assert 'android:allowBackup="false"' in manifest
    assert 'CameraCaptureSession' in camera
    assert 'cameraHandler.postDelayed(autoCapture,1200)' in camera
    assert ':app:assembleDebug' in (ROOT/'.github/workflows/android-apk.yml').read_text()
