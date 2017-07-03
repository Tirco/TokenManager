/*
 *
 *   This file is part of TokenManager, licensed under the MIT License.
 *
 *   Copyright (c) Realized
 *   Copyright (c) contributors
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in all
 *   copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *   SOFTWARE.
 *
 */

package me.realized.tokenmanager.data;

import lombok.Getter;
import me.realized.tokenmanager.TokenManager;
import me.realized.tokenmanager.data.database.Database;
import me.realized.tokenmanager.data.database.MySQLDatabase;
import me.realized.tokenmanager.data.database.SQLiteDatabase;
import me.realized.tokenmanager.util.StringUtil;
import me.realized.tokenmanager.util.plugin.AbstractPluginDelegate;
import me.realized.tokenmanager.util.plugin.Reloadable;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DataManager extends AbstractPluginDelegate<TokenManager> implements Reloadable, Listener {

    private Database database;

    @Getter
    private List<Database.RankedData> topCache = new ArrayList<>();
    private Integer updateInterval;
    private long lastUpdateMillis;
    private Integer task;

    public DataManager(final TokenManager plugin) {
        super(plugin);
    }

    public Optional<Integer> get(final Player player) {
        return database != null ? database.get(player) : Optional.empty();
    }

    public void set(final Player player, final int amount) {
        if (database != null) {
            database.set(player, amount);
        }
    }

    private int getUpdateInterval() {
        if (updateInterval != null) {
            return updateInterval;
        }

        return (updateInterval = getPlugin().getConfiguration().getBalanceTopUpdateInterval()) < 1 ? 1 : updateInterval;
    }

    public String getNextUpdate() {
        return StringUtil.format((lastUpdateMillis + 60000L * getUpdateInterval() - System.currentTimeMillis()) / 1000);
    }

    @EventHandler
    public void on(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        database.load(player, args -> Bukkit.getScheduler().runTask(getPlugin(), () -> database.set(player, args)));
    }

    @EventHandler
    public void on(PlayerQuitEvent event) {
        database.save(event.getPlayer());
    }

    @Override
    public void handleLoad() throws Exception {
        this.database = getPlugin().getConfiguration().isMysqlEnabled() ? new MySQLDatabase(getPlugin()) : new SQLiteDatabase(getPlugin());

        database.setup();

        // Transfer data from versions below 3.0
        File file = new File(getPlugin().getDataFolder(), "data.yml");

        if (file.exists()) {
            database.transfer(file);
        }

        // Task runs sync since Database#ordered creates a copy of the data cache
        task = Bukkit.getScheduler().runTaskTimer(getPlugin(), () -> database.ordered(5, args -> Bukkit.getScheduler().runTask(getPlugin(), () -> {
            lastUpdateMillis = System.currentTimeMillis();
            topCache = args;
        })), 0L, 20L * 60L * getUpdateInterval()).getTaskId();

        Bukkit.getPluginManager().registerEvents(this, getPlugin());
    }

    @Override
    public void handleUnload() throws Exception {
        if (task != null) {
            BukkitScheduler scheduler = Bukkit.getScheduler();

            if (scheduler.isCurrentlyRunning(task) || scheduler.isQueued(task)) {
                scheduler.cancelTask(task);
            }
        }

        database.save();
    }
}