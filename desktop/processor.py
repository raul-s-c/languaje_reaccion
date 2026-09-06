"""Local episode processor. No external subtitles or paid API calls."""
import argparse
import gc
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
import site

_DLL_HANDLES = []


def configure_cuda():
    if os.name == 'nt':
        for base in site.getsitepackages():
            for directory in (Path(base) / 'nvidia').glob('*/bin'):
                os.environ['PATH'] = str(directory) + os.pathsep + os.environ.get('PATH', '')
                _DLL_HANDLES.append(os.add_dll_directory(str(directory)))

EXTENSIONS = {'.mkv', '.mp4', '.webm', '.m4v', '.avi'}


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
    temporary.replace(path)


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def probe(path):
    result = subprocess.run(['ffprobe', '-v', 'error', '-show_streams', '-show_format',
                             '-of', 'json', str(path)], capture_output=True, check=True,
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    return json.loads(result.stdout)


def discover(inputs):
    found = set()
    for item in inputs:
        path = Path(item).resolve()
        for candidate in (path.rglob('*') if path.is_dir() else [path]):
            if candidate.is_file() and candidate.suffix.lower() in EXTENSIONS:
                found.add(candidate)
    return sorted(found)


def process(video, destination, model_name='large-v3', device='cuda', log=print):
    configure_cuda()
    from faster_whisper import WhisperModel
    log(f'Inspeccionando {video.name}')
    metadata = probe(video)
    audio = [s for s in metadata['streams'] if s['codec_type'] == 'audio']
    japanese = [s for s in audio if s.get('tags', {}).get('language') in ('ja', 'jpn')]
    if not japanese and len(audio) != 1:
        raise ValueError('No hay una pista japonesa inequívoca; revisa los idiomas del archivo.')
    track = (japanese or audio)[0]
    identity = digest(video)
    folder = Path(destination) / video.parent.name
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / (video.stem + '.lrpack')
    if target.exists():
        with zipfile.ZipFile(target) as archive:
            existing = json.loads(archive.read('study.json'))
        if existing.get('videoId') == identity and existing.get('model') == model_name:
            log('Paquete ya preparado; omitido.')
            return target
        raise FileExistsError(f'Ya existe un paquete diferente: {target}')
    cache = Path(os.environ.get('LOCALAPPDATA', tempfile.gettempdir())) / 'LenguaReaccion' / 'jobs'
    cache.mkdir(parents=True, exist_ok=True)
    checkpoint = cache / f'{identity}-{model_name}.json'
    segments = json.loads(checkpoint.read_text(encoding='utf-8')) if checkpoint.exists() else []
    if not segments:
        with tempfile.TemporaryDirectory(prefix='lr-audio-') as temporary:
            wav = Path(temporary) / 'audio.wav'
            subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-i', str(video),
                            '-map', f'0:{track["index"]}', '-vn', '-sn', '-ac', '1',
                            '-ar', '16000', '-c:a', 'pcm_s16le', str(wav)], check=True,
                           creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            log(f'Transcribiendo japonés: {model_name} / {device}')
            model = WhisperModel(model_name, device=device,
                                 compute_type='int8_float16' if device == 'cuda' else 'int8')
            result, info = model.transcribe(str(wav), language='ja', beam_size=5,
                                            vad_filter=True, word_timestamps=True)
            for segment in result:
                if not segment.text.strip() or round(segment.end * 1000) <= round(segment.start * 1000):
                    continue
                segments.append({'startMillis': round(segment.start * 1000),
                                 'endMillis': round(segment.end * 1000),
                                 'japanese': segment.text.strip(), 'spanish': '', 'reading': ''})
                log(f'Transcripción {segment.end:.0f}/{info.duration:.0f} s')
            del model
            gc.collect()
        if not segments:
            raise ValueError('No se detectó habla; no se genera un paquete vacío.')
        write_json(checkpoint, segments)
    log('Cargando traducción local japonés → español (primera vez: descarga del modelo).')
    segments = [s for s in segments if s['endMillis'] > s['startMillis']]
    from transformers import AutoTokenizer
    import ctranslate2
    import torch
    torch.set_num_threads(min(8, os.cpu_count() or 1))
    from fugashi import Tagger
    translator_id = 'facebook/nllb-200-distilled-600M'
    tokenizer = AutoTokenizer.from_pretrained(translator_id, src_lang='jpn_Jpan')
    converted = cache.parent / 'nllb-600m-ct2-int8'
    if not converted.exists():
        log('Optimizando NLLB para GPU (solo la primera vez).')
        with tempfile.TemporaryDirectory(dir=cache.parent, prefix='nllb-convert-') as temporary:
            staging = Path(temporary) / 'model'
            ctranslate2.converters.TransformersConverter(translator_id).convert(str(staging), quantization='int8')
            staging.rename(converted)
        gc.collect()
    translator = ctranslate2.Translator(str(converted), device=device,
                                       compute_type='int8_float16' if device == 'cuda' else 'int8')
    tagger = Tagger()
    for index, segment in enumerate(segments):
        if not segment['spanish']:
            encoded = tokenizer.encode(segment['japanese'])
            if len(encoded) > 512:
                raise ValueError('Frase demasiado larga para traducir; requiere segmentación.')
            output = translator.translate_batch([tokenizer.convert_ids_to_tokens(encoded)],
                                                target_prefix=[['spa_Latn']], beam_size=4,
                                                max_decoding_length=256)[0].hypotheses[0]
            segment['spanish'] = tokenizer.decode(tokenizer.convert_tokens_to_ids(output), skip_special_tokens=True)
        tokens = []
        for word in tagger(segment['japanese']):
            reading = word.feature.kana or word.surface
            reading = ''.join(chr(ord(c) - 0x60) if '\u30a1' <= c <= '\u30f6' else c for c in reading)
            tokens.append({'surface': word.surface, 'reading': reading,
                           'baseForm': word.feature.lemma or word.surface,
                           'partOfSpeech': word.feature.pos1})
        segment['tokens'] = tokens
        segment['reading'] = ''.join(t['reading'] for t in tokens)
        write_json(checkpoint, segments)
        log(f'Traducido {index + 1}/{len(segments)}')
    content = {'formatVersion': 1, 'videoId': identity, 'videoFilename': video.name,
               'title': video.stem, 'series': video.parent.name, 'model': model_name,
               'translator': translator_id, 'durationMillis': round(float(metadata['format']['duration']) * 1000),
               'segments': segments}
    payload = json.dumps(content, ensure_ascii=False).encode('utf-8')
    temporary = target.with_suffix('.lrpack.partial')
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('study.json', payload)
        archive.writestr('study.sha256', hashlib.sha256(payload).hexdigest())
    temporary.replace(target)
    log(f'Preparado: {target}')
    return target


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('inputs', nargs='+')
    parser.add_argument('--output', required=True)
    parser.add_argument('--model', default='large-v3')
    parser.add_argument('--device', choices=['cuda', 'cpu'], default='cuda')
    args = parser.parse_args()
    failures = 0
    for video in discover(args.inputs):
        try:
            process(video, args.output, args.model, args.device)
        except Exception as error:
            failures += 1
            print(f'ERROR {video.name}: {error}', flush=True)
    raise SystemExit(1 if failures else 0)


if __name__ == '__main__':
    main()
