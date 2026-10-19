"""Clone an original Flutter settings row without adding Dart call PCs.

The original feedback row/closure remain intact. The additional row owns its
title, wrapper, closure and a valid Context carrying a private Android action.
One checked nursery reservation initializes every object before publication.
No additional GC safepoint, frame, or foreign call is introduced.
"""
from __future__ import annotations
import struct
import subprocess
import tempfile
from pathlib import Path
from port.patch_dpi_settings import encode_branch, file_offset, verify_leaf_router

CAVE = 0x9CF800
ROW_HOOK, ACTION_HOOK = 0x652AD8, 0x969DB0
ACTION = b"dev.weather.oneplus.MANAGE_WIDGETS"
DISPLAY_ACTION = b"dev.weather.oneplus.DISPLAY_CONFIG"


def row_source(prefix: str, slot: int, continuation: str, english_length: int) -> str:
    code = r'''
.global widget_row
widget_row:
    ldur x3, [x29, #-0x68]
'''
    # Validate all independently cloned objects before accessing their fields.
    for reg, cid, load in [('x3',0x1162,''), ('x4',0x11a5,'ldur w4, [x3, #0xf]'),
                           ('x5',248,'ldur w5, [x3, #0x37]'), ('x6',57,'ldur w6, [x5, #7]')]:
        if load:
            code += f'{load}\nadd {reg}, {reg}, x28, lsl #32\n'
        code += (f'ldur x9, [{reg}, #-1]\nubfx x9, x9, #12, #20\n'
                 f'mov x16, #{cid}\ncmp x9, x16\nb.ne widget_fallback\n')
    code += r'''
    add x16, x27, #0x30, lsl #12
    ldr x16, [x16, #0x690]
    ldur w9, [x6, #0x13]
    cmp w9, w16
    b.ne widget_fallback
    ldur w9, [x6, #0x17]
    cmp w9, w22
    b.ne widget_fallback
    // row144 + text80 + wrapper16 + closure48 + Context32 + title32 + action64.
    ldr x7, [x26, #0x60]
    adds x8, x7, #416
    b.cs widget_fallback
    ldr x9, [x26, #0x68]
    cmp x8, x9
    b.hs widget_fallback
    add x10, x7, #1
    add x11, x7, #145
    add x12, x7, #225
    add x13, x7, #241
    add x14, x7, #289
'''
    for old, new, size, header in [('x3','x10',144,0x116291c), ('x4','x11',80,0x11a551c),
                                   ('x5','x12',16,0xf811c), ('x6','x13',48,0x3935c)]:
        code += f'sub x16, {old}, #1\nsub x17, {new}, #1\n'
        for at in range(0,size,16):
            code += f'ldp x0, x1, [x16, #{at}]\nstp x0, x1, [x17, #{at}]\n'
        code += f'mov x16, #{header & 65535}\nmovk x16, #{header >> 16}, lsl #16\nstur x16, [{new}, #-1]\n'
    code += r'''
    // Select Chinese only when the original localized title begins in CJK.
    ldur w9, [x4, #0xb]
    add x9, x9, x28, lsl #32
    ldur x16, [x9, #-1]
    ubfx x16, x16, #12, #20
    cmp x16, #95
    b.ne widget_english
    ldurh w16, [x9, #0xf]
    mov w17, #0x3400
    cmp w16, w17
    b.lo widget_english
    mov w17, #0x9fff
    cmp w16, w17
    b.hi widget_english
    mov x16, #0xf25c
    movk x16, #5, lsl #16
    mov w1, #10
    adr x9, widget_title_zh
    b widget_title
widget_english:
    mov x16, #0xe25c
    movk x16, #5, lsl #16
    mov w1, #28
    adr x9, widget_title_en
widget_title:
    str x16, [x7, #320]
    str w1, [x7, #328]
    str wzr, [x7, #332]
    ldp x16, x17, [x9]
    stp x16, x17, [x7, #336]
    add x0, x7, #321
    stur w0, [x11, #0xb]
    // Context CID29, unboxed num_variables=1, null parent, compressed data[0].
    mov x16, #0xd21c
    movk x16, #1, lsl #16
    stur x16, [x14, #-1]
    mov w16, #1
    stur w16, [x14, #7]
    stur w22, [x14, #0xb]
    add x4, x7, #353
    stur w4, [x14, #0xf]
    stur w22, [x14, #0x13]
    stur w22, [x14, #0x17]
    stur w22, [x14, #0x1b]
    mov x16, #0xe45c
    movk x16, #5, lsl #16
    stur x16, [x4, #-1]
    mov w16, #68
    stur w16, [x4, #7]
    stur wzr, [x4, #0xb]
    adr x9, widget_action_bytes
    ldp x16, x17, [x9]
    stp x16, x17, [x7, #368]
    ldp x16, x17, [x9, #16]
    stp x16, x17, [x7, #384]
    ldp x16, x17, [x9, #32]
    stp x16, x17, [x7, #400]
    stur w14, [x13, #0x17]
    stur w22, [x13, #0x1b]
    stur w13, [x12, #7]
    stur w11, [x10, #0xf]
    stur w12, [x10, #0x37]
    str x8, [x26, #0x60]
    stur w10, [x2, #ROW_SLOT]
    b widget_row_continue
widget_fallback:
    add x16, x27, #0xd, lsl #12
    ldr x16, [x16, #0xda0]
    stur w16, [x2, #ROW_SLOT]
    b widget_row_continue
'''
    code=code.replace('mov w1, #28',f'mov w1, #{english_length*2}')
    code=code.replace('ROW_SLOT',hex(slot)).replace('widget_',prefix+'_')
    code=code.replace(prefix+'_row_continue',continuation)
    return code


