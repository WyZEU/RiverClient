import rig from "../animations/idle.bbmodel";

/*
 * The idle animation for the skin viewer, read straight from animations/idle.bbmodel: export a
 * new one from Blockbench over that file and rebuild. Bones are the groups named Head, Body,
 * Right Arm, Left Arm, Right Leg and Left Leg; a bone turns about its group's origin, from the
 * group's own rotation plus its animation keys (rotation only, linear in between), in
 * Blockbench's degrees. The first animation in the file is used.
 *
 * Blockbench plays an animation once; here it loops, and each bone eases from its last key
 * back to its first one over the rest of the animation, so the loop has no jump.
 */

const BONE_OF = {
  Head: "head", Body: "body", "Right Arm": "armR", "Left Arm": "armL", "Right Leg": "legR", "Left Leg": "legL"
};

const animation = rig.animations[0];
export const IDLE_LENGTH = animation.length;

/** Each bone's pivot in the skin viewer's pixels, whose axes run opposite to Blockbench's. */
export const IDLE_PIVOT = {};

const BONES = {};
// Key values are strings in the file, and a stray newline or dot can sit in them ("-2.\n").
const number = (value) => (Number.isFinite(parseFloat(value)) ? parseFloat(value) : 0);

for (const group of rig.groups) {
  const bone = BONE_OF[group.name];
  if (!bone) continue;
  const [ox, oy, oz] = group.origin;
  IDLE_PIVOT[bone] = [-ox, 16 - oy, -oz];
  const keys = (animation.animators[group.uuid]?.keyframes || [])
    .filter((key) => key.channel === "rotation")
    .map((key) => [key.time, number(key.data_points[0].x), number(key.data_points[0].y), number(key.data_points[0].z)])
    .sort((a, b) => a[0] - b[0]);
  BONES[bone] = { rest: group.rotation, keys: keys.length ? keys : [[0, 0, 0, 0]] };
}

function sampleKeys(keys, time) {
  const n = keys.length;
  if (n === 1) return [keys[0][1], keys[0][2], keys[0][3]];
  let i = n - 1;
  for (let k = 0; k < n; k++) if (keys[k][0] <= time) i = k;
  const j = (i + 1) % n;
  const start = keys[i][0];
  const end = keys[j][0] + (j <= i ? IDLE_LENGTH : 0);
  const a = ((time >= start ? time : time + IDLE_LENGTH) - start) / (end - start);
  return [1, 2, 3].map((axis) => keys[i][axis] + (keys[j][axis] - keys[i][axis]) * a);
}

/** Where every bone points at time t (seconds): { head: [x, y, z], ... } in Blockbench degrees. */
export function sampleIdle(t) {
  const time = ((t % IDLE_LENGTH) + IDLE_LENGTH) % IDLE_LENGTH;
  const pose = {};
  for (const [bone, { rest, keys }] of Object.entries(BONES)) {
    const anim = sampleKeys(keys, time);
    pose[bone] = rest.map((value, axis) => value + anim[axis]);
  }
  return pose;
}
