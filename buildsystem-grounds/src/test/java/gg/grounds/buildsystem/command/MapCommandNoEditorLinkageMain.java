package gg.grounds.buildsystem.command;

import de.eintosti.buildsystem.api.world.BuildWorld;
import gg.grounds.buildsystem.world.MapLinks;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;

/** Runs in an API-free JavaExec classpath; see noEditorLinkageTest. */
public final class MapCommandNoEditorLinkageMain {

    private MapCommandNoEditorLinkageMain() {}

    public static void main(String[] args) throws Exception {
        ClassLoader loader = MapCommandNoEditorLinkageMain.class.getClassLoader();
        try {
            Class.forName("gg.grounds.scene.editor.SceneEditStatus", false, loader);
            throw new AssertionError("SceneEditStatus must be absent from the no-editor runtime");
        } catch (ClassNotFoundException expected) {
            // MapCommand must still load and run this path.
        }
        List<String> messages = new ArrayList<>();
        SceneEditorPushGuard guard = new SceneEditorPushGuard(pluginManager(), services(), Logger.getAnonymousLogger());
        MapCommand command = new MapCommand(
                null,
                null,
                null,
                null,
                new MapLinks(Files.createTempDirectory("map-links-").toFile()),
                null,
                guard);
        Method push = MapCommand.class.getDeclaredMethod("push", Player.class, BuildWorld.class, String[].class);
        push.setAccessible(true);
        push.invoke(command, player(messages), world(), new String[] {"push"});
        if (!messages.equals(List.of("Which map? /map push <namespace/name> — or /map link it once."))) {
            throw new AssertionError("Expected push to pass the absent-editor guard, got: " + messages);
        }
    }

    private static PluginManager pluginManager() {
        return proxy(
                PluginManager.class, (method, ignored) -> method.getName().equals("isPluginEnabled") ? false : null);
    }

    private static ServicesManager services() {
        return proxy(ServicesManager.class, (method, ignored) -> null);
    }

    private static Player player(List<String> messages) {
        return proxy(Player.class, (method, values) -> {
            if (method.getName().equals("sendMessage")) {
                messages.add(((TextComponent) values[0]).content());
            }
            return null;
        });
    }

    private static BuildWorld world() {
        return proxy(BuildWorld.class, (method, ignored) -> {
            if (method.getName().equals("getUniqueId")) {
                return new UUID(5L, 6L);
            }
            if (method.getName().equals("getWorld")) {
                return Optional.of(proxy(
                        World.class,
                        (worldMethod, values) -> worldMethod.getName().equals("getUID") ? new UUID(7L, 8L) : null));
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, values) -> invocation.invoke(method, values));
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Method method, Object[] arguments) throws Throwable;
    }
}
