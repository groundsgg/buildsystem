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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerLoginsTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private static final TokenSource SERVICE_ACCOUNT = new TokenSource() {
        @Override
        public String token() {
            return "service-account-token";
        }

        @Override
        public String describe() {
            return "the build server";
        }
    };

    /** Nobody signed in: the build server acts for itself, which is the documented fallback. */
    @Test
    void falls_back_to_the_service_account() throws RegistryException {
        PlayerLogins logins = new PlayerLogins(new DeviceFlow("https://example/realms/x", "cli"));

        TokenSource auth = logins.actingAs(PLAYER, SERVICE_ACCOUNT);

        assertEquals("service-account-token", auth.token());
        assertEquals("the build server", auth.describe());
        assertFalse(logins.isSignedIn(PLAYER));
    }

    /** Signed in: the builder's own token wins, so the registry records a person. */
    @Test
    void prefers_the_signed_in_builder() throws RegistryException {
        PlayerLogins logins = new PlayerLogins(new DeviceFlow("https://example/realms/x", "cli"));
        logins.remember(
                PLAYER,
                "hendrik",
                new DeviceFlow.Tokens("player-token", "refresh", Instant.now().plusSeconds(3600)));

        TokenSource auth = logins.actingAs(PLAYER, SERVICE_ACCOUNT);

        assertEquals("player-token", auth.token());
        assertEquals("hendrik", auth.describe());
    }

    /**
     * An expired token with no refresh token is a login that is over. Keeping the session would
     * fail the same way on every later command while still reporting the builder as signed in.
     */
    @Test
    void drops_a_session_that_cannot_be_refreshed() {
        PlayerLogins logins = new PlayerLogins(new DeviceFlow("https://example/realms/x", "cli"));
        logins.remember(
                PLAYER,
                "hendrik",
                new DeviceFlow.Tokens("stale", "", Instant.now().minusSeconds(1)));

        TokenSource auth = logins.actingAs(PLAYER, SERVICE_ACCOUNT);

        assertThrows(RegistryException.class, auth::token);
        assertFalse(logins.isSignedIn(PLAYER), "the dead session must not survive");
    }

    @Test
    void signing_out_forgets_the_session() {
        PlayerLogins logins = new PlayerLogins(new DeviceFlow("https://example/realms/x", "cli"));
        logins.remember(
                PLAYER, "hendrik", new DeviceFlow.Tokens("t", "r", Instant.now().plusSeconds(60)));
        assertTrue(logins.isSignedIn(PLAYER));

        logins.forget(PLAYER);

        assertFalse(logins.isSignedIn(PLAYER));
    }
}
