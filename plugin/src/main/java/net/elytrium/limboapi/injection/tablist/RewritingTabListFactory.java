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

package net.elytrium.limboapi.injection.tablist;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.tablist.InternalTabList;
import com.velocitypowered.proxy.tablist.KeyedVelocityTabList;
import com.velocitypowered.proxy.tablist.VelocityTabListLegacy;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.elytrium.commons.utils.reflection.ReflectionException;
import sun.misc.Unsafe;

public final class RewritingTabListFactory {

  private static final Unsafe UNSAFE;

  // Velocity-CTD based forks (e.g. VeloFlame) accept VelocityServer instead of ProxyServer in these constructors,
  // so the rewriting tab lists can't call them there.
  private static final boolean KEYED_TAB_LIST_CONSTRUCTOR_SUPPORTED;
  private static final boolean LEGACY_TAB_LIST_CONSTRUCTOR_SUPPORTED;

  private RewritingTabListFactory() {

  }

  public static InternalTabList create(ConnectedPlayer player, VelocityServer server) throws ReflectiveOperationException {
    ProtocolVersion version = player.getProtocolVersion();
    if (version.noLessThan(ProtocolVersion.MINECRAFT_1_19_3)) {
      return new RewritingVelocityTabList(player);
    } else if (version.noLessThan(ProtocolVersion.MINECRAFT_1_8)) {
      return KEYED_TAB_LIST_CONSTRUCTOR_SUPPORTED
          ? new RewritingKeyedVelocityTabList(player, server)
          : copyOf(RewritingKeyedVelocityTabList.class, player.getTabList());
    } else {
      return LEGACY_TAB_LIST_CONSTRUCTOR_SUPPORTED
          ? new RewritingVelocityTabListLegacy(player, server)
          : copyOf(RewritingVelocityTabListLegacy.class, player.getTabList());
    }
  }

  /**
   * Creates the rewriting tab list without calling its constructor, copying the state of the tab list
   * that was created by the proxy for the player.
   */
  private static <T extends InternalTabList> T copyOf(Class<T> type, InternalTabList original) throws ReflectiveOperationException {
    if (original.getClass() != type.getSuperclass()) {
      throw new IllegalStateException("Unexpected tab list " + original.getClass().getName() + ", expected " + type.getSuperclass().getName());
    }

    T tabList = type.cast(UNSAFE.allocateInstance(type));
    for (Class<?> clazz = type; clazz != Object.class; clazz = clazz.getSuperclass()) {
      for (Field field : clazz.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) {
          continue;
        }

        // Fields of the rewriting tab list mirror the same named fields of the original one.
        Field source = clazz == type ? findField(original.getClass(), field.getName()) : field;
        source.setAccessible(true);
        field.setAccessible(true);
        field.set(tabList, source.get(original));
      }
    }

    return tabList;
  }

  private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
    for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        if (field.getName().equals(name) && !Modifier.isStatic(field.getModifiers())) {
          return field;
        }
      }
    }

    throw new NoSuchFieldException(clazz.getName() + "#" + name);
  }

  private static boolean hasProxyServerConstructor(Class<?> clazz) {
    try {
      clazz.getConstructor(ConnectedPlayer.class, ProxyServer.class);
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  static {
    try {
      Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      UNSAFE = (Unsafe) unsafeField.get(null);
    } catch (ReflectiveOperationException e) {
      throw new ReflectionException(e);
    }

    KEYED_TAB_LIST_CONSTRUCTOR_SUPPORTED = hasProxyServerConstructor(KeyedVelocityTabList.class);
    LEGACY_TAB_LIST_CONSTRUCTOR_SUPPORTED = hasProxyServerConstructor(VelocityTabListLegacy.class);
  }
}
