from pathlib import Path
import argparse, hashlib, json, os, re, shutil, zipfile
root = Path(__file__).resolve().parents[1]
version = 'v' + json.loads((root / 'integrations/mcp/package.json').read_text(encoding='utf-8'))['version']
os.chdir(root)
parser = argparse.ArgumentParser(description='Package a built and verified Sunlith APK and companion.')
parser.add_argument('--build-type', choices=['debug', 'distribution'], default='distribution',
                    help='APK variant to package (default: distribution)')
parser.add_argument('--apk', type=Path, help='Verified APK path, relative to the repository root or absolute; must match the selected build output')
args = parser.parse_args()

def require(condition, message):
    if not condition:
        raise ValueError(message)

android_build = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')
require(f'versionName = "{version[1:]}"' in android_build, 'Android and companion versions must match')
metadata_path = root / 'app/build/outputs/apk' / args.build_type / 'output-metadata.json'
metadata = json.loads(metadata_path.read_text(encoding='utf-8'))
require(metadata['variantName'] == args.build_type and metadata['applicationId'] == 'io.github.sunsind.sunlith', 'APK metadata must match the selected variant and application')
require(len(metadata['elements']) == 1 and not metadata['elements'][0]['filters'], 'Packaging requires one universal APK')
element = metadata['elements'][0]
require(element['versionName'] == version[1:] and element['versionCode'] == int(re.search(r'versionCode\s*=\s*(\d+)', android_build)[1]), 'Rebuild the APK for this version before packaging')
built_apk = metadata_path.parent / element['outputFile']
input_apk = (args.apk or built_apk).resolve()
require(input_apk.is_file() and built_apk.is_file(), 'Build the selected APK before packaging')
require(hashlib.sha256(input_apk.read_bytes()).digest() == hashlib.sha256(built_apk.read_bytes()).digest(), 'The verified APK must exactly match the selected variant build output')
source_files = [p for folder in ['app/src/main', f'app/src/{args.build_type}', 'gradle'] for p in (root / folder).rglob('*') if p.is_file()]
source_files += [p for folder in [root, root / 'app'] for pattern in ['*.gradle', '*.gradle.kts', '*.pro', 'gradle.properties'] for p in folder.glob(pattern) if p.is_file()]
require(all(p.stat().st_mtime_ns <= built_apk.stat().st_mtime_ns for p in source_files), 'Source changed after the APK was built; rebuild and retest before packaging')
print('Packaging', args.build_type, 'APK:', input_apk)
dist = root / 'dist'
dist.mkdir(exist_ok=True)
apk = dist / f'Sunlith-{version}.apk'
shutil.copy2(input_apk, apk)
archive = dist / f'Sunlith-companion-{version}.zip'
prefix = f'Sunlith-companion-{version}/'
start = (root / 'integrations/mcp/START-HERE.md').read_text(encoding='utf-8')
files = [Path(p) for p in ['LICENSE','NOTICE','SECURITY.md','CHANGELOG.md','docs/AI-INTEGRATION.md','docs/RESEARCH-AI.md','docs/RESEARCH-CALENDAR.md','docs/RESEARCH-CONNECTION-UX.md','docs/RESEARCH-REFINEMENT.md','docs/TESTING.md','docs/THIRD-PARTY.md','integrations/mcp/package.json','integrations/mcp/package-lock.json','integrations/mcp/README.md']]
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
