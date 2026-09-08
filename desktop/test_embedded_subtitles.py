import unittest
from embedded_subtitles import parse_srt, choose_track, align_tracks, speech_segments
from types import SimpleNamespace


class EmbeddedSubtitleTests(unittest.TestCase):
    def test_whisper_cue_is_split_at_silence_using_real_word_times(self):
        segment = SimpleNamespace(start=17, end=124, text='前 後', words=[
            SimpleNamespace(start=17,end=18,word='前'), SimpleNamespace(start=122,end=124,word='後')])
        cues = speech_segments(segment)
        self.assertEqual([(17000,18000),(122000,124000)],[(c['startMillis'],c['endMillis']) for c in cues])

    def test_unreliable_long_word_is_rejected_not_given_invented_times(self):
        segment = SimpleNamespace(words=[SimpleNamespace(start=17,end=124,word='前')])
        with self.assertRaises(ValueError): speech_segments(segment)

    def test_preserves_gaps_and_milliseconds(self):
        cues = parse_srt('1\n00:00:20,603 --> 00:00:22,063\n<i>日本語</i>\n\n2\n00:02:00,001 --> 00:02:01,020\n次\n')
        self.assertEqual([20603, 120001], [c['startMillis'] for c in cues])
        self.assertEqual('日本語', cues[0]['text'])
        self.assertEqual(22063, cues[0]['endMillis'])

    def test_prefers_european_dialogue_not_dubtitles_or_forced(self):
        streams = [{'index': i, 'codec_type': 'subtitle', 'codec_name': 'subrip',
                    'tags': {'language': 'spa', 'title': title}} for i,title in enumerate(
                        ['Latin American', 'European (Dubtitle)', 'European', 'Forced'])]
        self.assertEqual(2, choose_track(streams, {'spa'}, spanish=True)['index'])

    def test_does_not_merge_dialogue_across_opening_gap(self):
        jp = [{'startMillis': 1000, 'endMillis': 2000, 'text': '一つ'}, {'startMillis': 110000, 'endMillis': 111000, 'text':'二つ'}]
        es = [{'startMillis': 1100, 'endMillis': 2100, 'text': 'Uno'}, {'startMillis': 110050, 'endMillis': 111050, 'text':'Dos'}]
        result = align_tracks(jp, es)
        self.assertEqual(['Uno', 'Dos'], [s['spanish'] for s in result])
        self.assertEqual([1000, 110000], [s['startMillis'] for s in result])
        self.assertTrue(all(s['endMillis']-s['startMillis']==1000 for s in result))

    def test_ignores_small_overlap_with_neighbour(self):
        jp=[{'startMillis':1000,'endMillis':2000,'text':'日本語'}]
        es=[{'startMillis':0,'endMillis':1050,'text':'Anterior'}, {'startMillis':1100,'endMillis':2050,'text':'Correcto'}]
        self.assertEqual('Correcto',align_tracks(jp,es)[0]['spanish'])

if __name__ == '__main__': unittest.main()
