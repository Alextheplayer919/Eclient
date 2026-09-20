#!/usr/bin/env python3
"""Generate the BlockStateMap data asset (NBT) — Java→Bedrock id + state tables.

Two machine sources, no hand data:
  - GeyserMC mappings `feat/26.1` blocks.nbt  (java blockstate id → bedrock id+states)
  - PrismarineJS minecraft-data pc/26.1 blocks.json (java block registry + prop values)

The Geyser file is a sparse list indexed by the JAVA GLOBAL BLOCKSTATE ID. We
recover per-state java keys by expanding minecraft-data variants with the
verified "last property varies fastest" convention, with java bools ordered
true-then-false (validated against defaultState anchors: smooth_stone_slab
min+3 = default[bottom,w=false]; sea_pickle min = default[1,wl=true]).

Output: app/src/main/assets/schem/blocks_statemap.nbt (gzipped, big-endian NBT)
  root Compound:
    "names"   : Compound<String bedrockName>            — uniform java→bedrock renames
    "rules"   : Compound<block> → Compound<prop> → Compound{ "key": String,
                 "vals": Compound<jval → bval (String|Int|Byte)> }
    "complex" : Compound<"minecraft:id[k=v,...]"> → Compound{ "@id": String (opt),
                 plus bedrock state keys as raw tags }

Runtime loader: core/schem/BlockStateMap.kt  —  change the tables ONLY here;
never hand-edit the .nbt. Regenerate: python3 tools/gen_block_statemap.py
"""
import json, tempfile, os, urllib.request
from itertools import product

G_URL = "https://raw.githubusercontent.com/GeyserMC/mappings/feat/26.1/blocks.nbt"
B_URL = "https://raw.githubusercontent.com/PrismarineJS/minecraft-data/master/data/pc/26.1/blocks.json"
OUT   = "app/src/main/assets/schem/blocks_statemap.nbt"

def fetch(url):
    with urllib.request.urlopen(url) as r:
        return r.read()

