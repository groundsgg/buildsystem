/*
 * Copyright (c) 2026, Grounds
 * Copyright (c) 2018-2026, Thomas Meaney
 * Copyright (c) contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package gg.grounds.buildsystem.registry;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Who is signed in, in memory only.
 *
 * <p><strong>Nothing is written to disk.</strong> A token on disk outlives the session it was
 * granted for, survives a backup, and ends up in whatever copies the world folder — for a
 * credential that expires in minutes and can be re-obtained in fifteen seconds, that trade is not
 * worth making. A restart means signing in again, which is the correct cost.
 */
@NullMarked
public final class PlayerLogins {

    /** Refresh slightly early, so a long upload never begins on a token about to expire. */
    private static final Duration MARGIN = Duration.ofSeconds(30);

    private final DeviceFlow flow;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public PlayerLogins(DeviceFlow flow) {
        this.flow = flow;
    }

    private static final class Session {
        private DeviceFlow.Tokens tokens;
        private final String name;

        private Session(DeviceFlow.Tokens tokens, String name) {
            this.tokens = tokens;
            this.name = name;
        }
    }

    public void remember(UUID player, String name, DeviceFlow.Tokens tokens) {
        sessions.put(player, new Session(tokens, name));
    }

    public boolean isSignedIn(UUID player) {
        return sessions.containsKey(player);
    }

    public void forget(UUID player) {
        sessions.remove(player);
    }

    /**
     * The identity a command should act as: the player if they are signed in, otherwise the build
     * server's own service account.
     *
     * <p>Preferring the player is the whole point of the login — "published by the build server" is
     * true but useless when three people share a world.
     */
    public TokenSource actingAs(UUID player, TokenSource fallback) {
        Session session = sessions.get(player);
        if (session == null) {
            return fallback;
        }
        return new TokenSource() {
            @Override
            public String token() throws RegistryException {
                synchronized (session) {
                    if (Instant.now().isAfter(session.tokens.expiresAt().minus(MARGIN))) {
                        if (session.tokens.refreshToken().isEmpty()) {
                            sessions.remove(player);
                            throw new RegistryException("Your login expired. Run /map login again.");
                        }
                        try {
                            session.tokens = flow.refresh(session.tokens.refreshToken());
                        } catch (RegistryException e) {
                            // A refresh that is refused is a login that is over; leaving the
                            // session behind would fail the same way on every later command.
                            sessions.remove(player);
                            throw e;
                        }
                    }
                    return session.tokens.accessToken();
                }
            }

            @Override
            public String describe() {
                return session.name;
            }
        };
    }

    public @Nullable String nameOf(UUID player) {
        Session session = sessions.get(player);
        return session == null ? null : session.name;
    }
}
