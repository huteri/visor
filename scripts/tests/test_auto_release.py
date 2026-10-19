"""No network or credentials required: version, eligibility and reservation gates."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('auto_release', Path(__file__).parents[1] / 'auto_release.py')
assert SPEC is not None and SPEC.loader is not None
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
SHA = 'a' * 40
REPO = 'huteri/visor'


def pr(**overrides):
    value = dict(number=4, state='closed', merged_at='2026-10-19T00:00:00Z', merge_commit_sha=SHA,
                 base=dict(ref='main', repo=dict(full_name=REPO)))
    value.update(overrides)
    return value


def tag(name, sha=SHA):
    return dict(ref='refs/tags/' + name, object=dict(type='commit', sha=sha))


class ReleaseLogicTests(unittest.TestCase):
    def test_baseline_above_distributed_build(self):
        self.assertEqual(release.next_version([], []), '1.3.9')
        self.assertEqual(release.version_code('v1.3.9'), 10309)

    def test_all_drafts_releases_and_tags_count(self):
        self.assertEqual(release.next_version(['v1.3.10', 'unrelated'],
                         [dict(tag_name='v1.3.12-test.1', draft=True)]), '1.3.13')

    def test_base100_rollover_and_future_major(self):
        self.assertEqual(release.next_version(['v1.3.99'], []), '1.4.0')
        self.assertEqual(release.next_version(['v2.0.0'], []), '2.0.1')
        with self.assertRaises(ValueError):
            release.next_version(['v1.3.100'], [])

    def test_eligibility_exact_merged_commit_only(self):
        self.assertEqual(release.eligible_pr([pr()], SHA, REPO), 4)
        for p in [pr(state='open'), pr(merged_at=None), pr(merge_commit_sha='b'*40),
                  pr(base=dict(ref='feature', repo=dict(full_name=REPO))),
                  pr(base=dict(ref='main', repo=dict(full_name='fork/visor')))]:
            self.assertIsNone(release.eligible_pr([p], SHA, REPO))
        self.assertIsNone(release.eligible_pr([], SHA, REPO))

    def test_draft_idempotency_and_refuse_published_or_changed(self):
        draft = dict(tag_name='v1.3.9', draft=True, target_commitish=SHA)
        self.assertIs(release.existing_draft([draft], 'v1.3.9', SHA), draft)
        self.assertIsNone(release.existing_draft([], 'v1.3.9', SHA))
        for changes in [dict(draft=False), dict(target_commitish='b'*40)]:
            with self.assertRaises(ValueError):
                release.existing_draft([dict(draft, **changes)], 'v1.3.9', SHA)

    def run_prepare(self, prs, refs, releases=None, api_effect=None):
        # Keep fixture output under caller's scratch TMPDIR, never repo tree.
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {
            'GH_REPO': REPO, 'MERGED_SHA': SHA, 'GITHUB_OUTPUT': tmp + '/output'
        }), patch.object(release.subprocess, 'run') as command, patch.object(release.time, 'sleep'), \
             patch.object(release, 'api', side_effect=api_effect) as api:
            def pages(endpoint):
                if endpoint.endswith('/pulls'):
                    return prs
                if endpoint.endswith('/releases'):
                    return releases or []
                return refs.pop(0) if isinstance(refs[0], list) else refs
            with patch.object(release, 'pages', side_effect=pages):
                release.prepare()
            output = Path(tmp + '/output')
            return output.read_text() if output.exists() else '', api.call_args_list, command.call_args_list

    def test_nonmerge_skips_without_tag_mutation(self):
        output, calls, _ = self.run_prepare([], [tag('v1.3.9')])
        self.assertEqual(output, '')
        self.assertEqual(calls, [])

    def test_reservation_retry_reuses_same_version(self):
        output, calls, commands = self.run_prepare([pr()], [tag('v1.3.9')],
            [dict(tag_name='v1.3.9', draft=True, target_commitish=SHA)])
        self.assertIn('name=1.3.9\ncode=10309', output)
        self.assertEqual(calls, [])
        self.assertEqual(commands[-1].args[0], ['git', 'merge-base', '--is-ancestor', SHA, 'FETCH_HEAD'])

    def test_below_floor_tag_on_merged_sha_allocates_safe_version(self):
        output, calls, _ = self.run_prepare([pr()], [tag('v1.3.8')])
        self.assertIn('name=1.3.9\ncode=10309', output)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0].args[1], dict(ref='refs/tags/v1.3.9', sha=SHA))

    def test_below_floor_tags_do_not_conflict_with_safe_reservation(self):
        output, calls, _ = self.run_prepare([pr()],
            [tag('v1.3.7'), tag('v1.3.8'), tag('v1.3.10')],
            [dict(tag_name='v1.3.10', draft=True, target_commitish=SHA)])
        self.assertIn('name=1.3.10\ncode=10310', output)
        self.assertEqual(calls, [])

    def test_first_reservation_exact_sha(self):
        output, calls, _ = self.run_prepare([pr()], [tag('v1.3.8', 'b'*40)])
        self.assertIn('eligible=true', output)
        self.assertEqual(calls[0].args[1], dict(ref='refs/tags/v1.3.9', sha=SHA))

    def test_concurrent_tag_conflict_advances(self):
        output, calls, _ = self.run_prepare([pr()], [[tag('v1.3.8', 'b'*40)],
            [tag('v1.3.9', 'b'*40)]], api_effect=[subprocess.CalledProcessError(1, 'gh'), {}, {}])
        self.assertIn('name=1.3.10', output)
        self.assertEqual(calls[-1].args[1]['sha'], SHA)

    def test_same_sha_retry_after_failed_build(self):
        output, calls, _ = self.run_prepare([pr()], [tag('v1.3.9')])
        self.assertIn('name=1.3.9', output)
        self.assertEqual(calls, [])


if __name__ == '__main__':
    unittest.main()
