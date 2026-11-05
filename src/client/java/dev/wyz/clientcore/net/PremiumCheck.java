package dev.wyz.clientcore.net;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * The standalone mod needs a real Minecraft account. Runs before the game starts, so nothing
 * opens without one, and Fabric shows the message in its error window.
 *
 * The game's own access token is shown to the one service that issued it: Mojang answers 200 for
 * an account that owns the game, 401 for a token it does not accept and 404 for an account with
 * no profile. Anything else, or no answer at all, lets the game start, so an outage or playing
 * without internet never locks a paying player out. River's launcher signs players in itself.
 */
public final class PremiumCheck implements PreLaunchEntrypoint {
    private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final String MESSAGE =
        "River Client needs a premium Minecraft account.\n\nYour launcher is not signed in to one. "
            + "If you own Minecraft, sign in again and restart.";

    @Override
    public void onPreLaunch() {
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isDevelopmentEnvironment() && !Boolean.getBoolean("river.premiumCheck.test")) return;
        if ("river-client".equals(System.getProperty("minecraft.launcher.brand"))) return;
        if (!isPremium(argument(List.of(loader.getLaunchArguments(false)), "--accessToken"))) {
            throw new IllegalStateException(MESSAGE);
        }
    }

    private static String argument(List<String> arguments, String name) {
        int index = arguments.indexOf(name);
        return index >= 0 && index + 1 < arguments.size() ? arguments.get(index + 1) : "";
    }

    private static boolean isPremium(String token) {
        // Offline launchers pass nothing, or a placeholder far too short to be a real token.
        if (token.length() < 32) return false;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(PROFILE_URL))
                .timeout(Duration.ofSeconds(6))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status != 401 && status != 404;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Exception unreachable) {
            return true;
        }
    }
}
