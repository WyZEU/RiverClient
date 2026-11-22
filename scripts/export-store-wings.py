"""Export the Store wing meshes and animated texture atlases from Blockbench."""

import base64
import json
import math
from pathlib import Path
import struct
import sys


PRODUCTS = {
    "ember_dragon_wings": "wingemberdragon",
    "verdant_bloom_wings": "wingverdantbloom",
    "voidshard_wings": "wingvoidshard",
    "vampire_lord_wings": "wingvampirelord",
    "river_regalia_wings": "wingriverregalia",
}
BACK_OFFSET = 3
DROP = 3
FACES = {
    "north": (5, 4, 7, 6), "south": (0, 1, 2, 3),
    "east": (1, 5, 6, 2), "west": (4, 0, 3, 7),
    "up": (4, 5, 1, 0), "down": (3, 2, 6, 7),
}


def rotate(point, pivot, angles):
    x, y, z = (value - origin for value, origin in zip(point, pivot))
    ax, ay, az = map(math.radians, angles)
    c, s = math.cos(ax), math.sin(ax)
    y, z = y * c - z * s, y * s + z * c
    c, s = math.cos(ay), math.sin(ay)
    x, z = x * c + z * s, z * c - x * s
    c, s = math.cos(az), math.sin(az)
    x, y = x * c - y * s, x * s + y * c
    return x + pivot[0], y + pivot[1], z + pivot[2]


def angles_at(keys, time):
    if not keys:
        return (0, 0, 0)
    for (start, a), (end, b) in zip(keys, keys[1:]):
        if start <= time < end:
            t = (time - start) / (end - start)
            return tuple(x + (y - x) * t for x, y in zip(a, b))
    return keys[0 if time < keys[0][0] else -1][1]


def collect(model):
    elements = {element["uuid"]: element for element in model["elements"]}
    faces = []

    def walk(nodes, parents):
        for node in nodes:
            if not isinstance(node, str):
                if node.get("visibility", True):
                    walk(node["children"], parents + [node])
                continue
            cube = elements[node]
            if not cube.get("visibility", True):
                continue
            x0, y0, z0 = cube["from"]
            x1, y1, z1 = cube["to"]
            # Keep the mount on the torso and preserve the cloak hem.
            if cube["name"] == "spine_plate":
                z1 += BACK_OFFSET
            elif cube["name"] == "cape":
                y0 += DROP
            vertices = [
                (x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1),
                (x0, y1, z0), (x1, y1, z0), (x1, y0, z0), (x0, y0, z0),
            ]
            rest = cube.get("rotation", (0, 0, 0))
            vertices = [rotate(vertex, cube["origin"], rest) for vertex in vertices]
            for name, indices in FACES.items():
                face = cube["faces"].get(name)
                if not face or face.get("texture") is None or z0 == z1 and name not in ("north", "south"):
                    continue
                u0, v0, u1, v1 = face["uv"]
                uv = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
                for _ in range(face.get("rotation", 0) // 90):
                    uv = uv[-1:] + uv[:-1]
                faces.append(([vertices[i] for i in indices[::-1]], uv[::-1], parents))

    walk(model["outliner"], [])
    return faces


def export(source, cosmetic, output):
    model = json.loads(source.read_text())
    texture = model["textures"][0]
    png = base64.b64decode(texture["source"].split(",", 1)[1], validate=True)
    width, height = struct.unpack_from(">II", png, 16)
    frame_height = texture["uv_height"]
    frame_ms = round(texture["frame_time"] * 1000)
    length = model["animations"][0]["length"]
    duration = round(length * 1000)
    frame_count = round(length * 20)
    faces = collect(model)
    keys = {}
    for group_id, animator in model["animations"][0]["animators"].items():
        keys[group_id] = sorted(
            (key["time"], tuple(float(key["data_points"][0][axis]) for axis in "xyz"))
            for key in animator["keyframes"] if key["channel"] == "rotation"
        )

    data = bytearray(struct.pack(">I7H", 0x52574E31, len(faces), frame_count, duration, width, height, frame_height, frame_ms))
    for _, uv, _ in faces:
        for u, v in uv:
            data.extend(struct.pack(">ff", u / width, v / height))
    for frame in range(frame_count):
        time = length * frame / frame_count
        for vertices, _, parents in faces:
            posed = vertices
            for parent in reversed(parents):
                rest = parent.get("rotation", (0, 0, 0))
                offset = angles_at(keys.get(parent["uuid"]), time)
                angles = [a + b for a, b in zip(rest, offset)]
                posed = [rotate(vertex, parent["origin"], angles) for vertex in posed]
            posed = [(x, 24 - y + DROP, -z + BACK_OFFSET) for x, y, z in posed]
            a, b, c, _ = posed
            x, y, z = (b[i] - a[i] for i in range(3))
            u, v, w = (c[i] - a[i] for i in range(3))
            normal = (y * w - z * v, z * u - x * w, x * v - y * u)
            norm = math.sqrt(sum(n * n for n in normal))
            data.extend(struct.pack(">3h", *(round(n * 32767 / norm) for n in normal)))
            for point in posed:
                data.extend(struct.pack(">3h", *(round(value * 256) for value in point)))
    (output / f"{cosmetic}.bin").write_bytes(data)
    (output / f"{cosmetic}.png").write_bytes(png)


if __name__ == "__main__":
    source_dir = Path(sys.argv[1])
    output_dir = Path("src/main/resources/assets/clientcore/textures/cosmetics/wings")
    output_dir.mkdir(parents=True, exist_ok=True)
    for stem, asset in PRODUCTS.items():
        source_path = source_dir / f"{stem}.bbmodel"
        if not source_path.exists():
            source_path = source_dir / f"{stem} (1).bbmodel"
        export(source_path, asset, output_dir)
