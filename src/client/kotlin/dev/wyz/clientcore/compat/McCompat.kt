package dev.wyz.clientcore.compat

/**
 * Cross-version aliases for Minecraft classes that were only renamed.
 *
 * Most of the difference between the 1.21.4 and 1.21.11 trees is not different
 * logic, it is the same class under a new name - `ResourceLocation` became
 * `Identifier`, and so on. Writing a `//?` conditional at every use site would
 * mean six or more of them per file for what is one decision.
 *
 * Declaring the alias once here means the rest of the codebase writes `McId` and
 * never mentions a version. Add a new alias here whenever a rename is the only
 * thing standing between the two trees; keep genuine behaviour differences as
 * `//?` blocks at the point they actually differ.
 */

//? if >=1.21.11 {
typealias McId = net.minecraft.resources.Identifier
//?} else {
/*typealias McId = net.minecraft.resources.ResourceLocation
*///?}
