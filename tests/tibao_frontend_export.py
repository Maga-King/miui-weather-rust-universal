"""Reproduce the Android Chinese frontend assets from the approved Genie checkout.

Run with .build/tibao-voice/venv/Scripts/python.exe. No original weights or source
files are changed. The BiLSTM is the original g2pM model, not a replacement voice.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import importlib.util
import json
import pickle
import shutil
import struct
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from g2pM import G2pM
from pypinyin import Style, lazy_pinyin
from pypinyin.contrib.tone_convert import to_finals_tone3, to_initials

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / '.build/tibao-voice/source/src/genie_tts'
DATA = ROOT / '.build/tibao-voice/genie-data'
OUT = ROOT / 'port/assets/tibao_frontend'


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


class Writer:
    def __init__(self, file):
        self.file = file

    def integer(self, n):
        self.file.write(struct.pack('>i', n))

    def real(self, value):
        self.file.write(struct.pack('>d', value))

    def text(self, value):
        data = value.encode('utf8')
        self.integer(len(data))
        self.file.write(data)

    def strings(self, values):
        self.integer(len(values))
        for value in values:
            self.text(value)

    def mapping(self, values):
        self.integer(len(values))
        for key, value in sorted(values.items()):
            self.text(key)
            self.strings(value)


def phones_for(pinyin, mapping):
    pinyin = pinyin.replace('u:', 'v')
    c = to_initials(pinyin)
    v = to_finals_tone3(pinyin, neutral_tone_with_five=True)
    if not v or not v[-1].isdigit():
        return None
    final, tone = v[:-1], v[-1]
    if c:
        key = c + {'uei': 'ui', 'iou': 'iu', 'uen': 'un'}.get(final, final)
    elif final in {'ing': 'ying', 'i': 'yi', 'in': 'yin', 'u': 'wu'}:
        key = {'ing': 'ying', 'i': 'yi', 'in': 'yin', 'u': 'wu'}[final]
    else:
        key = {'v': 'yu', 'e': 'e', 'i': 'y', 'u': 'w'}.get(final[:1], final[:1]) + final[1:]
    if key not in mapping:
        return None
    c, v = mapping[key].split()
    return [c, v + tone]


def write_hmm(writer, states, starts, transitions, emissions, char_states):
    names = sorted(states)
    index = {state: i for i, state in enumerate(names)}
    writer.integer(len(names))
    for state in names:
        writer.text(state[0] if isinstance(state, tuple) else state)
        writer.text(state[1] if isinstance(state, tuple) else '')
        writer.real(starts[state])
        row = transitions.get(state, {})
        writer.integer(len(row))
        for target, probability in sorted(row.items()):
            writer.integer(index[target])
            writer.real(probability)
        row = emissions.get(state, {})
        writer.integer(len(row))
        for char, probability in sorted(row.items()):
            writer.integer(ord(char))
            writer.real(probability)
    writer.integer(len(char_states))
    for char, possible in sorted(char_states.items()):
        writer.integer(ord(char))
        writer.integer(len(possible))
        for state in sorted(possible):
            writer.integer(index[state])


def export():
    OUT.mkdir(parents=True, exist_ok=True)
    g2p = G2pM()
    gdir = Path(sys.modules['g2pM']. __file__).parent
    state = pickle.load(open(gdir / 'np_ckpt.pkl', 'rb'))

    class Net(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.embedding = torch.nn.Embedding(5398, 64)
            self.lstm = torch.nn.LSTM(64, 32, batch_first=True, bidirectional=True)
            self.logit_layer = torch.nn.Sequential(torch.nn.Linear(64, 32), torch.nn.ReLU(), torch.nn.Linear(32, 876))
            self.load_state_dict({key: torch.from_numpy(value) for key, value in state.items()})

        def forward(self, ids):
            return self.logit_layer(self.lstm(self.embedding(ids))[0]).argmax(dim=-1)

    net = Net().eval()
    example = torch.tensor([[g2p.char2idx['시'], 100, 101, g2p.char2idx['끝']]], dtype=torch.long)
    with torch.no_grad():
        torch.onnx.export(net, (example,), str(OUT / 'g2pm.onnx'), input_names=['input_ids'], output_names=['pronunciation_ids'],
                          dynamic_axes={'input_ids': {1: 'characters'}, 'pronunciation_ids': {1: 'characters'}}, opset_version=17, dynamo=False)

    # Neural disambiguation must agree with the original numpy implementation.
    session = ort.InferenceSession(str(OUT / 'g2pm.onnx'), providers=['CPUExecutionProvider'])
    corpus = ['重庆今天多云，最高气温二十六度。', '长春市长时间降雪，请注意保暖。', '银行旁边的朝阳区有大风。',
              '哈尔滨零下十五度，空气质量良好。', '成都天气晴朗，降水概率百分之六十。', '吐鲁番气温升高，部分地区有沙尘暴。']
    for sent in corpus:
        ids = np.array([[g2p.char2idx['시']] + [g2p.char2idx.get(c, g2p.char2idx['<UNK>']) for c in sent] + [g2p.char2idx['끝']]], dtype=np.int64)
        predicted = session.run(None, {'input_ids': ids})[0][0]
        expected = g2p(sent, char_split=True)
        for i, c in enumerate(sent):
            if len(g2p.cedict.get(c, [])) > 1:
                assert g2p.idx2class[int(predicted[i + 1])] == expected[i], (sent, c)

    corrections = pickle.load(open(DATA / 'G2P/ChineseG2P/polyphonic.pickle', 'rb'))
    strict = dict(line.strip().split('\t', 1) for line in (DATA / 'G2P/ChineseG2P/opencpop-strict.txt').read_text(encoding='utf8').splitlines())
    symbols = module('tibao_symbols', SOURCE / 'G2P/SymbolsV2.py').symbols_v2
    defaults = {c: lazy_pinyin(c, style=Style.TONE3, neutral_tone_with_five=True)[0] for c in g2p.cedict if len(c) == 1}
    all_pinyins = set(g2p.idx2class.values()) | set(defaults.values())
    for values in list(g2p.cedict.values()) + list(corrections.values()):
        all_pinyins.update(values)
    # Sandhi changes a syllable's tone even if that combination does not occur
    # in the character dictionary or neural classifier's pronunciation classes.
    for p in list(all_pinyins):
        if p[-1:].isdigit():
            all_pinyins.update(p[:-1] + str(t) for t in range(1, 6))
    pinyin_phones = {p: phones_for(p, strict) for p in all_pinyins}
    pinyin_phones = {p: v for p, v in pinyin_phones.items() if v is not None}
    conversion = module('tibao_char_convert', SOURCE / 'G2P/Chinese/Normalization/char_convert.py').t2s_dict

    with gzip.GzipFile(filename=str(OUT / 'lexicon.bin.gz'), mode='wb', mtime=0) as file:
        w = Writer(file)
        w.integer(0x54424631)
        w.strings(symbols)
        w.integer(len(g2p.char2idx))
        for key, value in sorted(g2p.char2idx.items()):
            w.text(key); w.integer(value)
        w.strings([g2p.idx2class[i] for i in range(len(g2p.idx2class))])
        w.mapping(g2p.cedict)
        w.mapping(corrections)
        w.mapping(pinyin_phones)
        w.mapping({c: [p] for c, p in defaults.items()})
        w.mapping({c: [v] for c, v in conversion.items()})
        # Full DAG frequencies and POS tags keep city and compound word boundaries.
        import jieba
        from jieba import posseg, finalseg
        jieba.initialize()
        entries = []
        for line in Path(jieba.__file__).with_name('dict.txt').read_text(encoding='utf8').splitlines():
            word, freq, tag = line.split()
            entries.append((word, int(freq), tag))
        w.integer(len(entries))
        for word, freq, tag in entries:
            w.text(word); w.integer(freq); w.text(tag)
        write_hmm(w, posseg.start_P, posseg.start_P, posseg.trans_P, posseg.emit_P, posseg.char_state_tab_P)
        write_hmm(w, finalseg.start_P, finalseg.start_P, finalseg.trans_P, finalseg.emit_P, {})
    shutil.copyfile(DATA / 'RoBERTa/roberta_tokenizer/vocab.txt', OUT / 'vocab.txt')
    import jieba as jieba_module
    sys.modules['jieba_fast'] = jieba_module
    sandhi = module('tibao_tone_sandhi', SOURCE / 'G2P/Chinese/ToneSandhi.py').ToneSandhi()
    (OUT / 'tone_words.txt').write_text(' '.join(sorted(sandhi.must_neural_tone_words)) + '\n' + ' '.join(sorted(sandhi.must_not_neural_tone_words)) + '\n', encoding='utf8')
    files = {}
    for file in sorted(OUT.iterdir()):
        if file.name != 'manifest.json' and file.is_file():
            files[file.name] = {'bytes': file.stat().st_size, 'sha256': hashlib.sha256(file.read_bytes()).hexdigest()}
    (OUT / 'manifest.json').write_text(json.dumps({'format': 1, 'g2pm': 'original np_ckpt.pkl BiLSTM', 'bertLayout': '[phones,1024]', 'boundary': 'Leading sentence boundary matching the approved V2ProPlus 。+text path',
              'unsupported': 'Unknown pronunciation, unrecognized Latin abbreviation, supplementary Chinese character: explicit exception; no zero BERT substitute.',
              'normalization': 'Chinese weather numbers, signed temperatures, percentages, dates, times, wind/rain units. mm/km/h and common weather abbreviations repaired before upstream normalization.',
              'files': files}, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    print(json.dumps(files, indent=2))


if __name__ == '__main__':
    export()
