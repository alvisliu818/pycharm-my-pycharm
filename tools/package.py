# 打包插件 zip：my-pycharm/lib/my-pycharm-<version>.jar（META-INF/plugin.xml + classes + icons）
# 版本号从 src/main/resources/META-INF/plugin.xml 的 <version> 读取，不再硬编码（0.2.5 前曾硬编码 0.2.2 过时值）
import re
import sys
import zipfile
from pathlib import Path

root = Path(sys.argv[1])
classes = Path(sys.argv[2])
plugin_xml = root / 'src' / 'main' / 'resources' / 'META-INF' / 'plugin.xml'
m = re.search(r'<version>([^<]+)</version>', plugin_xml.read_text(encoding='utf-8'))
if not m:
    raise SystemExit(f'打包失败：{plugin_xml} 里找不到 <version>')
version = m.group(1).strip()
jar_name = f'my-pycharm-{version}.jar'
out_zip = root / f'my-pycharm-{version}.zip'
res = plugin_xml.parent.parent

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
