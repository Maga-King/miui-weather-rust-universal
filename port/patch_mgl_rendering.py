"""Persist the weather renderer's resolution and vsync changes in its ELF.

File-backed code can revert to its original bytes after background reclamation.
Patch the packaged library, so a discarded executable page reloads the same
instructions. Never patch temperature coordinates, textures or scroll state.
"""
import struct

from port.patch_dpi_settings import file_offset


PATCHES = (
    # FrameSync constructor: remove its default 15 ms software gate.
    (0xFFC64, 0xF9000808, 0xF900081F),
    # FrameSync::setFrameRate: use its existing zero-interval branch.
    (0xFFEC4, 0x7100043F, 0x1400000C),
    # DeviceUtils::getRenderScale: always return 1.0, including after resume.
    (0xEE418, 0xBD400400, 0x1E2E1000),
)


def patch(image: bytes) -> bytes:
    if image[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", image, 18)[0] != 183:
        raise ValueError("Expected little-endian AArch64 ELF64 renderer")
    # Check the getter return and the setter's branch destination as well.
    for address, expected in ((0xEE41C, 0xD65F03C0), (0xFFEF4, 0xF900081F)):
        if struct.unpack_from("<I", image, file_offset(image, address))[0] != expected:
            raise ValueError(f"Unsupported MGL rendering ABI at {address:#x}")
    for address, expected, _ in PATCHES:
        if struct.unpack_from("<I", image, file_offset(image, address))[0] != expected:
            raise ValueError(f"Unsupported MGL rendering instruction at {address:#x}")
    result = bytearray(image)
    for address, _, replacement in PATCHES:
        struct.pack_into("<I", result, file_offset(image, address), replacement)
    return bytes(result)