def source() -> str:
    code=row_source('widget',0x17,'widget_row_continue',14)
    code+=row_source('display',0x1b,'display_row_continue',15)
    code+=r'''
.global widget_action
widget_action:
    add x1, x27, #0x30, lsl #12
    ldr x1, [x1, #0x6e8]
    // The original callback frame still exists before first async suspension.
    ldr x9, [x29, #8]
    adrp x10, widget_feedback_return
    add x10, x10, :lo12:widget_feedback_return
    cmp x9, x10
    b.ne widget_action_done
    ldr x9, [x29]
    ldr x9, [x9, #0x10]
    ldur w9, [x9, #0x17]
    cmp w9, w22
    b.eq widget_action_done
    add x9, x9, x28, lsl #32
    ldur x10, [x9, #-1]
    ubfx x10, x10, #12, #20
    cmp x10, #29
    b.ne widget_action_done
    ldur w10, [x9, #7]
    cmp w10, #1
    b.ne widget_action_done
    ldur w10, [x9, #0xb]
    cmp w10, w22
    b.ne widget_action_done
    ldur w9, [x9, #0xf]
    add x9, x9, x28, lsl #32
    ldur x10, [x9, #-1]
    ubfx x10, x10, #12, #20
    cmp x10, #94
    b.ne widget_action_done
    ldur w10, [x9, #7]
    cmp w10, #68
    b.ne widget_action_done
    ldur x10, [x9, #0xf]
    adr x16, widget_action_bytes
    ldr x16, [x16]
    cmp x10, x16
    b.ne widget_action_done
    mov x1, x9
widget_action_done:
    b widget_action_continue
.p2align 4
.global widget_data
widget_data:
widget_title_zh:
'''
    for label, data in [('', '\u7ba1\u7406\u5c0f\u7ec4\u4ef6'.encode('utf-16le').ljust(16,b'\0')),
                         ('widget_title_en:', b'Manage widgets'.ljust(16,b'\0')),
                         ('widget_action_bytes:', ACTION.ljust(48,b'\0')),
                         ('display_title_zh:', '\u5b57\u4f53\u4e0e\u52a8\u753b'.encode('utf-16le').ljust(16,b'\0')),
                         ('display_title_en:', b'Display options'.ljust(16,b'\0')),
                         ('display_action_bytes:', DISPLAY_ACTION.ljust(48,b'\0'))]:
        code += label+'\n.byte '+','.join(map(str,data))+'\n'
    return code


