"""Build the editable accessory collection and export its cuboids for the game."""
import bpy
import math
import runpy
from pathlib import Path
from mathutils import Matrix, Vector, Euler

ROOT = Path(__file__).resolve().parents[2]
PALETTE = ["172539", "263D56", "47769A", "78B7CC", "B9DFE6", "EDF5F0",
           "3ED5D0", "1D858E", "20202D", "383044", "613C48", "A6533C",
           "E77D42", "F6B96A", "D4D9DB", "101520"]
T = Matrix(((1, 0, 0), (0, 0, 1), (0, -1, 0)))
materials = []
for i, color in enumerate(PALETTE):
    material = bpy.data.materials.new("Palette %02d · %s" % (i, color))
    material.diffuse_color = (*[int(color[k:k+2], 16) / 255 for k in (0, 2, 4)], 1)
    material["palette_index"] = i
    materials.append(material)

bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)
items = []
current = None

def item(identifier, label, slot):
    global current
    collection = bpy.data.collections.new(label)
    bpy.context.scene.collection.children.link(collection)
    collection['accessory_id'] = identifier
    collection['accessory_name'] = label
    collection['accessory_slot'] = slot
    current = {"id": identifier, "name": label, "slot": slot, "collection": collection}
    items.append(current)

def box(name, origin, size, color, pivot=(0, 0, 0), rotation=(0, 0, 0)):
    vertices = []
    for x, y, z in ((0,0,0),(1,0,0),(1,1,0),(0,1,0),(0,0,1),(1,0,1),(1,1,1),(0,1,1)):
        vertices.append(T @ Vector([(origin[i] + (x,y,z)[i] * size[i]) / 16 for i in range(3)]))
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(vertices, [], [(0,3,2,1),(4,5,6,7),(0,1,5,4),(3,7,6,2),(0,4,7,3),(1,2,6,5)])
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    current["collection"].objects.link(obj)
    obj.location = T @ (Vector(pivot) / 16)
    obj.rotation_euler = (T @ Euler(rotation, 'XYZ').to_matrix() @ T.inverted()).to_euler('XYZ')
    obj.data.materials.append(materials[color])
    obj["accessory"] = current["id"]
    return obj

def stroke(name, a, b, width, depth, color, side=1, z=4):
    dx, dy = (b[0]-a[0])*side, b[1]-a[1]
    length = math.hypot(dx,dy)
    angle = math.atan2(-dx,dy)
    return box(name, (-width/2,0,-depth/2), (width,length,depth), color,
               (a[0]*side,a[1],z), (0,0,angle))

item("rivercap", "River cap", "head")
box("Crown", (-4.35,-8.9,-4.35),(8.7,2.8,8.7),0)
box("Upper crown", (-3.8,-9.8,-3.8),(7.6,1,7.6),1)
box("Top panel", (-3.15,-10.25,-3.15),(6.3,.5,6.3),1)
box("Top button", (-.45,-10.55,-.45),(.9,.3,.9),6)
box("Brim root", (-4.4,-6.55,-6.7),(8.8,.65,2.7),0)
box("Brim middle", (-3.9,-6.48,-7.8),(7.8,.58,1.5),1)
box("Brim edge", (-3.25,-6.42,-8.45),(6.5,.5,1.0),6)
box("Brim underside", (-3.7,-5.96,-7.7),(7.4,.13,3.3),7)
box("Center seam", (-.08,-10.28,-3.2),(.16,.06,6.4),2)
box("Front seam", (-.08,-9.8,-3.83),(.16,.92,.07),2)
box("Front tab", (-1.0,-8.55,-4.4),(2,.8,.12),7)
box("Tab highlight", (-.6,-8.28,-4.48),(1.2,.2,.12),6)
for side in [-1,1]:
    for z in [-1.8,0,1.8]: box("Vent", (side*4.36-.12,-7.65,z-.22),(.24,.44,.44),2)
box("Rear strap", (-2.3,-6.9,4.37),(4.6,.6,.15),1)
box("Buckle", (-.55,-6.99,4.51),(1.1,.78,.16),14)
box("Buckle inset", (-.3,-6.8,4.68),(.6,.4,.03),0)

item("frostbeanie", "Frost beanie", "head")
box("Cuff", (-4.55,-8.0,-4.55),(9.1,1.9,9.1),2)
box("Crown", (-4.3,-10.2,-4.3),(8.6,2.25,8.6),1)
box("Upper knit", (-3.8,-11.25,-3.8),(7.6,1.1,7.6),2)
box("Top knit", (-2.95,-11.75,-2.95),(5.9,.6,5.9),3)
box("Pom center", (-1.15,-14,-1.15),(2.3,2.3,2.3),4)
box("Pom horizontal", (-1.55,-13.6,-.85),(3.1,1.5,1.7),5)
box("Pom cross", (-.85,-13.6,-1.55),(1.7,1.5,3.1),4)
for i in range(9):
    v=-4.1+i*1.0
    for side in [-1,1]:
        box("Cuff rib", (v,-7.83,side*4.56-.06),(.22,1.52,.12),3)
        box("Side rib", (side*4.56-.06,-7.83,v),(.12,1.52,.22),3)
