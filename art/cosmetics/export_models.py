"""Export the open accessory scene to Minecraft cuboids and a flat-color atlas."""
import json
import math
import re
import struct
import zlib
from pathlib import Path

import bpy
from mathutils import Matrix, Vector

ROOT = Path(__file__).resolve().parents[2]
TO_GAME = Matrix(((1, 0, 0), (0, 0, -1), (0, 1, 0)))


def export_part(obj):
    if obj.type != 'MESH' or obj.parent or obj.modifiers:
        raise ValueError(f'{obj.name}: expected an unparented cuboid without modifiers')
    if any(scale <= 0 for scale in obj.scale):
        raise ValueError(f'{obj.name}: apply mirrored scale before exporting')
    vertices = [TO_GAME @ Vector([v.co[i] * obj.scale[i] * 16 for i in range(3)]) for v in obj.data.vertices]
    lo = [min(v[i] for v in vertices) for i in range(3)]
    hi = [max(v[i] for v in vertices) for i in range(3)]
    corners = {tuple(round(v[i], 5) for i in range(3)) for v in vertices}
    expected = {(round(x, 5), round(y, 5), round(z, 5)) for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])}
    if len(vertices) != 8 or len(corners) != 8 or corners != expected:
        raise ValueError(f'{obj.name}: mesh must remain a rectangular cuboid')
    size = [hi[i] - lo[i] for i in range(3)]
    # Every box unwrap must stay inside its 64 x 64 color swatch.
    if any(s <= 0 for s in size) or 2 * (size[0] + size[2]) > 63 or size[1] + size[2] > 63:
        raise ValueError(f'{obj.name}: cuboid exceeds its texture swatch')
    if len(obj.data.materials) != 1:
        raise ValueError(f'{obj.name}: assign exactly one palette material')
    color = obj.data.materials[0].get('palette_index', -1)
    if not isinstance(color, int) or not 0 <= color < 16:
        raise ValueError(f'{obj.name}: invalid palette_index')
    location, rotation, _ = obj.matrix_basis.decompose()
    angles = (TO_GAME @ rotation.to_matrix() @ TO_GAME.inverted()).to_euler('XYZ')
    pivot = TO_GAME @ (location * 16)
    values = list(lo) + size + list(pivot) + list(angles)
    if not all(math.isfinite(v) for v in values):
        raise ValueError(f'{obj.name}: non-finite transform')
    return {'origin': [round(x, 5) for x in lo], 'size': [round(x, 5) for x in size],
            'pivot': [round(x, 5) for x in pivot], 'rotation': [round(x, 6) for x in angles],
            'uv': [(color % 4) * 64, (color // 4) * 64]}


def export():
    items = []
    for collection in bpy.data.collections:
        identifier = collection.get('accessory_id')
        if identifier is None:
            continue
        if not re.fullmatch('[a-z]+', identifier) or collection.get('accessory_slot') not in ('head', 'wings'):
            raise ValueError(f'{collection.name}: invalid accessory metadata')
        objects = list(collection.objects)
        if not 1 <= len(objects) <= 256:
            raise ValueError(f'{collection.name}: expected 1 to 256 cuboids')
        items.append({'id': identifier, 'name': collection['accessory_name'], 'slot': collection['accessory_slot'],
                      'parts': [export_part(obj) for obj in objects]})
    if not items or len({item['id'] for item in items}) != len(items):
        raise ValueError('Accessory ids must be present and unique')
    items.sort(key=lambda item: (item['slot'], item['id']))
    palette = bpy.context.scene['accessory_palette']
    if len(palette) != 16 or any(not re.fullmatch('[0-9a-fA-F]{6}', color) for color in palette):
        raise ValueError('Expected sixteen RGB colors in scene accessory_palette')
    # Keep each cuboid on one line so geometry changes can be reviewed in a diff.
    document = '{"textureSize":256,"items":[\n' + ',\n'.join(
        json.dumps({key: item[key] for key in ('id', 'name', 'slot')})[:-1] + ',"parts":[\n' +
        ',\n'.join(json.dumps(part, separators=(',', ':')) for part in item['parts']) + '\n]}' for item in items) + '\n]}\n'
    target = ROOT/'src/client/resources/assets/clientcore/cosmetics/accessories.json'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(document)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    raw = b''.join(b'\0' + b''.join(bytes.fromhex(palette[(y // 64) * 4 + x // 64]) + b'\xff' for x in range(256)) for y in range(256))
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 256, 256, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')
    target = ROOT/'src/client/resources/assets/clientcore/textures/cosmetics/palette.png'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(png)
    print('Exported accessories:', [(item['id'], len(item['parts'])) for item in items])


if __name__ == '__main__':
    export()
