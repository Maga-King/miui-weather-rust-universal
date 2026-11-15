# Chinese speech frontend materials

`port/assets/tibao_frontend/` contains general Chinese pronunciation and tokenizer
resources. It does not contain Tribbie character voice weights, user recordings,
reference-audio tensors, or synthesized character speech. The project converts
the following upstream resources into a compressed lexicon and a small ONNX
pronunciation model; conversion does not replace their licenses.

| Material | Source and terms |
| --- | --- |
| Genie-TTS Chinese G2P, SymbolsV2, pronunciation correction dictionary and shared data | [High-Logic/Genie-TTS](https://github.com/High-Logic/Genie-TTS), source revision `d347fd0f8683e9a362b69f59fa0a4799ddb5e828`; shared resource repository [High-Logic/Genie](https://huggingface.co/High-Logic/Genie), whose model card declares MIT. Copyright (c) 2025 High_Logic. Complete license: `GENIE-TTS-LICENSE`. |
| GPT-SoVITS upstream inference/export logic and Chinese phoneme resources | [RVC-Boss/GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS); Copyright (c) 2024 RVC-Boss; MIT. Complete license: `GPT-SOVITS-LICENSE`. This code license covers upstream software; it does not grant distribution rights to third-party trained character weights. |
| g2pM 0.1.2.5 pronunciation BiLSTM, character and class tables | [kakaobrain/g2pM](https://github.com/kakaobrain/g2pM), by Kyubyong Park and Seanie Lee; Apache-2.0, complete license: `G2PM-LICENSE`. `g2pm.onnx` converts the packaged `np_ckpt.pkl` parameters; the original model architecture and weights are retained. |
| Jieba 0.42.1 dictionary and POS/word HMM probability tables | [fxsjy/jieba](https://github.com/fxsjy/jieba); Copyright (c) 2013 Sun Junyi; MIT, complete license: `JIEBA-LICENSE`. Exported dictionary frequencies and HMM tables are used by the Java port. |
| pypinyin 0.55.0 pronunciation conversion and default character tones | [mozillazg/python-pinyin](https://github.com/mozillazg/python-pinyin); Copyright (c) 2016 mozillazg, 闲耘; MIT, complete license: `PYPINYIN-LICENSE`. Underlying single-character pronunciation data comes from [mozillazg/pinyin-data](https://github.com/mozillazg/pinyin-data), Copyright (c) 2016 mozillazg; MIT, complete license: `PINYIN-DATA-LICENSE`. |
| Chinese text normalization, traditional conversion and tone-sandhi lists/logic | PaddlePaddle authors, Copyright (c) 2020 and 2021. Original Apache-2.0 copyright headers occur in the Genie source copies. Complete license: `APACHE-LICENSE`; retained copyright attribution: `PADDLE-FRONTEND-NOTICE`. The Java port adds weather units, cardinal-number handling and explicit alignment validation. |
| Chinese RoBERTa WordPiece vocabulary | Shared Genie RoBERTa tokenizer, originating from [hfl/chinese-roberta-wwm-ext-large](https://huggingface.co/hfl/chinese-roberta-wwm-ext-large). Its model card declares Apache-2.0 and credits [ymcui/Chinese-BERT-wwm](https://github.com/ymcui/Chinese-BERT-wwm), based on Google BERT. Complete license: `APACHE-LICENSE`; source attribution: `HFL-BERT-NOTICE`. Only `vocab.txt` is in the APK. |

## CEDICT-derived data

The compressed lexicon also contains the character pronunciation lists exported
from g2pM's `digest_cedict.pkl`. These must not be described as new project data
covered solely by the repository's MIT license. CC-CEDICT contributors and the
original CEDICT project (Paul Denisowski, 1997) are credited for the upstream
dictionary; source: [CC-CEDICT](https://cc-cedict.org/).

g2pM's packaged file does not identify its original dictionary snapshot or
license version. CC-CEDICT historically used CC-BY-SA 3.0 Unported and currently
publishes CC-BY-SA 4.0 International. The bundled historical CEDICT-derived
pronunciation subset retains the applicable upstream attribution and ShareAlike
terms; no relicensing of that subset to MIT or Apache-2.0 is claimed. Both
license texts are preserved as `CC-CEDICT-BY-SA-3.0` and
`CC-CEDICT-BY-SA-4.0`. Changes to the subset consist of extraction of character
pronunciation lists and serialization into `lexicon.bin.gz`; these data changes
retain the applicable ShareAlike terms. The other independent components keep
their own licenses. The upstream snapshot/version remains unverified.

## Character models and references

`port/assets/tibao_models/manifest.json` contains file names, sizes and hashes
for a private import; it contains no model parameter values or audio tensors.
The character model collection's terms are recorded in
`TIBAO-PRIVATE-MODEL-TERMS.md`. Character weights and their converted derivatives,
original/reference recordings and the user's reference tensors are not part of
the public source or release attachments. General inference-code licenses do
not grant rights to those materials.
