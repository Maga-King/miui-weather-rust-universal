"""Verify the packaged JNI callback descriptor and Kotlin isolation, without installation."""
import argparse
import json
import struct
from pathlib import Path
from zipfile import ZipFile


def definitions(data):
    def u32(at):
        return struct.unpack_from('<I', data, at)[0]

    def uleb(at):
        result = shift = 0
        while True:
            byte = data[at]
            at += 1
            result |= (byte & 127) << shift
            if byte < 128:
                return result, at
            shift += 7

    if not data.startswith(b'dex\n'):
        raise ValueError('DEX required')
    count, start = u32(56), u32(60)
    strings = []
    for index in range(count):
        _, at = uleb(u32(start + index * 4))
        strings.append(data[at:data.index(0, at)].decode('utf8', errors='replace'))
    count, start = u32(64), u32(68)
    types = [strings[u32(start + index * 4)] for index in range(count)]

    def type_list(at):
        if not at:
            return []
        return [types[struct.unpack_from('<H', data, at + 4 + index * 2)[0]]
                for index in range(u32(at))]

    count, start = u32(72), u32(76)
    protos = []
    for index in range(count):
        at = start + index * 12
        protos.append('(' + ''.join(type_list(u32(at + 8))) + ')' + types[u32(at + 4)])
    count, start = u32(88), u32(92)
    methods = []
    for index in range(count):
        owner, proto, name = struct.unpack_from('<HHI', data, start + index * 8)
        methods.append((types[owner], strings[name], protos[proto]))
    count, start = u32(96), u32(100)
    result = {}
    for index in range(count):
        at = start + index * 32
        owner = types[u32(at)]
        declared = []
        class_data = u32(at + 24)
        if class_data:
            cursor = class_data
            sizes = []
            for _ in range(4):
                n, cursor = uleb(cursor)
                sizes.append(n)
            for _ in range(sizes[0] + sizes[1]):
                _, cursor = uleb(cursor)
                _, cursor = uleb(cursor)
            for size in sizes[2:]:
                method = 0
                for _ in range(size):
                    diff, cursor = uleb(cursor)
                    method += diff
                    _, cursor = uleb(cursor)
                    _, cursor = uleb(cursor)
                    declared.append(methods[method])
        result[owner] = {'interfaces': type_list(u32(at + 12)), 'methods': declared}
    return result


def audit(apk):
    classes = {}
    with ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.endswith('.dex'):
                entries = definitions(archive.read(name))
                duplicates = classes.keys() & entries.keys()
                if duplicates:
                    raise AssertionError('Duplicate packaged definitions: ' + str(sorted(duplicates)))
                classes.update(entries)
        # The small, general pronunciation frontend is packaged; character TTS
        # weights and reference tensors must remain optional private storage.
        bundled_models = [name for name in archive.namelist() if name.endswith('.onnx')]
        assert bundled_models == ['assets/tibao_frontend/g2pm.onnx'], 'Unexpected bundled speech weights'
        assert 'lib/arm64-v8a/libsherpa-onnx-jni.so' in archive.namelist(), 'Native synthesizer absent'
    interface = 'Ldev/weather/speech/kotlin/jvm/functions/Function1;'
    candidates = [(owner, value) for owner, value in classes.items()
                  if owner.startswith('Ldev/weather/oneplus/WeatherNeuralSpeechService$')
                  and interface in value['interfaces']]
    assert len(candidates) == 1, 'Exactly one concrete isolated JNI callback required'
    owner, callback = candidates[0]
    signatures = {(name, proto) for _, name, proto in callback['methods']}
    assert ('invoke', '([F)Ljava/lang/Integer;') in signatures, 'JNI typed callback is missing'
    assert ('invoke', '(Ljava/lang/Object;)Ljava/lang/Object;') in signatures, 'Generic bridge missing'
    assert interface in classes, 'Isolated Kotlin interface missing'
    assert 'Lcom/k2fsa/sherpa/onnx/OfflineTts;' in classes, 'JNI class path changed'
    return {'apk': str(apk), 'callback': owner, 'typedCallback': True,
            'erasedBridge': True, 'isolatedInterface': interface, 'classDefinitions': len(classes),
            'modelBundled': False, 'deviceAudioVerified': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    args = parser.parse_args()
    print(json.dumps(audit(args.apk), indent=2))
