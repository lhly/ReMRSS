#!/usr/bin/env python3
"""Exercise release integrity, conflict handling and interrupted Quark syncs."""

import copy
import hashlib
from html import escape
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.request import Request

import quark_release_sync as sync
from quark_release_job import definition


def digest(data):
    return hashlib.sha256(data).hexdigest()


def public_page(releases, page=1, next_page=None):
    route = {'repository': {'id': sync.REPOSITORY_ID, 'name': 'ReMRSS', 'ownerLogin': 'lhly'},
             'toc': [{'anchorId': 'release-' + release['tag_name'], 'id': str(release['id'])} for release in releases]}
    result = ['<script type="application/json" data-target="react-app.embeddedData">',
              json.dumps({'payload': {'releasesIndexRoute': route}}), '</script>']
    for release in releases:
        tag = release['tag_name']
        result.append(f'<section data-release-anchor="release-{escape(tag)}">')
        result.append(f'<a href="/{sync.REPOSITORY}/releases/tag/{tag}">Version</a>')
        if release.get('prerelease'):
            result.append('<span class="Label Label--warning">Pre-release</span>')
        if release.get('draft'):
            result.append('<span class="Label">Draft</span>')
        result.append(f'<relative-time datetime="{release["published_at"]}"></relative-time>')
        result.append(f'<include-fragment src="https://github.com/{sync.REPOSITORY}/releases/expanded_assets/{tag}"></include-fragment>')
        result.append('</section>')
    if next_page:
        result.append(f'<a rel="next" href="/{sync.REPOSITORY}/releases?page={next_page}">Next</a>')
    return ''.join(result)


def public_assets(release):
    result = ['<ul>']
    for asset in release['assets']:
        url = asset['browser_download_url']
        result.append(f'<li><a href="{escape(url)}">{escape(asset["name"])}</a>')
        if asset.get('digest'):
            result.append(f'<clipboard-copy value="{asset["digest"]}"></clipboard-copy>')
        result.append('</li>')
    result.append(f'<li><a href="/{sync.REPOSITORY}/archive/refs/tags/{release["tag_name"]}.zip">Source code</a></li></ul>')
    return ''.join(result)


class HttpFixture:
    def __init__(self):
        self.contents = {'ReMRSS-v3.6.0.apk': b'published apk', 'LICENSE': b'GPL text', 'NOTICE': b'Credits'}
        self.contents['SHA256SUMS'] = ''.join(f'{digest(data)}  {name}\n' for name, data in self.contents.items()).encode()
        self.feed = {'schemaVersion': 1, 'versionName': '3.6.0', 'versionCode': 2019, 'tag': 'v3.6.0',
                     'apk': {'url': 'https://github.com/lhly/MiRearScreenSwitcher/releases/download/v3.6.0/ReMRSS-v3.6.0.apk',
                             'size': len(self.contents['ReMRSS-v3.6.0.apk']),
                             'sha256': digest(self.contents['ReMRSS-v3.6.0.apk']), 'packageName': 'top.zainzh.rearscreen'}}

    def manifest(self, directory):
        return copy.deepcopy(self.feed)

    def download(self, url, path, limit):
        data = self.contents[url.rsplit('/', 1)[1]]
        if len(data) > limit:
            raise sync.SyncError('too large')
        path.write_bytes(data)


class QuarkFixture:
    def __init__(self):
        self.folders = {'target': [], 'version': []}
        self.data = {}
        self.uploads = []
        self.checked = 0
        self.notices = []
        self.fail_on = None
        self.corrupt_readback = False

    def factory(self, *args):
        return self

    def install_check(self):
        self.checked += 1

    def browse(self, fid):
        return copy.deepcopy(self.folders[fid])

    def folder(self, parent, tag):
        if not any(e['filename'] == tag for e in self.folders[parent]):
            self.folders[parent].append({'fid': 'version', 'filename': tag, 'file_type': '0'})
        return 'version'

    def upload(self, path, parent):
        if path.name == self.fail_on:
            raise sync.SyncError('interrupted')
        self.uploads.append(path.name)
        fid = 'file-' + path.name
        self.data[fid] = path.read_bytes()
        self.folders[parent].append({'fid': fid, 'filename': path.name, 'file_type': '1', 'size': path.stat().st_size})
        return fid, '夸克网盘/分享/ReMRSS/v3.6.0'

    def readback(self, fid, expected):
        if self.corrupt_readback or digest(self.data[fid]) != expected['sha256']:
            raise sync.SyncError('content mismatch')


