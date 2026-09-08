"""Build the paper using the unmodified organizer template and paper-content.json."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from lxml import etree as E
import copy
import json
import re

OUT = Path(__file__).resolve().parent
data = json.loads((OUT / 'paper-content.json').read_text())
# Omit references no longer cited after an editorial revision and number by appearance.
mapping = {}
def renumber(match):
    old = int(match.group(1))
    if old not in mapping:
        mapping[old] = len(mapping) + 1
    return '[' + str(mapping[old]) + ']'
for section in data['sections']:
    section['paragraphs'] = [re.sub(r'\[(\d+)\]', renumber, text) for text in section['paragraphs']]
references = {int(re.match(r'\[(\d+)\]', text).group(1)): text for text in data['references']}
data['references'] = [re.sub(r'^\[\d+\]', '[' + str(new) + ']', references[old]) for old, new in mapping.items()]
with ZipFile(OUT / 'official-template.odt') as z:
    files = {n: z.read(n) for n in z.namelist()}
root = E.fromstring(files['content.xml'])
ns = root.nsmap

def q(name):
    prefix, local = name.split(':')
    return '{' + ns[prefix] + '}' + local

body = root.find('office:body/office:text', ns)
declarations = copy.deepcopy(body[0])
for element in list(body):
    body.remove(element)
body.append(declarations)

def paragraph(text, style, heading=False):
    element = E.SubElement(body, q('text:h' if heading else 'text:p'))
    element.set(q('text:style-name'), style)
    if heading:
        element.set(q('text:outline-level'), str(int(heading)))
    element.text = text

paragraph(data['title'], 'P1')
paragraph('Leanid Piliptsevich', 'P2')
paragraph('Independent researcher', 'University')
paragraph('Corresponding author: pilleo19@gmail.com', 'University')
paragraph('Abstract', 'Abstract_20_Title')
paragraph(data['abstract'], 'Abstract_20_Text')
paragraph('Keywords', 'Keywords_20_title')
paragraph(data['keywords'], 'Keywords_20_words')
for section in data['sections']:
    numbered = section['heading'] != 'Declaration on generative AI'
    level = section.get('level', 1) if numbered else 0
    style = ('P8' if level == 1 else 'P11') if numbered else 'Acknowledgements_20__28_Heading_29_'
    paragraph(section['heading'], style, level)
    for index, text in enumerate(section['paragraphs']):
        paragraph(text, 'P9' if index == 0 else 'Standard')
paragraph('References', 'References_20__28_Heading_29_')
for reference in data['references']:
    paragraph(reference, 'reference')
files['content.xml'] = E.tostring(root, xml_declaration=True, encoding='UTF-8')
metadata = E.fromstring(files['meta.xml'])
meta = metadata.find('office:meta', ns)
for element in list(meta):
    meta.remove(element)
E.SubElement(meta, '{http://purl.org/dc/elements/1.1/}title').text = data['title']
E.SubElement(meta, '{http://purl.org/dc/elements/1.1/}creator').text = 'Leanid Piliptsevich'
files['meta.xml'] = E.tostring(metadata, xml_declaration=True, encoding='UTF-8')
with ZipFile(OUT / 'mazewall_SMICS2026_DRAFT.odt', 'w') as z:
    z.writestr('mimetype', files.pop('mimetype'))
    for name, content in files.items():
        z.writestr(name, content, compress_type=ZIP_DEFLATED)
md = '# ' + data['title'] + '\n\n'
md += 'Leanid Piliptsevich · Independent researcher · pilleo19@gmail.com\n\n'
md += '**Draft for author review.**\n\n## Abstract\n\n' + data['abstract'] + '\n\n'
for section in data['sections']:
    md += '#' * (section.get('level', 1) + 1) + ' ' + section['heading'] + '\n\n' + '\n\n'.join(section['paragraphs']) + '\n\n'
md += '## References\n\n' + '\n\n'.join(data['references']) + '\n'
(OUT / 'manuscript.md').write_text(md)
print('Manuscript word count:', len(md.split()))
