"""Replace only the original rain-page locale comparison in the merged DEX.

No DEX layout changes: the two-register invoke-virtual Locale.equals becomes a
same-size invoke-static to the host helper. All method/class references are
resolved from descriptors after D8, never from unstable numeric method indexes.
"""
from __future__ import annotations
import hashlib
import struct
import zlib


class Dex:
    def __init__(self,data: bytes):
        if data[:4]!=b'dex\n' or data[7]!=0 or self.u32(data,32)!=len(data):
            raise ValueError('Expected a complete standard DEX')
        self.data=data
        count,offset=struct.unpack_from('<II',data,56)
        self.strings=[]
        for index in range(count):
            at=self.u32(data,offset+4*index);_,at=self.uleb(at)
            self.strings.append(data[at:data.index(0,at)].decode('utf-8',errors='replace'))
        count,offset=struct.unpack_from('<II',data,64)
        self.types=[self.strings[self.u32(data,offset+4*i)] for i in range(count)]
        count,offset=struct.unpack_from('<II',data,72)
        self.protos=[]
        for index in range(count):
            _,result,parameters=struct.unpack_from('<III',data,offset+12*index)
            args=[] if not parameters else [self.types[struct.unpack_from('<H',data,parameters+4+2*i)[0]]
                    for i in range(self.u32(data,parameters))]
            self.protos.append('('+''.join(args)+')'+self.types[result])
        count,offset=struct.unpack_from('<II',data,88)
        self.methods=[]
        for index in range(count):
            cls,proto,name=struct.unpack_from('<HHI',data,offset+8*index)
            self.methods.append((self.types[cls],self.strings[name],self.protos[proto]))

    @staticmethod
    def u32(data,at):return struct.unpack_from('<I',data,at)[0]

    def uleb(self,at):
        value=0
        for shift in range(0,35,7):
            byte=self.data[at];at+=1;value|=(byte&127)<<shift
            if not byte&128:return value,at
        raise ValueError('Invalid DEX ULEB128')

    def code(self,wanted):
        target=self.methods.index(wanted)
        count,offset=struct.unpack_from('<II',self.data,96)
        for index in range(count):
            entry=offset+32*index
            if self.types[self.u32(self.data,entry)]!=wanted[0]:continue
            at=self.u32(self.data,entry+24)
            if not at:break
            counts=[]
            for _ in range(4):value,at=self.uleb(at);counts.append(value)
            for _ in range(counts[0]+counts[1]):
                _,at=self.uleb(at);_,at=self.uleb(at)
            for size in counts[2:]:
                method=0
                for _ in range(size):
                    delta,at=self.uleb(at);method+=delta
                    _,at=self.uleb(at);code,at=self.uleb(at)
                    if method==target:return code
        raise ValueError('Missing original locale method code')


def patch(data: bytes) -> bytes:
    dex=Dex(data)
    original=('Lcom/miui/weather2map/tools/z;','w','(Landroid/content/Context;)Z')
    helper=('Ldev/weather/oneplus/WeatherLocale;','matchesSimplifiedChinese','(Ljava/util/Locale;Ljava/lang/Object;)Z')
    helper_id=dex.methods.index(helper)
    if helper_id>65535:raise ValueError('Locale helper is outside the invoke method-index range')
    code=dex.code(original)
    registers,inputs,outputs,tries,debug,size=struct.unpack_from('<HHHHII',data,code)
    if (registers,inputs,outputs,tries,size)!=(2,1,2,0,11):
        raise ValueError('Original locale method layout changed')
    at=code+16;body=data[at:at+22]
    if body[:2]!=b'\x71\x10' or body[4:10]!=bytes.fromhex('01000c016200') or body[16:]!=bytes.fromhex('01000a010f01'):
        raise ValueError('Original locale method instructions changed')
    first=dex.methods[struct.unpack_from('<H',body,2)[0]]
    if first!=('Lcom/miui/weather2map/tools/z;','i','(Landroid/content/Context;)Ljava/util/Locale;'):
        raise ValueError('Unexpected locale source')
    field_id=struct.unpack_from('<H',body,10)[0]
    _,fields=struct.unpack_from('<II',data,80)
    cls,kind,name=struct.unpack_from('<HHI',data,fields+field_id*8)
    if (dex.types[cls],dex.types[kind],dex.strings[name])!=('Ljava/util/Locale;','Ljava/util/Locale;','SIMPLIFIED_CHINESE'):
        raise ValueError('Unexpected comparison locale')
    invocation=struct.unpack_from('<H',body,14)[0]
    if body[12:14]==b'\x71\x20' and invocation==helper_id:return data
    if body[12:14]!=b'\x6e\x20' or dex.methods[struct.unpack_from('<H',body,14)[0]]!=('Ljava/util/Locale;','equals','(Ljava/lang/Object;)Z'):
        raise ValueError('Unexpected locale comparison invoke')
    out=bytearray(data);out[at+12]=0x71;struct.pack_into('<H',out,at+14,helper_id)
    out[12:32]=hashlib.sha1(out[32:]).digest()
    struct.pack_into('<I',out,8,zlib.adler32(out[12:])&0xffffffff)
    return bytes(out)
