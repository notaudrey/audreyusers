package me.curlpipesh.users;

import lombok.Getter;
import me.curlpipesh.users.attribute.Attribute;
import me.curlpipesh.users.command.CommandKD;
import me.curlpipesh.users.command.CommandPlaytime;
import me.curlpipesh.users.listeners.PlayerListener;
import me.curlpipesh.users.tasks.PlayerMapTask;
import me.curlpipesh.users.user.SkirtsUser;
import me.curlpipesh.users.user.SkirtsUserMap;
import me.curlpipesh.util.command.SkirtsCommand;
import me.curlpipesh.util.database.IDatabase;
import me.curlpipesh.util.database.impl.SQLiteDatabase;
import me.curlpipesh.util.plugin.SkirtsPlugin;
import org.bukkit.Bukkit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * TODO: Make an actual API ;-;
 *
 * @author audrey
 * @since 12/21/15.
 */
@SuppressWarnings({"Duplicates", "unused"})
public class Users extends SkirtsPlugin {
    @Getter
    private IDatabase userDb;
    @Getter
    private final String userDbName = "users";
    @Getter
    private final String attributeDbName = "attributes";

    @SuppressWarnings("StaticVariableOfConcreteClass")
    @Getter
    private static Users instance;

    @Getter
    private SkirtsUserMap skirtsUserMap;

    public Users() {
        instance = this;
    }

    @Override
    public void onEnable() {
        if(!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }
        //saveDefaultConfig();
        // Create database for users. IF NOT EXISTS because we're fucking lazy
        // and don't check if the table actually exists first. ;-;
        userDb = new SQLiteDatabase(this, userDbName, "CREATE TABLE IF NOT EXISTS " + userDbName
                + " (uuid TEXT PRIMARY KEY NOT NULL UNIQUE, lastName TEXT NOT NULL," +
                "kills INT NOT NULL, deaths INT NOT NULL, ip TEXT NOT NULL)");
        if(userDb.connect()) {
            if(userDb.initialize()) {
                getLogger().info("Successfully attached to SQLite DB!");
            } else {
                Bukkit.getPluginManager().disablePlugin(this);
                getLogger().severe("Couldn't initialize SQLite DB!");
                return;
            }
        } else {
            Bukkit.getPluginManager().disablePlugin(this);
            getLogger().severe("Couldn't connect to SQLite DB!");
            return;
        }
        try {
            //noinspection SqlDialectInspection
            final PreparedStatement s = userDb.getConnection().prepareStatement("CREATE TABLE IF NOT EXISTS "
                    + attributeDbName + " (uuid TEXT NOT NULL, attr_name TEXT NOT NULL, " + "attr_value TEXT NOT NULL, " +
                    "FOREIGN KEY(uuid) REFERENCES " + userDbName + "(uuid))");
            userDb.execute(s);
        } catch(final SQLException e) {
            e.printStackTrace();
            getLogger().severe("Couldn't make attr. table, disabling...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        skirtsUserMap = new SkirtsUserMap(this);

        // Grab all users
        try {
            final PreparedStatement s = userDb.getConnection().prepareStatement(String.format("SELECT * FROM %s", userDbName));
            s.execute();
            final ResultSet rs = s.getResultSet();
            getLogger().info("ResultSet worked?: " + rs.isBeforeFirst());
            while(rs.next()) {
                final String uuid = rs.getString("uuid");
                final String lastName = rs.getString("lastName");
                final int kills = rs.getInt("kills");
                final int deaths = rs.getInt("deaths");
                final String ip = rs.getString("ip");
                if(uuid != null && lastName != null && kills != -1 && deaths != -1 && ip != null) {
                    skirtsUserMap.addUser(new SkirtsUser(UUID.fromString(uuid), lastName, kills, deaths, InetAddress.getByName(ip.replaceAll("/", ""))));
                }
            }
            rs.close();
        } catch(SQLException | UnknownHostException e) {
            getServer().getPluginManager().disablePlugin(this);
            throw new IllegalStateException(e);
        }

        registerEvents();
        // Register custom commands
        getCommandManager().registerCommand(SkirtsCommand.builder().setName("killdeath")
                .setDescription("Shows you your K/D ratio")
                .addAlias("kd").addAlias("killdeathratio").addAlias("kdr")
                .setPermissionNode("skirtsusers.kdr")
                .setPlugin(this)
                .setExecutor(new CommandKD()).build());
        getCommandManager().registerCommand(SkirtsCommand.builder().setName("playtime")
                .setDescription("Shows you your playtime")
                .setPermissionNode("skirtsusers.playtime")
                .setUsage("/playtime [user]")
                .setPlugin(this)
                .setExecutor(new CommandPlaytime(this))
                .build());
        // Map users already online. This is for when a /reload happens, or the plugin is
        // loaded in while the server is running
        getServer().getScheduler().scheduleSyncDelayedTask(this, new PlayerMapTask(this), 600L);
    }

    @Override
    public void onDisable() {
        userDb.disconnect();
    }

    /**
     * Return the user with the given UUID
     *
     * @param uuid UUID string
     *
     * @return SkirtsUser with the given uuid
     */
    @SuppressWarnings("unused")
    public Optional<SkirtsUser> getUserForUUID(final String uuid) {
        return getUserForUUID(UUID.fromString(uuid));
    }

    /**
     * Return the user with the given UUID
     *
     * @param uniqueId UUID
     *
     * @return SkirtsUser with the given UUID
     */
    public Optional<SkirtsUser> getUserForUUID(final UUID uniqueId) {
        final Optional<SkirtsUser> skirtsUserOptional = skirtsUserMap.getUser(uniqueId);
        if(!skirtsUserOptional.isPresent()) {
            try {
                final PreparedStatement s = userDb.getConnection().prepareStatement(String.format("SELECT * FROM %s WHERE uuid = ?", userDbName));
                s.setString(1, uniqueId.toString());
                final ResultSet rs = s.executeQuery();
                String uuid = null;
                String lastName = null;
                int kills = -1;
                int deaths = -1;
                String ip = null;
                while(rs.next()) {
                    uuid = rs.getString("uuid");
                    lastName = rs.getString("lastName");
                    kills = rs.getInt("kills");
                    deaths = rs.getInt("deaths");
                    ip = rs.getString("ip");
                }
                if(uuid == null || lastName == null || kills == -1 || deaths == -1 || ip == null) {
                    // Assume not seen before
                    return Optional.<SkirtsUser>empty();
                } else {
                    final SkirtsUser skirtsUser = new SkirtsUser(UUID.fromString(uuid), lastName, kills, deaths,
                            InetAddress.getByName(ip.replaceAll("/", "")));
                    // TODO: ATTR
                    final PreparedStatement s2 = userDb.getConnection()
                            .prepareStatement(String.format("SELECT * FROM %s WHERE uuid = ?", attributeDbName));
                    final ResultSet rs2 = s2.executeQuery();
                    while(rs2.next()) {
                        final String uuid2 = rs2.getString("uuid");
                        final String attrName = rs2.getString("attr_name");
                        final String attrType = rs2.getString("attr_type");
                        final String attrValue = rs2.getString("attr_value");
                        skirtsUser.addAttribute(attrName, Attribute.fromString(attrType, attrValue));
                    }
                    skirtsUserMap.addUser(skirtsUser);
                    return Optional.of(skirtsUser);
                }
            } catch(SQLException | UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        } else {
            return skirtsUserOptional;
        }
    }

    private void registerEvents() {
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
    }
}
