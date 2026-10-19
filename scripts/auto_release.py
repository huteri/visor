#!/usr/bin/env python3
"""Reserve monotonic release versions for canonical merged-main commits.

Git refs are atomic reservations (including failed builds). Never delete reservations:
retrying one SHA reuses its version, while concurrent merges advance independently.
"""
import json
import os
import re
import subprocess
import time

BASELINE_CODE = 10308  # Highest previously distributed APK, even if not tagged.
VERSION = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?:-[0-9A-Za-z.]+)?$")


def version_code(tag):
    match = VERSION.fullmatch(tag)
    if not match:
        return None
    major, minor, patch = map(int, match.groups())
    if minor >= 100 or patch >= 100:
        raise ValueError(f"Version outside base-100 Android mapping: {tag}")
    return major * 10000 + minor * 100 + patch


def next_version(tags, releases, baseline=BASELINE_CODE):
    codes = [version_code(t) for t in tags]
    codes += [version_code(r['tag_name']) for r in releases]
    code = max([baseline] + [c for c in codes if c is not None]) + 1
    if code > 2100000000:
        raise ValueError('Android versionCode exhausted')
    return f'{code // 10000}.{code // 100 % 100}.{code % 100}'


def eligible_pr(prs, sha, repo):
    matches = [p for p in prs if p.get('merged_at') and p.get('state') == 'closed'
               and p.get('merge_commit_sha') == sha
               and p.get('base', {}).get('ref') == 'main'
               and p.get('base', {}).get('repo', {}).get('full_name') == repo]
    if len(matches) > 1:
        raise ValueError('Ambiguous merged PR for SHA')
    return matches[0]['number'] if matches else None


def existing_draft(releases, tag, sha):
    release = next((r for r in releases if r['tag_name'] == tag), None)
    if release and (not release['draft'] or release['target_commitish'] != sha):
        raise ValueError('Reserved release changed target or was published; refusing overwrite')
    return release


def api(endpoint, payload=None):
    args = ['gh', 'api', endpoint]
    if payload is not None:
        args += ['--method', 'POST', '--input', '-']
    return json.loads(subprocess.check_output(args, input=json.dumps(payload) if payload is not None else None, text=True))


def pages(endpoint):
    return [item for page in json.loads(subprocess.check_output(
        ['gh', 'api', '--paginate', '--slurp', endpoint], text=True)) for item in page]


def prepare():
    repo, sha = os.environ['GH_REPO'], os.environ['MERGED_SHA']
    if repo != 'huteri/visor' or not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Only canonical huteri/visor main commits are supported')
    # A later push is fine, but deleted/rewritten/non-main commits cannot sign.
    subprocess.run(['git', 'fetch', 'origin', 'main'], check=True)
    subprocess.run(['git', 'merge-base', '--is-ancestor', sha, 'FETCH_HEAD'], check=True)
    number = None
    for attempt in range(6):  # GitHub association/merged metadata can lag push delivery.
        number = eligible_pr(pages(f'repos/{repo}/commits/{sha}/pulls'), sha, repo)
        if number:
            break
        if attempt < 5:
            time.sleep(10)
    if not number:
        print('No exact merged PR: skip (direct push or nonmerge).')
        return
    prefix = f'repos/{repo}'
    for _ in range(20):
        refs = pages(f'{prefix}/git/matching-refs/tags/v')
        releases = pages(f'{prefix}/releases')  # Includes authenticated drafts.
        # Historical tags below the distribution floor are not auto-release
        # reservations. Keep them intact and allocate/reuse a safe version.
        reservations = [r for r in refs if r['object']['type'] == 'commit'
                        and r['object']['sha'] == sha
                        and VERSION.fullmatch(r['ref'].removeprefix('refs/tags/'))
                        and (code := version_code(r['ref'].removeprefix('refs/tags/'))) is not None
                        and code > BASELINE_CODE]
        if len(reservations) > 1:
            raise ValueError('Multiple version reservations for merged SHA; review manually')
        if reservations:
            tag = reservations[0]['ref'].removeprefix('refs/tags/')
            existing_draft(releases, tag, sha)
            break
        tags = [r['ref'].removeprefix('refs/tags/') for r in refs]
        tag = 'v' + next_version(tags, releases)
        try:
            api(f'{prefix}/git/refs', {'ref': f'refs/tags/{tag}', 'sha': sha})
            break
        except subprocess.CalledProcessError:
            # Only a verified competing tag is a retriable allocation collision.
            api(f'{prefix}/git/ref/tags/{tag}')
    else:
        raise RuntimeError('Version reservation contention; rerun workflow')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'eligible=true\nname={tag[1:]}\ncode={version_code(tag)}\npr={number}\n')


if __name__ == '__main__':
    prepare()
