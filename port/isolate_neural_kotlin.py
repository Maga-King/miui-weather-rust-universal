"""Isolate new neural Kotlin runtime from the original R8-obfuscated pages runtime.

Only constant-pool UTF8 type names/descriptors change. Class/member names used by
Sherpa's JNI remain exact. The typed invoke(float[]) callback bridge is preserved.
"""
from pathlib import Path
import re, struct, zipfile
NAMESPACE=b'dev/weather/speech/kotlin'

def relocate_utf8(value: bytes) -> bytes:
    value=re.sub(rb'(?<![A-Za-z0-9_$/\.])kotlin/',NAMESPACE+b'/',value)
    value=value.replace(b'Lkotlin/',b'L'+NAMESPACE+b'/')
    value=re.sub(rb'(?<![A-Za-z0-9_$/\.])kotlin\.',NAMESPACE.replace(b'/',b'.')+b'.',value)
    return value

def relocate_class(data: bytes) -> bytes:
    if data[:4]!=b'\xca\xfe\xba\xbe':raise ValueError('Expected classfile')
    count=struct.unpack_from('>H',data,8)[0];out=bytearray(data[:10]);at=10;index=1
    sizes={3:4,4:4,5:8,6:8,7:2,8:2,9:4,10:4,11:4,12:4,15:3,16:2,17:4,18:4,19:2,20:2}
    while index<count:
        tag=data[at];at+=1;out.append(tag)
        if tag==1:
            size=struct.unpack_from('>H',data,at)[0];at+=2
            value=relocate_utf8(data[at:at+size]);at+=size
            if len(value)>65535:raise ValueError('Relocated class UTF8 limit exceeded')
            out.extend(struct.pack('>H',len(value)));out.extend(value)
        else:
            size=sizes.get(tag)
            if size is None:raise ValueError(f'Unknown class constant tag {tag}')
            out.extend(data[at:at+size]);at+=size
            if tag in (5,6):index+=1
        index+=1
    out.extend(data[at:]);return bytes(out)

def isolated_inputs(host_classes: list[Path],jars: list[Path],directory: Path) -> tuple[list[Path],list[Path]]:
    directory.mkdir(parents=True,exist_ok=True)
    host=[]
    for path in host_classes:
        data=path.read_bytes();changed=relocate_class(data)
        if changed!=data:
            target=directory/'host'/path.name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(changed);host.append(target)
        else:host.append(path)
    outputs=[]
    for jar in jars:
        # Compress implementation contains no Kotlin and is safe to leave intact.
        if not jar.name.startswith(('sherpa-onnx','kotlin-stdlib')):outputs.append(jar);continue
        target=directory/(jar.stem+'-isolated.jar')
        with zipfile.ZipFile(jar) as source,zipfile.ZipFile(target,'w') as archive:
            for entry in sorted(source.namelist()):
                if entry.endswith('/') or entry.startswith('META-INF/versions/') or entry=='module-info.class':continue
                if entry.upper().endswith(('.SF','.RSA','.DSA')):continue
                data=source.read(entry);name=entry
                if entry.endswith('.class'):
                    data=relocate_class(data)
                    if entry.startswith('kotlin/'):name=NAMESPACE.decode()+entry[len('kotlin'):]
                info=zipfile.ZipInfo(name,(2000,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
                archive.writestr(info,data)
        outputs.append(target)
    return host,outputs
