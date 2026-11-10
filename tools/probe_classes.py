import glob, sys, zipfile, re

pats = (
    'openapi/wm/Content.class',
    'openapi/wm/ContentFactory.class',
    'openapi/wm/ContentManager.class',
    'fileEditor/FileEditorManagerEvent.class',
    'lookup/PrioritizedLookupElement.class',
    'openapi/ide/CopyPasteManager.class',
    'ui/SimpleToolWindowPanel.class',
    'icons/AllIcons.class',
)
roots = [r'D:\programs\PyCharm 2026.3\lib', r'D:\programs\PyCharm 2026.3\plugins']
found = {}
for r in roots:
    for j in glob.glob(r + r'\**\*.jar', recursive=True):
        try:
            with zipfile.ZipFile(j) as z:
                names = set(z.namelist())
        except Exception:
            continue
        for t in pats:
            if t in names and t not in found:
                found[t] = j
for t in pats:
    v = found.get(t)
    print(t, '->', v if v else 'MISS')

# AllIcons 内部类字段名探测
import zipfile
ai = found.get('icons/AllIcons.class')
if ai:
    with zipfile.ZipFile(ai) as z:
        names = [n for n in z.namelist() if n.startswith('com/intellij/icons/AllIcons$') and n.endswith('.class')]
    groups = {}
    for n in names:
        inner = n.split('AllIcons$')[1].split('.')[0]
        groups.setdefault(inner, 0)
        groups[inner] += 1
    print('AllIcons inner groups:', sorted(groups))
    # 打印关心的组内的具体字段
    want = {'FileTypes', 'Vcs', 'Actions', 'General', 'Nodes'}
    import re
    for n in names:
        inner = n.split('AllIcons$')[1].split('.')[0]
        if inner in want and '.' not in n.split('AllIcons$')[1].rstrip('.class'):
            data = z.read(n)
            fields = re.findall(rb'javax/swing/Icon\s+([A-Za-z0-9_]+)', data)
            # javac 产物里字段名在 constant pool；粗略抓所有可见字符串中的候选
            strs = set(s.decode() for s in re.findall(rb'[ -~]{3,}', data))
            print('==', inner, 'len', len(fields))
