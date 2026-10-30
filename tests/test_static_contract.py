"""Sanity checks runnable without the Android SDK. NOT a substitute for APK/device testing."""
from pathlib import Path
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / 'app/src/main/kotlin/com/automaforge/android'
ASSETS = ROOT / 'app/src/main/assets'


def test_js_syntax():
    subprocess.run(['node', '--check', str(ASSETS/'web/app.js')], check=True)


def test_all_dom_ids_are_defined():
    js = (ASSETS/'web/app.js').read_text()
    html = (ASSETS/'web/index.html').read_text()
    dynamic = {'confirmProposal', 'rejectProposal', 'ackUnverified', 'completedMoves', 'executedProposal'}
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
    server = (KOTLIN/'data/lan/LanServer.kt').read_text()
    for route in ('/api/health','/api/rooms','/rules','/rules/pages','/rules/interpret',
                  '/board/photo','/board/analyze','/cards/private','/automa/manual',
                  '/automa/propose','/automa/reject','/automa/confirm','/state'):
        assert route in server
    assert 'ServerSocket()' in server
    assert 'sec-websocket-key' in server


def test_rule_manual_accepts_pdf():
    html = (ASSETS/'web/index.html').read_text()
    server = (KOTLIN/'data/lan/LanServer.kt').read_text()
    ai = (KOTLIN/'data/ai/AiClient.kt').read_text()
    assert 'accept="application/pdf,.pdf,image/*"' in html
    assert '"application/pdf"' in server
    assert 'isPdf(content.bytes)' in server
    assert '"input_file"' in ai
    assert '"file_data"' in ai and 'data:application/pdf;base64,' in ai


def test_private_card_not_stored_with_public_state():
    store = (KOTLIN/'data/game/GameStore.kt').read_text()
    server = (KOTLIN/'data/lan/LanServer.kt').read_text()
    assert 'remove("private_cards")' in store
    assert 'private val privateCards = mutableMapOf<String, JSONArray>()' in store
    assert 'No image, card text or any sensitive interpretation written to disk' in store
    assert 'privateCards.put(reading)' in server


def test_native_camera_and_build_workflow():
    manifest=(ROOT/'app/src/main/AndroidManifest.xml').read_text()
    camera=(KOTLIN/'data/camera/CameraController.kt').read_text()
    assert 'android.permission.CAMERA' in manifest
    assert 'android:allowBackup="false"' in manifest
    assert 'CameraCaptureSession' in camera
    assert 'postDelayed' in camera and '1200' in camera
    assert ':app:assembleDebug' in (ROOT/'.github/workflows/android-apk.yml').read_text()


def test_kotlin_mvvm_room_dagger_boundaries():
    assert not list((ROOT/'app/src/main').rglob('*.java'))
    for path in (KOTLIN/'domain').rglob('*.kt'):
        source=path.read_text()
        assert 'import android.' not in source
        assert 'import com.automaforge.android.data.' not in source
    assert '@Component' in (KOTLIN/'di/AppComponent.kt').read_text()
    database=(KOTLIN/'data/local/AutomaDatabase.kt').read_text()
    assert '@Database' in database and 'RoomDatabase' in database
    assert 'fallbackToDestructiveMigration' not in database
    assert 'allowMainThreadQueries' not in database
    assert '@Composable' in (KOTLIN/'presentation/main/MainScreen.kt').read_text()
    assert 'ViewModel()' in (KOTLIN/'presentation/main/MainViewModel.kt').read_text()
    for path in (KOTLIN/'presentation').rglob('*ViewModel.kt'):
        assert 'WebView' not in path.read_text().replace('WebView,','') or path.name=='MainViewModel.kt'
        assert 'import android.app.Activity' not in path.read_text()
