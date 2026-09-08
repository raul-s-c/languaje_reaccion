"""Read existing text tracks; retain their timestamps instead of re-recognising dialogue."""
import html
import re
import subprocess
import os
import json
import hashlib
import zipfile
import shutil
import time
from pathlib import Path


def choose_track(streams, languages, spanish=False):
    candidates = []
    for stream in streams:
        tags = {k.lower(): str(v).lower() for k, v in stream.get('tags', {}).items()}
        title = tags.get('title', '')
        if (stream.get('codec_type') != 'subtitle' or tags.get('language') not in languages
                or stream.get('codec_name') not in {'subrip', 'ass', 'ssa', 'webvtt', 'mov_text'}
                or stream.get('disposition', {}).get('forced') or 'forced' in title or 'dubtitle' in title):
            continue
        score = (10 if spanish and any(t in title for t in ('european', 'castell', 'spain')) else 0)
        score -= 2 if 'sdh' in title or 'hearing' in title else 0
        candidates.append((score, -stream['index'], stream))
    return max(candidates, key=lambda item: item[:2])[2] if candidates else None


def parse_srt(text):
    cues = []
    def millis(parts):
        h, m, s, ms = map(int, parts)
        return ((h * 60 + m) * 60 + s) * 1000 + ms
    for block in re.split(r'\n\s*\n', text.replace('\r', '').lstrip('\ufeff').strip()):
        match = re.search(r'(\d+):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d+):(\d{2}):(\d{2})[,.](\d{3})[^\n]*\n([\s\S]*)', block)
        if not match:
            continue
        start, end = millis(match.groups()[:4]), millis(match.groups()[4:8])
        content = html.unescape(re.sub(r'<[^>]*>|\{\\[^}]*\}', '', match.group(9))).strip()
        if content and end > start:
            cues.append({'startMillis': start, 'endMillis': end, 'text': content})
    return sorted(cues, key=lambda cue: (cue['startMillis'], cue['endMillis']))


def extract_track(video, track):
    output = subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-i', str(video),
        '-map', f'0:{track["index"]}', '-f', 'srt', 'pipe:1'], check=True, capture_output=True,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    return parse_srt(output.stdout.decode('utf-8-sig'))


def align_tracks(japanese, spanish):
    result = []
    cursor = 0
    for cue in japanese:
        while cursor < len(spanish) and spanish[cursor]['endMillis'] <= cue['startMillis']:
            cursor += 1
        translations = []
        for other in spanish[cursor:]:
            if other['startMillis'] >= cue['endMillis']:
                break
            overlap = min(cue['endMillis'], other['endMillis']) - max(cue['startMillis'], other['startMillis'])
            # Tiny boundary overlaps belong to neighbouring dialogue, not this line.
            if overlap > 0 and overlap / min(cue['endMillis']-cue['startMillis'], other['endMillis']-other['startMillis']) >= .5:
                # All-uppercase on-screen signs are not translations of short Japanese interjections.
                if len(cue['text']) <= 5 and other['text'].isupper():
                    continue
                if other['text'] not in translations:
                    translations.append(other['text'])
        result.append({'startMillis': cue['startMillis'], 'endMillis': cue['endMillis'],
            'japanese': cue['text'], 'spanish': '\n'.join(translations), 'reading': ''})
    return result


def embedded_segments(video, metadata):
    jp = choose_track(metadata['streams'], {'ja', 'jpn'})
    es = choose_track(metadata['streams'], {'es', 'spa'}, spanish=True)
    if not jp or not es:
        return None
    japanese, spanish = extract_track(video, jp), extract_track(video, es)
    if not japanese or not spanish:
        return None
    return align_tracks(japanese, spanish), {'type': 'embedded-subtitles',
        'japaneseTrack': jp['index'], 'spanishTrack': es['index'],
        'spanishTitle': es.get('tags', {}).get('title', ''), 'alignment': 'time-overlap'}