def assemble(clang: Path) -> tuple[bytes, int, int]:
    with tempfile.TemporaryDirectory(prefix='weather-widget-settings-') as folder:
        root=Path(folder); asm,elf,raw=root/'widget.S',root/'widget.elf',root/'widget.bin'
        asm.write_text('.text\n.p2align 2\n'+source(),encoding='ascii')
        command=[str(clang),'--target=aarch64-linux-android','-nostdlib','-fuse-ld=lld',
                 '-Wl,--entry=widget_row','-Wl,--no-undefined',
                 '-Wl,--defsym,widget_row_continue=0x652ae4',
                 '-Wl,--defsym,display_row_continue=0x652af0',
                 '-Wl,--defsym,widget_action_continue=0x969db8',
                 '-Wl,--defsym,widget_feedback_return=0x653b54',
                 f'-Wl,-Ttext={CAVE:#x}','-Wl,--build-id=none','-Wl,-z,max-page-size=4096',
                 str(asm),'-o',str(elf)]
        result=subprocess.run(command,capture_output=True,text=True)
        if result.returncode:raise RuntimeError(result.stderr)
        subprocess.run([str(clang.with_name('llvm-objcopy.exe')),'-O','binary','--only-section=.text',
                        str(elf),str(raw)],check=True,capture_output=True)
        symbols=subprocess.check_output([str(clang.with_name('llvm-nm.exe')),str(elf)],text=True)
        locations={p[-1]:int(p[0],16) for line in symbols.splitlines() if len(p:=line.split())==3}
        data=raw.read_bytes();verify_leaf_router(data[:locations['widget_data']-CAVE])
        return data,locations['widget_action'],locations['display_row']


def patch(image: bytes, clang: Path) -> bytes:
    code,action,display=assemble(Path(clang))
    if CAVE+len(code)>0x9D0000:raise ValueError('Widget settings exceeds checked ELF padding')
    hooks=[(ROW_HOOK,CAVE,bytes.fromhex('70374091')),
           (0x652AE4,display,bytes.fromhex('70374091')),
           (ACTION_HOOK,action,bytes.fromhex('61c34091'))]
    for address,expected in [(0x9532FC,'822385d2c222a0f2'),(0x938DA0,'82a38ad24223a0f2'),
                             (0x969A88,'822390d2e201a0f2')]:
        at=file_offset(image,address,8)
        if image[at:at+8]!=bytes.fromhex(expected):raise ValueError(f'Unsupported object allocator at {address:#x}')
    installed=[image[file_offset(image,a):file_offset(image,a)+4]==encode_branch(a,t) for a,t,_ in hooks]
    if all(installed):
        if image[CAVE:CAVE+len(code)]!=code:raise ValueError('Existing widget settings router differs')
        return image
    if any(installed):raise ValueError('Partial widget settings hook')
    for address,_,expected in hooks:
        at=file_offset(image,address)
        if image[at:at+4]!=expected:raise ValueError(f'Original instruction changed at {address:#x}')
    out=bytearray(image);phoff=struct.unpack_from('<Q',image,32)[0]
    entry,count=struct.unpack_from('<HH',image,54);found=False
    for i in range(count):
        at=phoff+i*entry
        kind,flags,offset,va,_,size,memory,_=struct.unpack_from('<IIQQQQQQ',image,at)
        if kind==1 and flags==5 and offset==va==0x2D0000:
            if size!=memory or va+size>CAVE or any(image[va+size:CAVE+len(code)]):raise ValueError('Executable padding occupied')
            struct.pack_into('<QQ',out,at+32,CAVE+len(code)-va,CAVE+len(code)-va);found=True
    if not found:raise ValueError('Expected executable segment missing')
    shoff=struct.unpack_from('<Q',image,40)[0];entry,count=struct.unpack_from('<HH',image,58)
    for i in range(count):
        at=shoff+i*entry;s=struct.unpack_from('<IIQQQQIIQQ',image,at)
        if s[1]==1 and s[2]==6 and s[3]==0x2D0000:struct.pack_into('<Q',out,at+32,CAVE+len(code)-s[3])
    out[CAVE:CAVE+len(code)]=code
    for address,target,_ in hooks:
        at=file_offset(image,address);out[at:at+4]=encode_branch(address,target)
    return bytes(out)
