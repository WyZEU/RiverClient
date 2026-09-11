/**
 * Stonecutter controller.
 *
 *   ./gradlew build                          builds every version
 *   ./gradlew ":1.21.4:build"                builds one
 *   ./gradlew '"Set active project to 1.21.4"'  switches which version the IDE
 *                                            resolves against, rewriting the //?
 *                                            comment branches in src/ in place
 *   ./gradlew '"Reset active project"'        back to the vcs version - run this
 *                                            before committing
 *
 * Adding a version is a folder under versions/ with a gradle.properties and one
 * more entry in settings.gradle.kts. No source is copied.
 *
 * There are three API generations in the list, not five. Minecraft renamed things
 * in most 1.21.x releases, so every //? block is written against whichever release
 * actually changed the API it guards:
 *
 *   >=1.21.11   Identifier (was ResourceLocation), the input event classes
 *               (MouseButtonEvent / KeyEvent / CharacterEvent), the entity
 *               render-state path, Util, the debug-screen entries
 *   >=1.21.6    the GUI render rework - pose() is a Matrix3x2fStack
 *               (pushMatrix/popMatrix, 2-arg translate/scale), blit takes a
 *               RenderPipeline, and the MobEffects constants were renamed
 *   else        1.21.4
 *
 * 1.21.6, 1.21.7 and 1.21.8 are the same generation, so they cost nothing extra -
 * a change that compiles on one compiles on all three. Only two blocks needed a
 * genuine third branch (//?} elif >=1.21.6 {): the disconnect call in
 * QuickDisconnectModule and the entity preview in RiverCosmeticsScreen.
 *
 * Adding 1.21.5, or 1.21.9/1.21.10, would each mean a fourth and fifth generation
 * to keep in step on every future edit, which is why they are not in the list.
 */
plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.11"
