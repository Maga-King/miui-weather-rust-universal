"""Add a DPI choice row using the original Flutter settings/factory callsite.

Uses a no-call router in verified ELF padding; all Dart calls retain their
original return PC and stackmap. Missing metadata/heap space keeps original UI.
Uses only Python's standard library. Existing native patches outside the checked
ranges are permitted; both publication variants can therefore use this checker.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import subprocess
import tempfile
from pathlib import Path


UNIT_NAMESPACE = "com.miui.weather2.unit"
PREFERENCE_KEY = "densityRatio"  # Existing pool String, independent of unit keys.
METADATA_KEY = "display_logical_density_dpi"  # Existing pool String.
POOL = {"namespace": 0x10888, "preference_key": 0x1BBE0,
        "metadata_key": 0x1B7D0, "choice_type": 0x30650,
        "choice_arguments": 0x30658}
CAVE_ADDRESS = 0x9CEE50
HOOK_ADDRESS = 0x652670
FINGERPRINTS = {
    0x652AD8: "7037409110d246f9507001b87037409110d246f950b001b8",
    0x6525AC: "4246fe97",  # Original temperature factory call.
    0x65266C: "1246fe97",  # Original wind factory call.
    0x5E3EB4: "fd79bfa9fd030faaef8101d1a2831df8f00305aa",
    0x337598: "fd79bfa9fd030faaef6100d1a1831ff8a2031ff8410080d2",
}


class UnsafePatch(RuntimeError):
    """The requested operation lacks a verified GC-safe insertion path."""


def file_offset(image: bytes, address: int, size: int = 4) -> int:
    """Map a virtual address through ELF64 PT_LOAD instead of assuming identity."""
    if image[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected little-endian ELF64")
    phoff = struct.unpack_from("<Q", image, 32)[0]
    entsize, count = struct.unpack_from("<HH", image, 54)
    for index in range(count):
        kind, flags, offset, va, _, filesz, _, _ = struct.unpack_from(
            "<IIQQQQQQ", image, phoff + index * entsize)
        if kind == 1 and flags & 1 and va <= address and address + size <= va + filesz:
            return offset + address - va
    raise ValueError(f"No file-backed executable segment covers {address:#x}")


def verify(image: bytes) -> dict:
    for address, expected_hex in FINGERPRINTS.items():
        expected = bytes.fromhex(expected_hex)
        offset = file_offset(image, address, len(expected))
        actual = image[offset:offset + len(expected)]
        if actual != expected:
            raise ValueError(f"Unsupported AOT at {address:#x}: {actual.hex()}")
    return {"sha256": hashlib.sha256(image).hexdigest(),
            "fingerprints_verified": len(FINGERPRINTS)}


def encode_branch(source: int, target: int) -> bytes:
    """A non-linking branch does not manufacture a new GC return PC."""
    displacement = target - source
    if displacement % 4 or not -(1 << 27) <= displacement < (1 << 27):
        raise ValueError("ARM64 B requires aligned target within +/-128 MiB")
    return struct.pack("<I", 0x14000000 | ((displacement >> 2) & 0x3FFFFFF))


def verify_leaf_router(code: bytes) -> None:
    """Reject calls/traps in a prospective allocation-free branch router.

    This check is necessary, not sufficient: live roots, branch destinations,
    overwritten instructions and original call-site stack maps need proof too.
    It therefore never enables installation by itself.
    """
    if len(code) % 4:
        raise ValueError("Unaligned ARM64 router")
    for offset in range(0, len(code), 4):
        word = struct.unpack_from("<I", code, offset)[0]
        if word & 0xFC000000 == 0x94000000:  # BL immediate.
            raise UnsafePatch(f"New Dart GC return PC at router+{offset:#x}")
        if word & 0xFE000000 == 0xD6000000:  # BR/BLR/RET, incl authenticated forms.
            raise UnsafePatch("Indirect branch requires separate target proof")
        if word & 0xFF000000 == 0xD4000000:
            raise UnsafePatch("Trap/system-call instruction in router")


def metadata(title: str, current: int, options: list[tuple[int, str]]) -> list:
    """Value Java returns under METADATA_KEY in unit.getAll/reload.

    Outer List avoids additional snapshot String keys. Codec decoder supplies
    real heap Strings and an indexed Map; leaf code wraps labels in new lKa.
    Keep PREFERENCE_KEY itself as an ordinary scalar integer in the same reply.
    """
    if isinstance(current, bool) or not isinstance(current, int):
        raise ValueError("Current DPI offset must be an integer")
    seen = set()
    for value, label in options:
        if isinstance(value, bool) or not isinstance(value, int) or value in seen:
            raise ValueError("Choice keys must be distinct integers")
        if not isinstance(label, str) or not label:
            raise ValueError("Choice label must be nonempty")
        seen.add(value)
    if 0 not in seen or current not in seen:
        raise ValueError("Options must include baseline zero and current offset")
    return [1, title, current, dict(options)]


def inspect(image: bytes) -> dict:
    result = verify(image)
    result.update({
        "status": "supported_original_image",
        "namespace": UNIT_NAMESPACE, "preference_key": PREFERENCE_KEY,
        "metadata_key": METADATA_KEY, "pool_offsets": POOL,
        "metadata_schema": "[1, title:String, current:int, Map<int,String> options]",
        "factory": "0x5e3eb4: x2=current, x3=Map<dynamic,lKa>, x5=key, x6=title",
        "option": "allocate 0x9533a0; compressed String at field_7",
        "cached_unit": {
            "builder_root": "[fp-0x48] stores unit GKa at 0x652394; do not overwrite it",
            "preference_lookup": "0x337598(GKa, key) returns cached/created eC holder",
            "holder_value": "compressed field_27; preserve null handling",
            "metadata_loading": "GKa underlying async map must finish getAll before use; do not assume injecting native response alone creates a widget",
        },
        "insertion": "0x652ae0 stores rY placeholder into Other Settings array[2]",
        "gc_contract": "No-call router; original BL 0x65266c reused; fp-0x60 PairArray root; final fp-0x70 Array root",
    })
    return result


def assemble_leaf(source: str, clang: Path, llvm_objcopy: Path) -> bytes:
    """Assemble self-contained ARM64 routing code using an installed NDK.

    Absolute continuations should use `.inst` with encode_branch() at the
    actual placement address. Unresolved relocations are rejected, rather than
    producing a router whose branch silently targets its assembler address.
    """
    with tempfile.TemporaryDirectory(prefix="weather-dpi-aot-") as folder:
        root = Path(folder)
        asm, obj, binary = root / "router.S", root / "router.o", root / "router.bin"
        asm.write_text(".text\n.p2align 2\n" + source + "\n", encoding="ascii")
        subprocess.run([str(clang), "--target=aarch64-linux-android", "-c",
                        str(asm), "-o", str(obj)], check=True, capture_output=True)
        elf = obj.read_bytes()
        shoff = struct.unpack_from("<Q", elf, 40)[0]
        entsize, count = struct.unpack_from("<HH", elf, 58)
        for index in range(count):
            section = struct.unpack_from("<IIQQQQIIQQ", elf, shoff + index * entsize)
            if section[1] in (4, 9) and section[5]:
                raise UnsafePatch("Router contains unresolved ELF relocations")
        subprocess.run([str(llvm_objcopy), "-O", "binary", "--only-section=.text",
                        str(obj), str(binary)], check=True, capture_output=True)
        code = binary.read_bytes()
        verify_leaf_router(code)
        return code


def assemble_linked_router(clang: Path, llvm_objcopy: Path, address: int) -> bytes:
    """Link absolute original-code continuations without unresolved relocations."""
    with tempfile.TemporaryDirectory(prefix="weather-dpi-aot-link-") as folder:
        root = Path(folder)
        asm, elf, binary = root / "dpi.S", root / "dpi.elf", root / "dpi.bin"
        asm.write_text(".text\n.p2align 2\n" + settings_router_source(), encoding="ascii")
        subprocess.run([str(clang), "--target=aarch64-linux-android", "-nostdlib",
                        "-fuse-ld=lld", "-Wl,--entry=dpi_start", "-Wl,--no-undefined",
                        "-Wl,--defsym,dpi_factory_call=0x65266c",
                        "-Wl,--defsym,dpi_original_continue=0x652674",
                        "-Wl,--defsym,dpi_list_continue=0x6526a4",
                        f"-Wl,-Ttext={address:#x}", "-Wl,--build-id=none",
                        "-Wl,-z,max-page-size=4096", str(asm), "-o", str(elf)],
                       check=True, capture_output=True)
        subprocess.run([str(llvm_objcopy), "-O", "binary", "--only-section=.text",
                        str(elf), str(binary)], check=True, capture_output=True)
        result = binary.read_bytes()
        verify_leaf_router(result)
        return result


def array3_fastpath_source(success: int, failure: int, address: int) -> str:
    """Research fragment: allocation-free-in-the-GC-sense young Array creation.

    Input x0=DPI row; [fp-0x60]=temperature row; [fp-0x68]=wind row.
    Outputs x2=new Array, [fp-0x70]=Array, branches into original 0x6526a4.
    Caller must supply proven roots for the repeated original factory call.
    Failure retains original wind row and resumes original Array allocator.
    This is NOT an enabled patch: safepoint/root proof is still required.
    """
    # Exactly 24 fixed-width instructions; final continuations are computed
    # relative to their real placement. The two failure edges share a local
    # label and occur before committing thread allocation top.
    lines = [
        "ldr x9, [x26, #0x60]", "adds x10, x9, #32", "b.cs 1f",
        "ldr x11, [x26, #0x68]", "cmp x10, x11", "b.hs 1f",
        "str x10, [x26, #0x60]", "mov x11, #0xa21c", "movk x11, #5, lsl #16",
        "str x11, [x9]", "add x2, x9, #1", "stur w22, [x2, #7]",
        "mov w11, #6", "stur w11, [x2, #0xb]", "ldur x11, [x29, #-0x60]",
        "stur w11, [x2, #0xf]", "ldur x11, [x29, #-0x68]",
        "stur w11, [x2, #0x13]", "stur w0, [x2, #0x17]",
        "stur w22, [x2, #0x1b]", "stur x2, [x29, #-0x70]",
    ]
    lines.append(f".inst 0x{struct.unpack('<I', encode_branch(address + 21 * 4, success))[0]:08x}")
    lines += ["1:", "ldur x0, [x29, #-0x68]"]
    lines.append(f".inst 0x{struct.unpack('<I', encode_branch(address + 23 * 4, failure))[0]:08x}")
    return "\n".join(lines)


def clone_options_source() -> str:
    """No-call fragment cloning a decoded Map<int,String> to Map<dynamic,lKa>.

    EXPERIMENTAL: caller must prove _Map field layout and input String classes.
    Input x3=decoded map; no stale caller stack slots are dereferenced.
    Output x3=new typed options map; preserves x0-x2, x15, x18 and VM registers.
    Clobbers x4-x14,x16,x17. Failure branches to caller's dpi_fallback label;
    success falls through, with x3 ready to root at original stackmap slot.
    Map index is shared read-only; data array and lKa values are all independent.
    """
    return r'''
    // Validate the proven SDK _Map layout, without reading dead caller slots.
    ldur x9, [x3, #-1]
    ubfx x10, x9, #12, #20
    cmp x10, #86
    b.ne dpi_fallback
    ubfx x9, x9, #8, #4
    cmp x9, #2
    b.ne dpi_fallback
    // No deleted entries and a bounded positive Smi usedData count.
    ldur w9, [x3, #0x17]
    cbnz w9, dpi_fallback
    ldur w5, [x3, #0x13]
    tst w5, #3
    b.ne dpi_fallback
    cmp w5, #4
    b.lo dpi_fallback
    cmp w5, #1024
    b.hi dpi_fallback
    ldur w6, [x3, #0xf]
    add x6, x6, x28, lsl #32
    tbz w6, #0, dpi_fallback
    ldur x9, [x6, #-1]
    ubfx x9, x9, #12, #20
    cmp x9, #90
    b.eq 4f
    cmp x9, #91
    b.ne dpi_fallback
4:
    ldur w7, [x6, #0xb]
    tst w7, #1
    b.ne dpi_fallback
    cmp w7, w5
    b.lo dpi_fallback
    cmp w7, #4096
    b.hi dpi_fallback
    // Reject non-Smi keys and non-String values before allocating anything.
    mov x12, #0
    lsr x13, x5, #1
5:
    add x4, x6, x12, lsl #2
    ldur w14, [x4, #0xf]
    tbnz w14, #0, dpi_fallback
    ldur w14, [x4, #0x13]
    tbz w14, #0, dpi_fallback
    add x14, x14, x28, lsl #32
    ldur x16, [x14, #-1]
    ubfx x16, x16, #12, #20
    cmp x16, #94
    b.eq 6f
    cmp x16, #95
    b.ne dpi_fallback
6:
    add x12, x12, #2
    cmp x12, x13
    b.lo 5b
    // Array size = align16(16 + compressedLength * 4).
    lsl x7, x7, #1
    add x7, x7, #31
    and x7, x7, #-16
    ldr x9, [x26, #0x60]
    add x8, x9, #32
    adds x8, x8, x7
    b.cs dpi_fallback
    adds x8, x8, x5, lsl #3
    b.cs dpi_fallback
    ldr x10, [x26, #0x68]
    cmp x8, x10
    b.hs dpi_fallback
    // Clone 32-byte map and restore young-generation tag bits.
    sub x4, x3, #1
    ldp x10, x11, [x4]
    ldp x12, x13, [x4, #16]
    and x10, x10, #-256
    orr x10, x10, #0x1c
    stp x10, x11, [x9]
    stp x12, x13, [x9, #16]
    add x10, x9, #1
    add x11, x9, #33
    stur w11, [x10, #0xf]
    add x16, x27, #0x30, lsl #12
    ldr x16, [x16, #0x650]
    stur w16, [x10, #7]
    // Copy full backing array, including unused capacity/padding.
    sub x4, x6, #1
    sub x12, x11, #1
    mov x13, #0
2:
    ldp x16, x17, [x4], #16
    stp x16, x17, [x12], #16
    add x13, x13, #16
    cmp x13, x7
    b.lo 2b
    ldur x16, [x11, #-1]
    and x16, x16, #-256
    orr x16, x16, #0x1c
    stur x16, [x11, #-1]
    // Build one independent 32-byte lKa per live value.
    add x13, x9, #32
    add x13, x13, x7
    mov x12, #0
    lsr x5, x5, #1
3:
    add x4, x11, x12, lsl #2
    ldur w14, [x4, #0x13]
    mov x16, #0x621c
    movk x16, #0x52, lsl #16
    str x16, [x13]
    str w14, [x13, #8]
    str w22, [x13, #12]
    str w22, [x13, #16]
    str w22, [x13, #20]
    str w22, [x13, #24]
    str w22, [x13, #28]
    add x16, x13, #1
    stur w16, [x4, #0x13]
    add x13, x13, #32
    add x12, x12, #2
    cmp x12, x5
    b.lo 3b
    // No GC or heap mutation has occurred before every object is initialized.
    str x8, [x26, #0x60]
    mov x3, x10
'''


def settings_router_source() -> str:
    """Full no-call router for the verified cached-map and stackmap contract.

    Installed at the original factory return PC 0x652670, it loops through the
    original BL at 0x65266c. Existing stackmap bit 11 roots fp-0x60; a temporary
    PairArray there keeps both original unit rows alive through the second call.
    Actual device validation remains the final integration check; unsupported
    metadata or insufficient young-heap space retains the original empty row.
    """
    prefix = r'''
    .global dpi_start
dpi_start:
    .extern dpi_factory_call
    .extern dpi_original_continue
    .extern dpi_list_continue
    // Factory result has a canonical key at rKa.field_b.
    add x16, x27, #0x1b, lsl #12
    ldr x16, [x16, #0xbe0]
    ldur w17, [x0, #0xb]
    cmp w17, w16
    b.eq dpi_second_return
    // Unit namespace receiver was saved by original 0x652394.
    ldur x9, [x29, #-0x48]
    ldur w9, [x9, #0x17]
    add x9, x9, x28, lsl #32
    cmp w9, w22
    b.eq dpi_fallback
    ldur x10, [x9, #-1]
    ubfx x10, x10, #12, #20
    cmp x10, #86
    b.ne dpi_fallback
    ldur w10, [x9, #0x13]
    tst w10, #1
    b.ne dpi_fallback
    cmp w10, #4096
    b.hi dpi_fallback
    lsr x10, x10, #1
    ldur w11, [x9, #0xf]
    add x11, x11, x28, lsl #32
    add x16, x27, #0x1b, lsl #12
    ldr x16, [x16, #0x7d0]
    mov x12, #0
dpi_find_metadata:
    cmp x12, x10
    b.hs dpi_fallback
    add x13, x11, x12, lsl #2
    ldur w14, [x13, #0xf]
    add x14, x14, x28, lsl #32
    cmp w14, w16
    b.eq dpi_metadata_found
    tbz w14, #0, dpi_next_metadata
    ldur x17, [x14, #-1]
    ubfx x17, x17, #12, #20
    cmp x17, #94
    b.ne dpi_next_metadata
    ldur w17, [x14, #7]
    ldur w9, [x16, #7]
    cmp w17, w9
    b.ne dpi_next_metadata
    lsr x9, x9, #1
    mov x7, #0
dpi_compare_key:
    cmp x7, x9
    b.hs dpi_metadata_found
    add x8, x14, x7
    ldurb w8, [x8, #0xf]
    add x17, x16, x7
    ldurb w17, [x17, #0xf]
    cmp w8, w17
    b.ne dpi_next_metadata
    add x7, x7, #1
    b dpi_compare_key
dpi_next_metadata:
    add x12, x12, #2
    b dpi_find_metadata
dpi_metadata_found:
    ldur w9, [x13, #0x13]
    add x9, x9, x28, lsl #32
    tbz w9, #0, dpi_fallback
    ldur x10, [x9, #-1]
    ubfx x10, x10, #12, #20
    cmp x10, #92
    b.ne dpi_metadata_array
    ldur w11, [x9, #0xb]
    cmp w11, #8
    b.lo dpi_fallback
    ldur w9, [x9, #0xf]
    add x9, x9, x28, lsl #32
    b dpi_metadata_array_checked
dpi_metadata_array:
    cmp x10, #90
    b.eq dpi_metadata_array_size
    cmp x10, #91
    b.ne dpi_fallback
dpi_metadata_array_size:
    ldur w11, [x9, #0xb]
    cmp w11, #8
    b.lo dpi_fallback
dpi_metadata_array_checked:
    ldur w10, [x9, #0xf]
    cmp w10, #2
    b.ne dpi_fallback
    ldur w10, [x9, #0x13]
    tbz w10, #0, dpi_fallback
    add x10, x10, x28, lsl #32
    ldur x10, [x10, #-1]
    ubfx x10, x10, #12, #20
    cmp x10, #94
    b.eq dpi_title_valid
    cmp x10, #95
    b.ne dpi_fallback
dpi_title_valid:
    ldur w10, [x9, #0x17]
    tbnz w10, #0, dpi_fallback
    ldur w3, [x9, #0x1b]
    add x3, x3, x28, lsl #32
    tbz w3, #0, dpi_fallback
    ldur x10, [x3, #-1]
    ubfx x10, x10, #12, #20
    cmp x10, #86
    b.ne dpi_fallback
    // These slots are dead at the original 0x652670 return PC. No new GC yet.
    stur x9, [x29, #-0x68]
'''
    suffix = r'''
    // A single original live root holds both unchanged original row objects.
    ldr x9, [x26, #0x60]
    adds x10, x9, #32
    b.cs dpi_fallback
    ldr x11, [x26, #0x68]
    cmp x10, x11
    b.hs dpi_fallback
    mov x11, #0xa21c
    movk x11, #5, lsl #16
    str x11, [x9]
    add x11, x9, #1
    stur w22, [x11, #7]
    mov w12, #4
    stur w12, [x11, #0xb]
    ldur x12, [x29, #-0x60]
    stur w12, [x11, #0xf]
    stur w0, [x11, #0x13]
    stur w22, [x11, #0x17]
    stur w22, [x11, #0x1b]
    str x10, [x26, #0x60]
    stur x11, [x29, #-0x60]
    // Re-enter exactly the original factory BL and its original stackmap.
    ldur x9, [x29, #-0x68]
    ldur w2, [x9, #0x17]
    sxtw x2, w2
    ldur w6, [x9, #0x13]
    add x6, x6, x28, lsl #32
    mov x1, x22
    add x5, x27, #0x1b, lsl #12
    ldr x5, [x5, #0xbe0]
    add x4, x27, #0x30, lsl #12
    ldr x4, [x4, #0x658]
    add x16, x22, #0x20
    add x17, x22, #0x20
    stp x17, x16, [x15, #0x10]
    add x16, x22, #0x30
    add x17, x22, #0x30
    stp x17, x16, [x15]
    b dpi_factory_call
dpi_second_return:
    // Build final 3-row array without another allocation safepoint.
    ldr x9, [x26, #0x60]
    adds x10, x9, #32
    b.cs dpi_second_fallback
    ldr x11, [x26, #0x68]
    cmp x10, x11
    b.hs dpi_second_fallback
    mov x11, #0xa21c
    movk x11, #5, lsl #16
    str x11, [x9]
    add x2, x9, #1
    stur w22, [x2, #7]
    mov w11, #6
    stur w11, [x2, #0xb]
    ldur x11, [x29, #-0x60]
    ldur w12, [x11, #0xf]
    stur w12, [x2, #0xf]
    add x12, x12, x28, lsl #32
    stur x12, [x29, #-0x60]
    ldur w12, [x11, #0x13]
    stur w12, [x2, #0x13]
    stur w0, [x2, #0x17]
    stur w22, [x2, #0x1b]
    stur x2, [x29, #-0x70]
    str x10, [x26, #0x60]
    b dpi_list_continue
dpi_second_fallback:
    ldur x11, [x29, #-0x60]
    ldur w12, [x11, #0xf]
    add x12, x12, x28, lsl #32
    stur x12, [x29, #-0x60]
    ldur w0, [x11, #0x13]
    add x0, x0, x28, lsl #32
dpi_fallback:
    mov x1, x22
    b dpi_original_continue
'''
    return prefix + clone_options_source() + suffix


def patch(image: bytes, clang: Path, llvm_objcopy: Path | None = None) -> bytes:
    """Return a candidate-patched ELF, rejecting unknown builds/padding changes.

    Original instruction-table stack maps verified separately with ReversePc:
    factory return 0x652670 roots fp-0x60 (bit11); list return 0x6526b0 roots
    fp-0x70 (bit13). No CALL instruction exists at any new PC in this patch.
    """
    verify(image)
    clang = Path(clang)
    llvm_objcopy = Path(llvm_objcopy) if llvm_objcopy else clang.with_name("llvm-objcopy.exe")
    code = assemble_linked_router(clang, llvm_objcopy, CAVE_ADDRESS)
    if CAVE_ADDRESS + len(code) > 0x9D0000:
        raise UnsafePatch("Router exceeds verified executable padding")
    hook_offset = file_offset(image, HOOK_ADDRESS)
    hook = encode_branch(HOOK_ADDRESS, CAVE_ADDRESS)
    if image[hook_offset:hook_offset+4] == hook:
        if image[CAVE_ADDRESS:CAVE_ADDRESS+len(code)] != code:
            raise UnsafePatch("Existing DPI hook uses different router bytes")
        return image
    if image[hook_offset:hook_offset+4] != bytes.fromhex("e10316aa"):
        raise UnsafePatch("Original post-factory instruction changed")
    if any(image[CAVE_ADDRESS:CAVE_ADDRESS+len(code)]):
        raise UnsafePatch("ELF padding is occupied; refusing to overwrite")
    output = bytearray(image)
    phoff = struct.unpack_from("<Q", image, 32)[0]
    entsize, count = struct.unpack_from("<HH", image, 54)
    extended = False
    for index in range(count):
        offset = phoff + index * entsize
        kind, flags, file_at, va, _, size, memory, _ = struct.unpack_from("<IIQQQQQQ", image, offset)
        if kind == 1 and flags == 5 and va == file_at == 0x2D0000:
            if va + size != CAVE_ADDRESS or size != memory:
                raise UnsafePatch("Executable PT_LOAD end differs from verified original")
            struct.pack_into("<QQ", output, offset + 32, size + len(code), memory + len(code))
            extended = True
    if not extended:
        raise UnsafePatch("Verified executable PT_LOAD not found")
    shoff = struct.unpack_from("<Q", image, 40)[0]
    entsize, count = struct.unpack_from("<HH", image, 58)
    text_extended = False
    for index in range(count):
        offset = shoff + index * entsize
        section = struct.unpack_from("<IIQQQQIIQQ", image, offset)
        if section[1] == 1 and section[2] == 6 and section[3] == 0x2D0000:
            if section[3] + section[5] != CAVE_ADDRESS:
                raise UnsafePatch("ELF text section end differs from verified original")
            struct.pack_into("<Q", output, offset + 32, section[5] + len(code))
            text_extended = True
    if not text_extended:
        raise UnsafePatch("Verified ELF text section not found")
    output[CAVE_ADDRESS:CAVE_ADDRESS+len(code)] = code
    output[hook_offset:hook_offset+4] = hook
    return bytes(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--ndk-bin", type=Path)
    args = parser.parse_args()
    original = args.image.read_bytes()
    if args.output:
        if not args.ndk_bin:
            parser.error("--output requires --ndk-bin")
        args.output.write_bytes(patch(original, args.ndk_bin / "clang.exe"))
    report = json.dumps(inspect(original), ensure_ascii=False, indent=2)
    if args.report:
        args.report.write_text(report + "\n", encoding="utf-8")
    else:
        print(report)


if __name__ == "__main__":
    main()
