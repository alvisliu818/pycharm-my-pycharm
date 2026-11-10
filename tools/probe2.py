import glob, zipfile

names_wanted = (
    'CopyPasteManager',
    'SimpleToolWindowPanel',
    'ContentFactory',
    'ContentManager.class',
    '/wm/Content.class',
    'FileEditorManagerEvent',
    'PrioritizedLookupElement',
    'ToolWindowPanel',
)
roots = [r'D:\programs\PyCharm 2026.3\lib', r'D:\programs\PyCharm 2026.3\plugins']
hits = {}
count = 0
for r in roots:
    for j in glob.glob(r + r'\**\*.jar', recursive=True):
        count += 1
        try:
            with zipfile.ZipFile(j) as z:
                nl = z.namelist()
        except Exception:
            continue
        for n in nl:
            if not n.endswith('.class'):
                continue
            for w in names_wanted:
                if w in n and (w, n) not in hits and len(hits) < 120:
                    hits[(w, n)] = j
print('scanned jars:', count)
for (w, n), j in sorted(hits.items()):
    print(w, '|', n, '|', j.replace('D:\\programs\\PyCharm 2026.3\\', ''))
