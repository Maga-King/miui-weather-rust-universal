Tibao V2ProPlus Android Chinese frontend, format 1

g2pm.onnx is a faithful export of the original g2pM 0.1.2.5 BiLSTM weights.
lexicon.bin.gz contains original g2pM character pronunciations/classes, Genie
polyphonic corrections, Opencpop phone pairs and SymbolsV2 IDs, pypinyin default
tones, traditional conversion, and complete Jieba dictionary/POS/word HMM data.
vocab.txt is the original Chinese RoBERTa tokenizer vocabulary. tone_words.txt
contains the upstream Paddle tone-sandhi lists.

The engine supplies the original RoBERTa model. Features are real, finite,
nonzero phone-major [N,1024] floats. A leading sentence boundary matches the
approved V2ProPlus synthesis path. Unknown pronunciation/Latin abbreviations
and supplementary characters cause explicit errors. No silent phoneme drop or
zero BERT substitute is permitted.

Reproduce assets: tests/tibao_frontend_export.py in the private voice Python venv.
Verify Java/Python parity: tests/tibao_frontend_parity.py. The validation uses
original phones, word2ph and tokenizer IDs for dynamic Chinese weather, cities,
dates/times/temperatures/percentages/wind/rain/air-pressure units and float16
RoBERTa features. Weather unit and cardinal number repairs precede upstream
normalization. A 510-character normalized limit preserves RoBERTa context size.

Sources/licenses: Genie-TTS MIT (High_Logic, 2025); g2pM Apache-2.0;
Jieba MIT; pypinyin MIT; Paddle Chinese normalization/tone-sandhi Apache-2.0.
Full notices are maintained in the application's third-party notices.
