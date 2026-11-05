from pathlib import Path
import hashlib, json, os, re, shutil, zipfile
root = Path(__file__).resolve().parents[1]
version = 'v' + json.loads((root / 'integrations/mcp/package.json').read_text(encoding='utf-8'))['version']
os.chdir(root)

def require(condition, message):
    if not condition:
        raise ValueError(message)

require(f'versionName = "{version[1:]}"' in (root / 'app/build.gradle.kts').read_text(encoding='utf-8'), 'Android and companion versions must match')
metadata = json.loads((root / 'app/build/outputs/apk/debug/output-metadata.json').read_text(encoding='utf-8'))
require(metadata['elements'][0]['versionName'] == version[1:], 'Rebuild the APK for this version before packaging')
source_files = [p for folder in ['app/src/main', 'gradle'] for p in (root / folder).rglob('*') if p.is_file()]
source_files += [root / name for name in ['app/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties'] if (root / name).is_file()]
require(all(p.stat().st_mtime <= (root / 'app/build/outputs/apk/debug/app-debug.apk').stat().st_mtime for p in source_files), 'Source changed after the APK was built; rebuild and retest before packaging')
dist = root / 'dist'
dist.mkdir(exist_ok=True)
apk = dist / f'Sunlith-{version}.apk'
shutil.copy2(root / 'app/build/outputs/apk/debug/app-debug.apk', apk)
archive = dist / f'Sunlith-companion-{version}.zip'
prefix = f'Sunlith-companion-{version}/'
start = (root / 'integrations/mcp/START-HERE.md').read_text(encoding='utf-8')
files = [Path(p) for p in ['LICENSE','NOTICE','SECURITY.md','CHANGELOG.md','docs/AI-INTEGRATION.md','docs/RESEARCH-AI.md','docs/RESEARCH-CALENDAR.md','docs/THIRD-PARTY.md','integrations/mcp/package.json','integrations/mcp/package-lock.json','integrations/mcp/README.md']]
files += sorted(Path('integrations/mcp/src').glob('*.js'))
files += sorted(Path('integrations/mcp/test').glob('*.js'))
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr(prefix + 'START-HERE.md', start)
    for p in files: z.write(p, prefix + p.as_posix())
with zipfile.ZipFile(archive) as z:
    for p in files: require(z.read(prefix+p.as_posix()) == p.read_bytes(), str(p))
    require(not any(re.search(r'\.(png|jpe?g|webp|gif|mp4)$', n, re.I) for n in z.namelist()), 'Media must not be packaged before approval')
    for name in z.namelist():
        if not name.endswith('.md'): continue
        content = z.read(name).decode('utf-8')
        for target in re.findall(r'\]\(([^)]+)\)', content):
            if re.match(r'^[a-z]+:', target) or target.startswith('#'): continue
            clean = target.split('#')[0]
            from posixpath import normpath, join, dirname
            resolved = normpath(join(dirname(name),clean))
            require(resolved in z.namelist() or any(n.startswith(resolved.rstrip('/')+'/') for n in z.namelist()), (name,target))
lines=[]
for p in [apk,archive]:
    digest=hashlib.sha256(p.read_bytes()).hexdigest()
    lines.append(f'{digest}  {p.name}\n')
    print(p.name, p.stat().st_size, digest)
(dist/'SHA256SUMS.txt').write_bytes(''.join(lines).encode('ascii'))
print('Archive source equality, image exclusion and Markdown links verified.')
