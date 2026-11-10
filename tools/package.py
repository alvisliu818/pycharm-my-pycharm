# 打包插件 zip：my-pycharm/lib/my-pycharm-<version>.jar（META-INF/plugin.xml + classes + icons）
import sys, zipfile, os
from pathlib import Path

root = Path(sys.argv[1])
classes = Path(sys.argv[2])
version = '0.2.1'
jar_name = f'my-pycharm-{version}.jar'
out_zip = root / f'my-pycharm-{version}.zip'
res = root / 'src' / 'main' / 'resources'

jar_tmp = root / 'build' / jar_name
with zipfile.ZipFile(jar_tmp, 'w', zipfile.ZIP_DEFLATED) as jar:
    for p in sorted(classes.rglob('*.class')):
        jar.write(p, p.relative_to(classes).as_posix())
    for p in sorted(res.rglob('*')):
        if p.is_file():
            jar.write(p, p.relative_to(res).as_posix())

with zipfile.ZipFile(out_zip, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(jar_tmp, f'my-pycharm/lib/{jar_name}')

print(f'zip: {out_zip} ({out_zip.stat().st_size} bytes)')
