import csv, json, re, sys
csv.field_size_limit(10**9)
BOOK, CH = sys.argv[1], int(sys.argv[2])
r = csv.reader(open('tables.tsv', encoding='utf-8-sig'), delimiter='\t')
h = next(r); I = {n.strip(): i for i, n in enumerate(h)}
G = lambda row, k: (row[I[k]] if len(row) > I[k] else '') or ''
def txt(s):
    s = s.replace('|', '"')
    s = re.sub(r'<[^>]*>', '', s)
    return re.sub(r'\s+', ' ', s).strip()
verses, cur, words, meta = [], None, [], {}
def flush():
    if not cur or not words: return
    body = ''
    for w in words:
        if w['text'] == '-':
            w['untranslated'] = True
            continue
        body += w['begQ'] + w['text'] + w['pnc'] + w['endQ'] + ' '
    body = re.sub(r'<[^>]*>', '', body.replace('|', '"'))
    body = re.sub(r'\s+([,.;:!?])', r'\1', re.sub(r'\s+', ' ', body)).strip()
    verses.append({**meta, 'ref': cur, 'text': body,
                   'words': [w for w in words if w['text']]})
for row in r:
    if len(row) <= I['BSB version']: continue
    vid = G(row, 'VerseId').strip()
    if vid and vid != cur:
        flush(); cur, words = vid, []
        par = G(row, 'Par')
        meta = {'heading': txt(G(row, 'Hdg')), 'xref': txt(G(row, 'Crossref')),
                'footnote': txt(G(row, 'footnotes')),
                'psalm_title': 'pshdg' in par, 'para': 'reg' in par or 'pshdg' in par}
    t = txt(G(row, 'BSB version'))
    if t:
        words.append({'text': t, 'translit': G(row, 'Translit').strip(),
                      'strongs': G(row, 'Str Heb').strip() or G(row, 'Str Grk').strip(),
                      'parsing': G(row, 'Parsing').strip(), 'lang': G(row, 'Language').strip(),
                      'orig': G(row, 'WLC / Nestle Base TR RP WH NE NA SBL').strip(),
                      'pnc': G(row, 'pnc').strip(), 'begQ': G(row, 'begQ').strip(),
                      'endQ': G(row, 'endQ').strip()})
flush()
sel = [v for v in verses if re.match(rf'^{re.escape(BOOK)} {CH}:\d+$', v['ref'])]
json.dump(sel, open(sys.argv[3], 'w'), ensure_ascii=False, indent=1)
print(f"{sys.argv[3]}: {len(sel)} verses")