class PreviewHttpFixture(HttpFixture):
    def __init__(self):
        super().__init__()
        self.release_list = []
        self.assets = {}
        self.list_error = False

    def add_preview(self, tag, sdk=False):
        data = {f'ReMRSS-{tag}.apk': b'preview ' + tag.encode(), 'LICENSE': b'GPL text', 'NOTICE': b'Credits'}
        if sdk:
            data['remrss-platform-sdk-0.2.0.zip'] = b'published sdk'
        data['SHA256SUMS'] = ''.join(f'{digest(value)}  {name}\n' for name, value in data.items()).encode()
        base = f'https://github.com/{sync.REPOSITORY}/releases/download/{tag}/'
        release = {'id': len(self.release_list) + 1, 'tag_name': tag, 'draft': False, 'prerelease': True,
                   'published_at': '2026-11-08T12:56:17Z',
                   'html_url': f'https://github.com/{sync.REPOSITORY}/releases/tag/{tag}',
                   'assets': [{'name': name, 'size': len(value), 'digest': 'sha256:' + digest(value),
                               'state': 'uploaded', 'browser_download_url': base + name} for name, value in data.items()]}
        self.assets.update({base + name: value for name, value in data.items()})
        self.release_list.insert(0, release)
        return release

    def releases(self, directory, known=None):
        if self.list_error:
            raise sync.SyncError('GitHub rate limited')
        return copy.deepcopy(self.release_list)

    def download(self, url, path, limit):
        if url not in self.assets:
            return super().download(url, path, limit)
        value = self.assets[url]
        if len(value) > limit:
            raise sync.SyncError('too large')
        path.write_bytes(value)


class PreviewQuarkFixture(QuarkFixture):
    def __init__(self):
        super().__init__()
        self.targets = []

    def folder(self, parent, tag):
        existing = [item for item in self.folders[parent] if item['filename'] == tag]
        if existing:
            return existing[0]['fid']
        fid = 'folder-' + str(len(self.folders))
        self.folders[fid] = []
        self.folders[parent].append({'fid': fid, 'filename': tag, 'file_type': '0'})
        return fid

    def upload(self, path, parent):
        if path.name == self.fail_on:
            raise sync.SyncError('interrupted')
        self.uploads.append(path.name)
        self.targets.append(parent)
        fid = parent + '-' + path.name
        self.data[fid] = path.read_bytes()
        self.folders[parent].append({'fid': fid, 'filename': path.name, 'file_type': '1', 'size': path.stat().st_size})
        return fid, '夸克网盘/分享/ReMRSS/' + sync.PRERELEASE_FOLDER


class ReleaseSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.work = self.root / 'work'
        self.work.mkdir()
        self.config = {'minimum_version': '3.6.0', 'publish_dir': str(self.root / 'public'),
                       'quark_target_fid': 'target'}
        self.state = self.root / 'state.json'
        self.http = HttpFixture()
        self.quark = QuarkFixture()

    def tearDown(self):
        self.temp.cleanup()

    def run_sync(self):
        return sync.run(self.config, self.state, self.work, self.http, self.quark.factory)

    def test_published_release_uploaded_and_verified(self):
        result = self.run_sync()
        self.assertEqual(result['status'], 'uploaded')
        self.assertEqual(result['file_count'], 4)
        self.assertEqual(set(self.quark.uploads), set(self.http.contents))
        self.assertEqual(json.loads(self.state.read_text())['releases']['v3.6.0']['status'], 'complete')

    def test_completed_release_does_not_call_skill_again(self):
        self.run_sync()
        self.assertEqual(self.run_sync()['status'], 'unchanged')
        self.assertEqual(self.quark.checked, 1)
        self.assertEqual(len(self.quark.uploads), 4)

    def test_interrupted_upload_resumes_only_missing_files(self):
        self.quark.fail_on = 'NOTICE'
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(self.quark.uploads, ['ReMRSS-v3.6.0.apk', 'LICENSE'])
        self.quark.fail_on = None
        self.assertEqual(self.run_sync()['status'], 'uploaded')
        self.assertEqual(len(self.quark.uploads), 4)

    def test_existing_identical_files_reconciled_by_readback(self):
        self.run_sync()
        self.state.unlink()
        self.assertEqual(self.run_sync()['status'], 'uploaded')
        self.assertEqual(len(self.quark.uploads), 4)

    def test_sdk_creation_receipt_reconciled_with_opaque_folder_id(self):
        self.quark.fail_on = 'NOTICE'
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.quark.folders['target'][0]['fid'] = '~opaque|folderFingerprint'
        self.quark.folders['~opaque|folderFingerprint'] = self.quark.folders['version']
        self.quark.fail_on = None
        self.assertEqual(self.run_sync()['status'], 'uploaded')
        self.assertEqual(len(self.quark.uploads), 4)

    def test_readback_uses_listing_ids_instead_of_upload_receipts(self):
        original = self.quark.browse
        def qualified(fid):
            rows = original(fid)
            if fid == 'version':
                for row in rows:
                    receipt = row['fid']
                    row['fid'] = '~opaque|fingerprint-' + receipt
                    self.quark.data[row['fid']] = self.quark.data[receipt]
            return rows
        self.quark.browse = qualified
        self.assertEqual(self.run_sync()['status'], 'uploaded')

    def test_rotating_folder_prefix_keeps_same_identity(self):
        self.quark.fail_on = 'NOTICE'
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        record = json.loads(self.state.read_text())
        record['releases']['v3.6.0']['folder_fid'] = '~old|folderFingerprint'
        sync.write_json(self.state, record)
        self.quark.folders['target'][0]['fid'] = '~new|folderFingerprint'
        self.quark.folders['~new|folderFingerprint'] = self.quark.folders['version']
        self.quark.fail_on = None
        self.assertEqual(self.run_sync()['status'], 'uploaded')
        self.assertEqual(len(self.quark.uploads), 4)

    def test_replaced_version_folder_stops_resume(self):
        self.quark.fail_on = 'NOTICE'
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        record = json.loads(self.state.read_text())
        record['releases']['v3.6.0']['folder_fid'] = '~old|oldFingerprint'
        sync.write_json(self.state, record)
        self.quark.folders['target'][0]['fid'] = '~new|newFingerprint'
        self.quark.fail_on = None
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(len(self.quark.uploads), 2)

    def test_conflicting_same_size_file_never_overwritten(self):
        self.run_sync()
        self.state.unlink()
        self.quark.data['file-LICENSE'] = b'bad text'
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(len(self.quark.uploads), 4)

    def test_ambiguous_version_folder_stops_upload(self):
        self.quark.folders['target'] = [{'fid': 'a', 'filename': 'v3.6.0', 'file_type': '0'},
                                        {'fid': 'b', 'filename': 'v3.6.0', 'file_type': '0'}]
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(self.quark.uploads, [])

    def test_existing_version_name_is_file_stops_upload(self):
        self.quark.folders['target'] = [{'fid': 'file', 'filename': 'v3.6.0', 'file_type': '1'}]
        with self.assertRaises(sync.SyncError):
            self.run_sync()

    def test_corrupt_readback_does_not_complete_release(self):
        self.quark.corrupt_readback = True
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        record = json.loads(self.state.read_text())['releases']['v3.6.0']
        self.assertEqual(record['status'], 'pending')
        self.assertEqual(record['files'], {})

    def test_changed_existing_release_rejected(self):
        self.run_sync()
        self.http.feed['apk']['sha256'] = 'a' * 64
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(len(self.quark.uploads), 4)

    def test_changed_target_requires_new_state(self):
        self.run_sync()
        self.config['quark_target_fid'] = 'other'
        with self.assertRaises(sync.SyncError):
            self.run_sync()

    def test_feed_rollback_does_not_upload_older_release(self):
        sync.write_json(self.state, {'schema_version': 1, 'releases': {'v9.0.0': {'status': 'complete', 'versionCode': 9999}}})
        self.assertEqual(self.run_sync()['reason'], 'older_than_uploaded_release')
        self.assertEqual(self.quark.checked, 0)

    def test_pre_baseline_release_skipped(self):
        self.config['minimum_version'] = '4.0.0'
        self.assertEqual(self.run_sync()['reason'], 'older_than_configured_baseline')

    def test_prerelease_manifest_rejected(self):
        self.http.feed['versionName'] = '3.6.1-rc.1'
        self.http.feed['tag'] = 'v3.6.1-rc.1'
        with self.assertRaises(ValueError):
            self.run_sync()

    def test_foreign_repository_url_rejected(self):
        self.http.feed['apk']['url'] = self.http.feed['apk']['url'].replace('/lhly/', '/other/')
        with self.assertRaises(sync.SyncError):
            self.run_sync()

    def test_stable_repository_rename_keeps_completed_release_identity(self):
        with patch.object(self.http, 'download', wraps=self.http.download) as download:
            self.run_sync()
        self.assertEqual(len(download.call_args_list), 4)
        for call in download.call_args_list:
            self.assertTrue(call.args[0].startswith(f'https://github.com/{sync.REPOSITORY}/releases/download/'))
        self.http.feed['apk']['url'] = self.http.feed['apk']['url'].replace(sync.LEGACY_REPOSITORY, sync.REPOSITORY)
        self.assertEqual(self.run_sync()['status'], 'unchanged')
        self.assertEqual(len(self.quark.uploads), 4)

    def test_config_accepts_current_and_existing_repository_names(self):
        path = self.root / 'config.json'
        for name in (sync.REPOSITORY, sync.LEGACY_REPOSITORY):
            with self.subTest(name=name):
                config = dict(self.config, schema_version=1, repository=name)
                sync.write_json(path, config)
                self.assertEqual(sync.load_config(path), config)

    def test_corrupt_artifact_never_uploaded(self):
        self.http.contents['LICENSE'] = b'corrupted'
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(self.quark.checked, 0)
        self.assertEqual(self.quark.uploads, [])

    def test_checksum_path_traversal_rejected(self):
        self.http.contents['SHA256SUMS'] += (('a' * 64) + '  ../secret\n').encode()
        with self.assertRaises(sync.SyncError):
            self.run_sync()

    def test_duplicate_checksums_rejected(self):
        self.http.contents['SHA256SUMS'] += self.http.contents['SHA256SUMS'].splitlines(keepends=True)[0]
        with self.assertRaises(sync.SyncError):
            self.run_sync()

    def test_unsigned_extra_file_in_cache_rejected(self):
        self.run_sync()
        self.state.unlink()
        (Path(self.config['publish_dir']) / 'v3.6.0' / 'credentials.json').write_text('not a release asset')
        with self.assertRaises(sync.SyncError):
            self.run_sync()

    def test_second_process_cannot_take_lock(self):
        with sync.locked(self.root / 'sync.lock'):
            with self.assertRaises(sync.SyncError):
                with sync.locked(self.root / 'sync.lock'):
                    self.fail('second lock should not succeed')

    def test_launch_job_has_no_gui_or_credentials(self):
        import sys
        config = {'node_path': sys.executable, 'python_path': sys.executable, 'interval_seconds': 900}
        job = definition(self.root / 'config.json', config)
        self.assertEqual(job['StartInterval'], 900)
        self.assertTrue(job['RunAtLoad'])
        self.assertEqual(job['ProgramArguments'][2], 'run')
        self.assertNotIn('token', json.dumps(job).lower())
        self.assertNotIn('osascript', json.dumps(job))

    def test_launch_job_preserves_creating_codex_context_without_credentials(self):
        import sys
        config = {'node_path': sys.executable, 'python_path': sys.executable}
        with patch.dict(os.environ, {'CODEX_CI': '1', 'CODEX_THREAD_ID': 'private-thread',
                                     'GH_TOKEN': 'private-token'}, clear=True):
            job = definition(self.root / 'config.json', config)
        self.assertEqual(job['EnvironmentVariables']['CODEX_CI'], '1')
        self.assertEqual(set(job['EnvironmentVariables']), {'PATH', 'CODEX_CI'})
        self.assertNotIn('private-', json.dumps(job))

    def test_launch_job_does_not_invent_codex_context(self):
        import sys
        config = {'node_path': sys.executable, 'python_path': sys.executable}
        with patch.dict(os.environ, {}, clear=True):
            job = definition(self.root / 'config.json', config)
        self.assertNotIn('CODEX_CI', job['EnvironmentVariables'])


class PrereleaseSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.work = self.root / 'work'
        self.work.mkdir()
        self.state = self.root / 'prereleases-state.json'
        self.config = {'minimum_version': '3.6.0', 'publish_dir': str(self.root / 'public'),
                       'quark_target_fid': 'target', 'sync_prereleases': True}
        self.http = PreviewHttpFixture()
        self.http.add_preview('v4.0.0-rc.1')
        self.http.add_preview('v4.0.0-rc.2', sdk=True)
        self.quark = PreviewQuarkFixture()

    def tearDown(self):
        self.temp.cleanup()

    def run_sync(self):
        return sync.run_prereleases(self.config, self.state, self.work, self.http, self.quark.factory)

    def channels(self):
        return sync.run_channels(self.config, self.root, self.work, self.http, self.quark.factory)

    def test_all_previews_backfilled_in_separate_folder_with_sdk(self):
        result = self.run_sync()
        self.assertEqual([item['tag'] for item in result['uploaded']], ['v4.0.0-rc.1', 'v4.0.0-rc.2'])
        self.assertEqual([item['file_count'] for item in result['uploaded']], [4, 5])
        self.assertEqual([item['filename'] for item in self.quark.folders['target']], [sync.PRERELEASE_FOLDER])
        state = json.loads(self.state.read_text())
        self.assertEqual(set(state['releases']), {'v4.0.0-rc.1', 'v4.0.0-rc.2'})
        for tag in state['releases']:
            self.assertEqual(state['releases'][tag]['status'], 'complete')
            self.assertTrue((Path(self.config['publish_dir']) / sync.PRERELEASE_FOLDER / tag).is_dir())

    def test_unchanged_preview_list_does_not_call_skill_or_upload(self):
        self.run_sync()
        self.assertEqual(self.run_sync()['status'], 'unchanged')
        self.assertEqual(len(self.quark.uploads), 9)
        self.assertEqual(self.quark.checked, 1)

    def test_future_preview_is_discovered(self):
        self.run_sync()
        self.http.add_preview('v4.0.0-rc.3', sdk=True)
        self.assertEqual([item['tag'] for item in self.run_sync()['uploaded']], ['v4.0.0-rc.3'])
        self.assertEqual(len(self.quark.uploads), 14)

    def test_missing_older_preview_backfilled_even_below_stable_baseline(self):
        self.http.release_list = self.http.release_list[:1]
        self.run_sync()
        self.config['minimum_version'] = '9.0.0'
        self.http.add_preview('v3.0.0-beta.1')
        self.assertEqual([item['tag'] for item in self.run_sync()['uploaded']], ['v3.0.0-beta.1'])

    def test_drafts_stable_and_unpublished_releases_are_not_synced(self):
        for release, change in zip(self.http.release_list, [{'draft': True}, {'prerelease': False}]):
            release.update(change)
        self.http.add_preview('v4.0.0-rc.3')['published_at'] = None
        self.assertEqual(self.run_sync()['release_count'], 0)
        self.assertEqual(self.quark.uploads, [])

    def test_interruption_resumes_without_reuploading_completed_files(self):
        self.quark.fail_on = 'NOTICE'
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(len(self.quark.uploads), 2)
        self.quark.fail_on = None
        self.assertEqual(self.run_sync()['status'], 'uploaded')
        self.assertEqual(len(self.quark.uploads), 9)

    def test_changed_published_assets_never_overwritten(self):
        self.run_sync()
        self.http.release_list[0]['assets'][0]['digest'] = 'sha256:' + '0' * 64
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(len(self.quark.uploads), 9)

    def test_foreign_or_traversal_asset_rejected(self):
        asset = self.http.release_list[0]['assets'][0]
        original = copy.deepcopy(asset)
        for change in [{'browser_download_url': 'https://example.com/app.apk'}, {'name': '../secret'}]:
            with self.subTest(change=change):
                asset.update(original)
                asset.update(change)
                with self.assertRaises(sync.SyncError):
                    self.run_sync()
        self.assertEqual(self.quark.uploads, [])

    def test_missing_checksum_or_apk_or_digest_rejected(self):
        original = copy.deepcopy(self.http.release_list)
        for name in ['SHA256SUMS', 'ReMRSS-v4.0.0-rc.2.apk', 'digest']:
            with self.subTest(name=name):
                self.http.release_list = copy.deepcopy(original)
                assets = self.http.release_list[0]['assets']
                if name == 'digest':
                    assets[0].pop('digest')
                else:
                    assets[:] = [asset for asset in assets if asset['name'] != name]
                with self.assertRaises(sync.SyncError):
                    self.run_sync()
        self.assertEqual(self.quark.uploads, [])

    def test_corrupt_sdk_never_uploaded(self):
        self.http.release_list = self.http.release_list[:1]
        sdk = next(asset for asset in self.http.release_list[0]['assets'] if asset['name'].endswith('.zip'))
        self.http.assets[sdk['browser_download_url']] = b'corrupted sdk'
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(self.quark.uploads, [])

    def test_parent_name_conflict_stops_without_overwriting(self):
        self.quark.folders['target'].append({'fid': 'file', 'filename': sync.PRERELEASE_FOLDER, 'file_type': '1'})
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(self.quark.uploads, [])

    def test_new_folder_waits_for_listing_visibility(self):
        browse = self.quark.browse
        count = 0
        def delayed(fid):
            nonlocal count
            count += 1
            return [] if count <= 2 else browse(fid)
        self.quark.browse = delayed
        with patch('quark_release_sync.time.sleep') as sleep:
            folder = sync.ensure_folder(self.quark, 'target', sync.PRERELEASE_FOLDER)
        self.assertTrue(folder)
        sleep.assert_called_once_with(1)

    def test_upload_waits_for_listing_without_duplicate_upload(self):
        browse = self.quark.browse
        hidden = set()
        def delayed(fid):
            rows = browse(fid)
            new = [row['fid'] for row in rows if row.get('file_type') == '1' and row['fid'] not in hidden]
            if new:
                hidden.update(new)
                return [row for row in rows if row['fid'] not in new]
            return rows
        self.quark.browse = delayed
        with patch('quark_release_sync.time.sleep'):
            self.assertEqual(self.run_sync()['status'], 'uploaded')
        self.assertEqual(len(self.quark.uploads), 9)

    def test_changed_target_rejected_even_when_previews_complete(self):
        self.run_sync()
        self.config['quark_target_fid'] = 'other'
        with self.assertRaises(sync.SyncError):
            self.run_sync()

    def test_stable_and_preview_states_and_directories_are_isolated(self):
        result = self.channels()
        self.assertEqual(result['stable']['status'], 'uploaded')
        self.assertEqual(result['prereleases']['status'], 'uploaded')
        stable = json.loads((self.root / 'state.json').read_text())
        self.assertEqual(set(stable['releases']), {'v3.6.0'})
        self.assertEqual(len(json.loads(self.state.read_text())['releases']), 2)
        self.assertEqual({item['filename'] for item in self.quark.folders['target']}, {'v3.6.0', sync.PRERELEASE_FOLDER})
        self.assertEqual(self.channels()['status'], 'unchanged')
        self.assertEqual(len(self.quark.uploads), 13)

    def test_preview_api_failure_does_not_block_stable_upload(self):
        self.http.list_error = True
        result = self.channels()
        self.assertEqual(result['stable']['status'], 'uploaded')
        self.assertEqual(result['prereleases']['status'], 'failed')
        self.assertEqual(result['status'], 'failed')
        self.assertEqual(len(self.quark.uploads), 4)

    def test_stable_failure_does_not_block_preview_upload(self):
        self.http.feed['versionName'] = 'invalid'
        result = self.channels()
        self.assertEqual(result['stable']['status'], 'failed')
        self.assertEqual(result['prereleases']['status'], 'uploaded')
        self.assertEqual(len(self.quark.uploads), 9)

    def test_existing_config_without_preview_opt_in_keeps_original_contract(self):
        self.config.pop('sync_prereleases')
        self.http.list_error = True
        result = self.channels()
        self.assertEqual(result['tag'], 'v3.6.0')
        self.assertNotIn('prereleases', result)
        self.assertFalse(self.state.exists())

    def test_public_release_pagination_includes_second_page_without_api(self):
        http = sync.Http()
        urls = []
        def download(url, path, limit):
            urls.append(url)
            if '/expanded_assets/' in url:
                tag = url.rsplit('/', 1)[1]
                path.write_text(public_assets(next(item for item in self.http.release_list if item['tag_name'] == tag)))
            else:
                path.write_text(public_page(self.http.release_list[:1], page=1, next_page=2)
                                if '?page=2' not in url else public_page(self.http.release_list[1:], page=2))
        http.download = download
        http.asset_size = lambda url: len(self.http.assets[url])
        self.assertEqual(len(http.releases(self.work)), 2)
        self.assertTrue(any(url.endswith('?page=2') for url in urls))
        self.assertFalse(any('api.github.com' in url for url in urls))

    def test_repository_rename_does_not_repeat_preview_uploads(self):
        self.run_sync()
        state = json.loads(self.state.read_text())
        for record in state['releases'].values():
            for asset in record['assets'].values():
                asset['url'] = asset['url'].replace(sync.REPOSITORY, sync.LEGACY_REPOSITORY)
        sync.write_json(self.state, state)
        self.assertEqual(self.run_sync()['status'], 'unchanged')
        self.assertEqual(len(self.quark.uploads), 9)

    def test_public_page_reuses_sizes_only_when_digest_unchanged(self):
        self.run_sync()
        http = sync.Http()
        def download(url, path, limit):
            if '/expanded_assets/' in url:
                tag = url.rsplit('/', 1)[1]
                path.write_text(public_assets(next(item for item in self.http.release_list if item['tag_name'] == tag)))
            else:
                path.write_text(public_page(self.http.release_list))
        http.download = download
        known = json.loads(self.state.read_text())['releases']
        with patch.object(http, 'asset_size', side_effect=AssertionError('unchanged digest needs no new HEAD')):
            releases = http.releases(self.work, known)
        self.assertEqual(len(releases), 2)
        self.http.release_list[0]['assets'][0]['digest'] = 'sha256:' + '0' * 64
        with patch.object(http, 'asset_size', return_value=10) as head:
            http.releases(self.work, known)
        head.assert_called_once()

    def test_public_parser_keeps_preview_flag_and_excludes_source_archives(self):
        page = sync.PublicReleasePage()
        releases = copy.deepcopy(self.http.release_list)
        releases[1]['prerelease'] = False
        page.feed(public_page(releases))
        parsed, next_page = page.results()
        self.assertEqual([item['prerelease'] for item in parsed], [True, False])
        self.assertIsNone(next_page)
        assets = sync.PublicAssets()
        assets.feed(public_assets(releases[0]))
        self.assertEqual(len(assets.assets), 5)
        self.assertFalse(any(asset['name'] == 'Source code' for asset in assets.assets))

    def test_public_parser_rejects_missing_digest_and_untrusted_pages(self):
        release = copy.deepcopy(self.http.release_list[0])
        release['assets'][0].pop('digest')
        with self.assertRaises(sync.SyncError):
            sync.PublicAssets().feed(public_assets(release))
        for html in ['<html>unavailable</html>', public_page([]).replace(str(sync.REPOSITORY_ID), '123'),
                     public_page([]) + '<a rel="next" href="https://example.com/releases">Next</a>']:
            with self.subTest(html=html), self.assertRaises(sync.SyncError):
                page = sync.PublicReleasePage()
                page.feed(html)
                page.results()

    def test_public_pagination_cycle_stops_without_returning_partial_list(self):
        http = sync.Http()
        http.download = lambda url, path, limit: path.write_text(public_page([], next_page=2))
        with self.assertRaises(sync.SyncError):
            http.releases(self.work)

    def test_redirect_allows_official_api_rename_but_not_foreign_hosts(self):
        handler = sync.SafeRedirect()
        req = Request('https://api.github.com/repos/lhly/MiRearScreenSwitcher/releases')
        result = handler.redirect_request(req, None, 301, 'Moved', {},
                                          'https://api.github.com/repositories/1414137580/releases')
        self.assertEqual(result.host, 'api.github.com')
        for url in ['https://example.com/releases', 'http://github.com/lhly/ReMRSS/releases',
                    'https://api.github.com.example.com/releases']:
            with self.subTest(url=url), self.assertRaises(sync.SyncError):
                handler.redirect_request(req, None, 301, 'Moved', {}, url)

    def test_install_check_reused_for_both_channels(self):
        quark = sync.Quark({'skill_dir': str(self.root), 'node_path': '/usr/bin/node'}, self.work)
        process = type('Process', (), {'returncode': 0, 'stdout': '{"type":"install_result","success":true,"updated":false}'})()
        with patch('quark_release_sync.subprocess.run', return_value=process) as command:
            quark.install_check()
            quark.install_check()
        self.assertEqual(command.call_count, 1)


if __name__ == '__main__':
    unittest.main(verbosity=2)