def main():
    import nbtlib
    from nbtlib.tag import Compound, String, Int, Byte

    blocks = json.loads(fetch(B_URL))
    with tempfile.NamedTemporaryFile(suffix='.nbt', delete=False) as tf:
        tf.write(fetch(G_URL)); path = tf.name
    try:
        G = nbtlib.load(path)['bedrock_mappings']
    finally:
        os.unlink(path)

    calc = 0
    for b in blocks:
        n = 1
        for s in b.get('states', []): n *= s.get('num_values', 1)
        calc += n
    assert calc == len(G), 'alignment broke: %d vs %d' % (calc, len(G))
    print('global states aligned:', calc)

    def values(s):
        if s.get('values'): return s['values']
        if s.get('type') == 'bool': return ['true', 'false']     # true = index 0
        return [str(i) for i in range(s.get('num_values', 1))]

    def unwrap(v):
        return v.unpack() if hasattr(v, 'unpack') else v

    def tag(v):
        if isinstance(v, bool):  return Byte(1 if v else 0)
        if isinstance(v, int):   return Int(v)
        return String(str(v))

    name_over, prop_rules, complex_states = {}, {}, {}
    neutral_props = blocks_with_rules = blocks_complex = 0

    for b in blocks:
        props = b.get('states', [])
        pname = 'minecraft:' + b['name']
        minid = b['minStateId']
        variants = [dict()] if not props else [
            dict(zip([s['name'] for s in props], combo))
            for combo in product(*[values(s) for s in props])
        ]
        entries = [G[minid + i] for i in range(len(variants))]

        effective_ids = {
            'minecraft:' + (str(e['bedrock_identifier']) if 'bedrock_identifier' in e else b['name'])
            for e in entries
        }
        id_is_state_dependent = len(effective_ids) > 1
        if len(effective_ids) == 1:
            bid = next(iter(effective_ids))
            if bid != pname: name_over[pname] = bid

        stmap = [{k: unwrap(e['state'][k]) for k in e['state'].keys()} if 'state' in e else {}
                 for e in entries]
        skeys = set().union(*[set(s) for s in stmap]) if stmap else set()

        block_complex = id_is_state_dependent
        rules_for_block = {}
        if props and skeys and not block_complex:
            for s in props:
                p = s['name']
                vows = {}
                for i in range(len(variants)):
                    for j in range(i + 1, len(variants)):
                        vi, vj = variants[i], variants[j]
                        if [q for q in vi if vi[q] != vj[q]] != [p]: continue
                        changed = {k for k in set(stmap[i]) | set(stmap[j]) if stmap[i].get(k) != stmap[j].get(k)}
                        for k in changed:
                            trans = vows.setdefault(k, {})
                            if (vi[p] in trans and trans[vi[p]] != stmap[i].get(k)) or \
                               (vj[p] in trans and trans[vj[p]] != stmap[j].get(k)):
                                trans['_conflict'] = True
                            trans.setdefault(vi[p], stmap[i].get(k)); trans.setdefault(vj[p], stmap[j].get(k))
                if not vows:
                    neutral_props += 1
                    continue
                if len(vows) == 1 and '_conflict' not in next(iter(vows.values())):
                    bkey, mapping = next(iter(vows.items()))
                    if len(mapping) == len(set(values(s))):
                        rules_for_block[p] = (bkey, mapping)
                        continue
                block_complex = True
                break

        if block_complex:
            blocks_complex += 1
            plist = [(s['name'], values(s)) for s in props]
            for i, v in enumerate(variants):
                key = '%s[%s]' % (pname, ','.join('%s=%s' % (k, v[k]) for k, _ in plist))
                ident = 'minecraft:' + str(entries[i]['bedrock_identifier']) if 'bedrock_identifier' in entries[i] else None
                complex_states[key] = (ident, stmap[i])
        elif rules_for_block:
            blocks_with_rules += 1
            prop_rules[pname] = rules_for_block

    print('uniform name overrides:', len(name_over))
    print('blocks with prop rules:', blocks_with_rules, '| complex blocks:', blocks_complex,
          '| complex states:', len(complex_states), '| neutral props:', neutral_props)

    # ── emit NBT ──────────────────────────────────────────────────────────
    names_c = Compound({k: String(v) for k, v in name_over.items()})
    rules_c = Compound()
    for blk, rules in prop_rules.items():
        bc = Compound()
        for p, (bkey, mapping) in rules.items():
            bc[p] = Compound({'key': String(bkey), 'vals': Compound({jv: tag(bv) for jv, bv in mapping.items()})})
        rules_c[blk] = bc
    complex_c = Compound()
    for key, (ident, st) in complex_states.items():
        c = Compound({k: tag(v) for k, v in st.items()})
        if ident: c['@id'] = String(ident)
        complex_c[key] = c

    root = Compound({'names': names_c, 'rules': rules_c, 'complex': complex_c})
    f = nbtlib.File(root)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    f.save(OUT, gzipped=True, byteorder='big')
    print('wrote', OUT, os.path.getsize(OUT), 'bytes (gz)')

    # ── self-test against freshly written asset ───────────────────────────
    back = nbtlib.load(OUT)
    assert str(back['names']['minecraft:bricks']) == 'minecraft:brick_block'
    st = back['rules']['minecraft:oak_stairs']
    assert str(st['facing']['key']) == 'weirdo_direction'
    assert int(st['facing']['vals']['north']) == 3
    sp = back['rules']['minecraft:sea_pickle']
    assert int(sp['waterlogged']['vals']['false']) == 1      # dry pickle = dead_bit 1
    comp = back['complex']['minecraft:smooth_stone_slab[type=bottom,waterlogged=false]']
    assert str(comp['minecraft:vertical_half']) == 'bottom'
    dbl = back['complex']['minecraft:acacia_slab[type=double,waterlogged=false]']
    assert str(dbl['@id']) == 'minecraft:acacia_double_slab'
    print('self-test: bricks / oak_stairs / sea_pickle / slab / double-slab — OK')

if __name__ == '__main__':
    main()