for x in [-3,-1,1,3]:
    for z in [-4.31,4.31]: box("Knit column", (x-.1,-10.1,z-.05),(.2,2,.1),2)
box("Cuff patch", (-1.05,-7.7,-4.7),(2.1,1.25,.17),0)
box("Patch stitch", (-.65,-7.23,-4.9),(1.3,.25,.08),4)

item("aurorawings", "Aurora wings", "wings")
for side in [-1,1]:
    ends=[(20,-8),(23,-3),(23,2.5),(21,8),(17,12.5),(12,15.5),(7.5,16)]
    for i,end in enumerate(ends):
        start=(3.5+i*.18,.5+i*.5)
        dx,dy=(end[0]-start[0])*side,end[1]-start[1]
        length=math.hypot(dx,dy);angle=math.atan2(-dx,dy)
        pivot=(start[0]*side,start[1],4.7+i*.08)
        width=4.4 if i<5 else 3.7
        box("Flight feather",(-width/2,0,-.45),(width,length-2.5,.9),4 if i%2 else 5,pivot,(0,0,angle))
        box("Feather shoulder",(-width*.38,length-2.5,-.4),(width*.76,1.5,.8),4,pivot,(0,0,angle))
        box("Feather tip",(-width*.23,length-1,-.35),(width*.46,1,.7),3,pivot,(0,0,angle))
        box("Feather shaft",(-.13,1,.48),(.26,length-2.2,.12),3,pivot,(0,0,angle))
        box("Cover feather",(-width*.5,.5,.62),(width,length*.58,.3),3 if i%2 else 4,pivot,(0,0,angle+.035*side))
    stroke("Leading spar",(3,.5),(16,-6),2.8,1.2,3,side,4.4)
    stroke("Leading inlay",(4,.5),(16,-5.5),.4,.25,6,side,5.1)
box("Wing clasp",(-2,1,2.2),(4,3.8,1.4),0)
box("Clasp inset",(-.9,1.8,3.65),(1.8,2,.25),6)

item("emberwings", "Ember wings", "wings")
outline=[(3,2),(10,-5),(24,-7),(20,0),(17,3),(19,8),(13,7),(12,14),(7,10),(3,10)]
def inside(x,y):
    value=False
    for i,a in enumerate(outline):
        b=outline[(i+1)%len(outline)]
        if (a[1]>y)!=(b[1]>y) and x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0]:value=not value
    return value
for side in [-1,1]:
    for y in range(-7,14):
        xs=[x for x in range(3,24) if inside(x+.5,y+.5)]
        if not xs:continue
        x=min(xs);width=max(xs)-x+1
        left=x if side>0 else -x-width
        box("Membrane",(left,y,4.3),(width,1,.6),9)
        # The inner panel is inset, leaving a charcoal border around the silhouette.
        if width>4:box("Warm web",(left+1,y,4.9),(width-2,1,.08),11)
    for a,b in [((3,2),(10,-5)),((10,-5),(24,-7)),((9,-3),(19,8)),((6,1),(12,14))]:
        stroke("Wing spar",a,b,1.0,1.1,8,side,5.15)
        stroke("Ember seam",a,b,.28,.12,12,side,5.76)
    stroke("Outer talon",(21,-6),(24,-7),.65,1.0,13,side,5.25)
box("Back clasp",(-2.2,1,2.3),(4.4,4,1.6),8)
box("Ember core",(-.8,1.8,4),(1.6,2.1,.5),12)

bpy.context.scene['accessory_palette'] = PALETTE
runpy.run_path(str(ROOT/'art/cosmetics/export_models.py'), run_name='__main__')
for definition in items:
    definition['collection'].hide_render=definition['id']!='rivercap'
    definition['collection'].hide_viewport=definition['id']!='rivercap'
bpy.context.scene.world.color=(.12,.14,.18)
for area in bpy.context.screen.areas:
    if area.type=='VIEW_3D':
        area.spaces.active.shading.color_type='MATERIAL'
        area.spaces.active.region_3d.view_distance=1.7
        area.spaces.active.region_3d.view_location=(0,0,.4)
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'art/cosmetics/river_accessories.blend'))
print('Exported',[(i['id'],len(i['collection'].objects)) for i in items])
