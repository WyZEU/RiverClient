package dev.wyz.riverbootstrap;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * River launch agent.
 *
 * <p>The River in-game client is NOT a Fabric mod: it never sits in the mods folder, so Fabric
 * never lists it. Instead this agent, at exactly the right moment, does two things:
 *
 * <ol>
 *   <li>hands the clientcore jar (path from {@code -Driver.client.jar}) to Fabric's Knot
 *       launcher via {@code FabricLauncherBase.getLauncher().addToClassPath(...)}, so River's
 *       classes live in the SAME classloader as Minecraft — required for linking against game
 *       classes and for Mixin to read our config/refmap resources;</li>
 *   <li>registers {@code clientcore.mixins.json} with {@code Mixins.addConfiguration(...)}.</li>
 * </ol>
 *
 * <p>Timing: both steps must run after Fabric has bootstrapped Mixin but before any of our
 * target Minecraft classes load. We hook that window by watching for a sentinel class load via
 * the JVM instrumentation API — the vanilla client entrypoint, which loads after Fabric's mixin
 * setup and before {@code Minecraft}/{@code Gui}/etc.
 *
 * <p>Everything resolves through the sentinel's own class loader (Knot's transforming loader),
 * guaranteeing we talk to the same Fabric/Mixin instances the game uses.
 */
public final class Agent {

    private static final String MIXIN_CONFIG =
            System.getProperty("river.mixin.config", "clientcore.mixins.json");

    private static final String CLIENT_JAR = System.getProperty("river.client.jar", "");

    /** Class whose load marks "Mixin is ready, targets not yet loaded". Overridable for tuning. */
    private static final String SENTINEL =
            System.getProperty("river.mixin.sentinel", "net/minecraft/client/main/Main")
                    .replace('.', '/');

    private static final AtomicBoolean injected = new AtomicBoolean(false);

    private Agent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        log("agent active — client jar: " + (CLIENT_JAR.isBlank() ? "<none>" : CLIENT_JAR));
        log("will inject " + MIXIN_CONFIG + " when " + SENTINEL + " loads");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader,
                                    String className,
                                    Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain,
                                    byte[] classfileBuffer) {
                if (SENTINEL.equals(className) && injected.compareAndSet(false, true)) {
                    inject(loader);
                }
                // Never modify bytecode here; this transformer exists only for its timing side effect.
                return null;
            }
        });
    }

    /** Allow {@code -javaagent} attach at startup to also work as {@code agentmain} if ever needed. */
    public static void agentmain(String args, Instrumentation instrumentation) {
        premain(args, instrumentation);
    }

    private static void inject(ClassLoader sentinelLoader) {
        ClassLoader target = sentinelLoader != null
                ? sentinelLoader
                : Thread.currentThread().getContextClassLoader();
        addClientJarToKnot(target);
        registerMixins(target);
    }

    /**
     * Adds the clientcore jar to Fabric's own classloader. Without this the jar only exists on
     * the JVM application classpath, which Knot isolates from — mixin helper classes would fail
     * to link against Minecraft classes at runtime.
     */
    private static void addClientJarToKnot(ClassLoader target) {
        if (CLIENT_JAR.isBlank()) {
            log("no -Driver.client.jar supplied; skipping classpath injection");
            return;
        }
        Path jar = Path.of(CLIENT_JAR);
        if (!Files.isRegularFile(jar)) {
            log("FAILED: client jar does not exist: " + jar);
            return;
        }
        try {
            Class<?> launcherBase = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase", true, target);
            Object launcher = launcherBase.getMethod("getLauncher").invoke(null);
            Method add = launcher.getClass().getMethod("addToClassPath", Path.class, String[].class);
            add.setAccessible(true);
            add.invoke(launcher, jar, new String[0]);
            log("client jar added to Fabric classloader: " + jar.getFileName());
        } catch (Throwable error) {
            log("FAILED to add client jar to Fabric classloader: " + error);
            error.printStackTrace();
        }
    }

    private static void registerMixins(ClassLoader target) {
        try {
            Class<?> mixins = Class.forName("org.spongepowered.asm.mixin.Mixins", true, target);
            Method addConfiguration = mixins.getMethod("addConfiguration", String.class);
            addConfiguration.invoke(null, MIXIN_CONFIG);
            log("registered mixin config " + MIXIN_CONFIG + " (loader=" + target + ")");
        } catch (Throwable error) {
            log("FAILED to register mixin config " + MIXIN_CONFIG + ": " + error);
            error.printStackTrace();
        }
    }

    private static void log(String message) {
        System.out.println("[river-bootstrap] " + message);
    }
}