def speech_segments(segment):
    """Do not stretch a recognised line across a VAD-removed opening or silence."""
    words = [word for word in (segment.words or []) if word.word.strip() and word.end > word.start]
    if not words:
        if not segment.text.strip() or segment.end <= segment.start:
            return []
        if segment.end - segment.start > 15:
            raise ValueError('Whisper devolvió una frase de más de 15 segundos sin tiempos por palabra fiables.')
        return [{'startMillis':round(segment.start*1000),'endMillis':round(segment.end*1000),
                 'japanese':segment.text.strip(),'spanish':'','reading':''}]
    result, group = [], []
    def flush():
        if group:
            result.append({'startMillis':round(group[0].start*1000),'endMillis':round(group[-1].end*1000),
                           'japanese':''.join(word.word for word in group).strip(),'spanish':'','reading':''})
            group.clear()
    for word in words:
        if word.end-word.start > 15:
            raise ValueError('Whisper asignó más de 15 segundos a una palabra. No se guardan tiempos defectuosos.')
        if group and (word.start-group[-1].end > .8 or word.end-group[0].start > 7
                      or sum(len(w.word) for w in group)+len(word.word) > 60):
            flush()
        group.append(word)
    flush()
    return [cue for cue in result if cue['endMillis'] > cue['startMillis']]


def build_embedded_package(video, destination, metadata, identity, log=print):
    available = (choose_track(metadata['streams'], {'ja', 'jpn'}) and
                 choose_track(metadata['streams'], {'es', 'spa'}, spanish=True))
    if not available:
        return None
    folder = Path(destination) / video.parent.name
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / (video.stem + '.lrpack')
    if target.exists():
        with zipfile.ZipFile(target) as archive:
            payload = archive.read('study.json')
            previous = json.loads(payload)
            if previous.get('videoId') != identity:
                raise FileExistsError(f'El paquete existente pertenece a otro vídeo: {target}')
            if (previous.get('generatorVersion') == 2 and previous.get('source', {}).get('type') == 'embedded-subtitles'
                    and archive.read('study.sha256').decode().strip() == hashlib.sha256(payload).hexdigest()):
                log('Paquete de pistas del vídeo ya preparado; omitido.')
                return target
    log(f'Extrayendo subtítulos japoneses y españoles incluidos en {video.name}')
    extracted = embedded_segments(video, metadata)
    if extracted is None:
        raise ValueError('Las pistas de subtítulos están vacías o no se pueden leer.')
    segments, source = extracted
    try:
        from fugashi import Tagger
    except ImportError:
        tagger = None
    else:
        tagger = Tagger()
    if tagger:
        for segment in segments:
            tokens = []
            for word in tagger(segment['japanese']):
                reading = word.feature.kana or word.surface
                reading = ''.join(chr(ord(c)-0x60) if '\u30a1' <= c <= '\u30f6' else c for c in reading)
                tokens.append({'surface':word.surface,'reading':reading,'baseForm':word.feature.lemma or word.surface,'partOfSpeech':word.feature.pos1})
            segment['tokens'] = tokens
            segment['reading'] = ''.join(t['reading'] for t in tokens)
    content = {'formatVersion':1,'generatorVersion':2,'videoId':identity,'videoFilename':video.name,
        'title':video.stem,'series':video.parent.name,'model':'embedded-subtitles',
        'translator':'embedded-spanish-track','source':source,
        'durationMillis':round(float(metadata['format']['duration'])*1000),'segments':segments}
    payload = json.dumps(content,ensure_ascii=False).encode('utf-8')
    temporary = target.with_suffix('.lrpack.partial')
    with zipfile.ZipFile(temporary,'w',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('study.json',payload)
        archive.writestr('study.sha256',hashlib.sha256(payload).hexdigest())
    if target.exists():
        shutil.copy2(target,target.with_name(target.name+f'.backup-{time.time_ns()}'))
    temporary.replace(target)
    log(f'Preparado: {len(segments)} subtítulos de las pistas del vídeo: {target}')
    return target
