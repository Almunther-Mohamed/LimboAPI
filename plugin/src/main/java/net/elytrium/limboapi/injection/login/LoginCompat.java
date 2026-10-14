/*
 * Copyright (C) 2021 - 2025 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.limboapi.injection.login;

import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import net.elytrium.commons.utils.reflection.ReflectionException;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * As LimboAPI completes the player login by itself, it has to follow the login lifecycle of the proxy it runs on.
 * Velocity forks based on Velocity-CTD (e.g. VeloFlame) changed some of the internals used during the login, so the
 * differences are resolved here at runtime, allowing the same build to work on both Velocity and its forks.
 */
public final class LoginCompat {

  // Returns com.velocitypowered.proxy.config.PlayerInfoForwarding on Velocity,
  // but com.velocitypowered.api.proxy.server.PlayerInfoForwarding on Velocity-CTD based forks.
  private static final MethodHandle GET_PLAYER_INFO_FORWARDING_MODE;

  // Velocity registers the connection synchronously, Velocity-CTD based forks return a CompletableFuture<Boolean>
  // and lock the player identity until the login is finalized.
  @Nullable
  private static final MethodHandle REGISTER_CONNECTION_SYNC;
  @Nullable
  private static final MethodHandle REGISTER_CONNECTION_ASYNC;

  // Velocity-CTD based forks only, all of these are absent on Velocity.
  // DisconnectEvent is fired on these forks only if the player is marked as the one who has received LoginEvent.
  @Nullable
  private static final MethodHandle MARK_LOGIN_EVENT_FIRED;
  // Registers the player in the cluster (e.g. in redis) and in the queue manager.
  @Nullable
  private static final MethodHandle CLUSTER_ON_PLAYER_CONNECT;
  @Nullable
  private static final MethodHandle FULLY_CONNECTED;
  // Releases the identity lock acquired during the registration, otherwise the player gets kicked after some time.
  @Nullable
  private static final MethodHandle FINALIZE_LOGIN;

  private LoginCompat() {

  }

  public static boolean isPlayerInfoForwardingDisabled(VelocityConfiguration configuration) throws Throwable {
    Enum<?> mode = (Enum<?>) GET_PLAYER_INFO_FORWARDING_MODE.invokeExact(configuration);
    return mode.name().equals("NONE");
  }

  @SuppressWarnings("unchecked")
  public static CompletableFuture<Boolean> registerConnection(VelocityServer server, ConnectedPlayer player) throws Throwable {
    if (REGISTER_CONNECTION_SYNC != null) {
      return CompletableFuture.completedFuture((boolean) REGISTER_CONNECTION_SYNC.invokeExact(server, player));
    }

    return (CompletableFuture<Boolean>) REGISTER_CONNECTION_ASYNC.invokeExact(server, player);
  }

  public static void markLoginEventFired(ConnectedPlayer player) throws Throwable {
    if (MARK_LOGIN_EVENT_FIRED != null) {
      MARK_LOGIN_EVENT_FIRED.invokeExact(player);
    }
  }

  /**
   * Should be called once the player has been registered and the login can't be denied by LoginEvent anymore.
   *
   * @return false if the proxy has rejected the login (e.g. the player is already online on another proxy of the cluster)
   */
  public static boolean onPlayerConnected(VelocityServer server, ConnectedPlayer player) throws Throwable {
    if (CLUSTER_ON_PLAYER_CONNECT != null && !(boolean) CLUSTER_ON_PLAYER_CONNECT.invokeExact(server, player)) {
      return false;
    }

    if (FULLY_CONNECTED != null) {
      FULLY_CONNECTED.invokeExact(player);
    }

    return true;
  }

  /**
   * Should be called right after PostLoginEvent.
   */
  public static void finalizeLogin(VelocityServer server, ConnectedPlayer player) throws Throwable {
    if (FINALIZE_LOGIN != null) {
      FINALIZE_LOGIN.invokeExact(server, player);
    }
  }

  @Nullable
  private static Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
    try {
      return clazz.getMethod(name, parameterTypes);
    } catch (NoSuchMethodException e) {
      try {
        return clazz.getDeclaredMethod(name, parameterTypes);
      } catch (NoSuchMethodException ignored) {
        return null;
      }
    }
  }

  private static MethodHandle unreflect(Method method) throws IllegalAccessException {
    return MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup()).unreflect(method);
  }

  // Creates (VelocityServer, ConnectedPlayer)<return type> handle, that calls server.<getter>().<method>(player).
  @Nullable
  private static MethodHandle findServiceMethod(String getterName, String methodName, Class<?> returnType) throws IllegalAccessException {
    // The getter may be declared multiple times with covariant return types (e.g. API and internal ones),
    // the internal method is declared only in the internal type.
    for (Method getter : VelocityServer.class.getMethods()) {
      if (!getter.getName().equals(getterName) || getter.getParameterCount() != 0) {
        continue;
      }

      Method method = findMethod(getter.getReturnType(), methodName, ConnectedPlayer.class);
      if (method != null && method.getReturnType() == returnType) {
        MethodHandle methodHandle = unreflect(method).asType(MethodType.methodType(returnType, Object.class, ConnectedPlayer.class));
        MethodHandle getterHandle = unreflect(getter).asType(MethodType.methodType(Object.class, VelocityServer.class));
        return MethodHandles.filterArguments(methodHandle, 0, getterHandle);
      }
    }

    return null;
  }

  static {
    try {
      Method forwardingModeGetter = VelocityConfiguration.class.getMethod("getPlayerInfoForwardingMode");
      GET_PLAYER_INFO_FORWARDING_MODE = unreflect(forwardingModeGetter).asType(MethodType.methodType(Enum.class, VelocityConfiguration.class));

      Method registerConnection = VelocityServer.class.getMethod("registerConnection", ConnectedPlayer.class);
      MethodHandle registerConnectionHandle = unreflect(registerConnection);
      if (registerConnection.getReturnType() == boolean.class) {
        REGISTER_CONNECTION_SYNC = registerConnectionHandle;
        REGISTER_CONNECTION_ASYNC = null;
      } else if (CompletableFuture.class.isAssignableFrom(registerConnection.getReturnType())) {
        REGISTER_CONNECTION_SYNC = null;
        REGISTER_CONNECTION_ASYNC = registerConnectionHandle.asType(
            MethodType.methodType(CompletableFuture.class, VelocityServer.class, ConnectedPlayer.class));
      } else {
        throw new NoSuchMethodException("Unsupported VelocityServer#registerConnection return type: " + registerConnection.getReturnType());
      }

      Method markLoginEventFired = findMethod(ConnectedPlayer.class, "markLoginEventFired");
      MARK_LOGIN_EVENT_FIRED = markLoginEventFired == null ? null : unreflect(markLoginEventFired);

      Method fullyConnected = findMethod(ConnectedPlayer.class, "fullyConnected");
      FULLY_CONNECTED = fullyConnected == null ? null : unreflect(fullyConnected);

      CLUSTER_ON_PLAYER_CONNECT = findServiceMethod("getClusterPlayerService", "onPlayerConnect", boolean.class);
      FINALIZE_LOGIN = findServiceMethod("getPlayerRegistry", "finalizeLogin", void.class);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ReflectionException(e);
    }
  }
}
