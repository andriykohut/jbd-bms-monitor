"""Decode replies captured from the real battery: uv run --with bleak python test_battery.py"""
from battery import decode, payload

BASIC = bytes.fromhex("dd030019053200001a8927100025000000000000000010430304010b77fdd477")
CELLS = bytes.fromhex("dd0400080d330d340d330d33fef777")

s = decode(payload(BASIC, 0x03), payload(CELLS, 0x04))
assert s["voltage_v"] == 13.30 and s["current_a"] == 0 and s["soc_pct"] == 67, s
assert s["remaining_ah"] == 67.93 and s["capacity_ah"] == 100 and s["cycles"] == 37, s
assert s["temps_c"] == [20.4] and s["cells_mv"] == [3379, 3380, 3379, 3379], s
assert s["charge_fet"] and s["discharge_fet"] and s["protections"] == [], s

try:
    payload(BASIC[:-3] + b"\x00\x00\x77", 0x03)
    raise SystemExit("bad checksum was accepted")
except AssertionError:
    pass

# Both checksums are valid, yet it claims 2.56 V across 32 cells at 2%.
BOGUS_BASIC = bytes.fromhex("dd030017010000000d00271000002c9e0000000000001002002000fea877")
BOGUS_CELLS = bytes.fromhex(
    "dd040040000001f403e803e800020b860cb40cd90d000d6b2ee00e100a8c000100250ce9ed7b0000" + "00" * 28 + "f5f377"
)
try:
    decode(payload(BOGUS_BASIC, 0x03), payload(BOGUS_CELLS, 0x04))
    raise SystemExit("bogus reading was accepted")
except AssertionError:
    pass
print("ok")
