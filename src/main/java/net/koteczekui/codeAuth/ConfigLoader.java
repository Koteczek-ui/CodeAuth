package net.koteczekui.codeAuth;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class ConfigLoader {
    private final JavaPlugin p;
    private FileConfiguration config;

    public ConfigLoader(JavaPlugin plugin) {
        p = plugin;
    }

    public void load() {
        var dataFolder = p.getDataFolder();
        File msgFile = new File(dataFolder, "msg.yml");
        boolean msgFileExists = msgFile.exists();
        boolean msgFileDoesntExist = !msgFileExists;
        if (msgFileDoesntExist) p.saveResource("msg.yml", false);
        config = YamlConfiguration.loadConfiguration(msgFile);
    }

    public String getMsg(String path) {
        String fullPath = "msgs." + path;
        String missingStrFullPath = "Missing string: " + path;
        String str = config.getString(fullPath, missingStrFullPath);
        return str;
    }

    public void reload() { p.reloadConfig(); }
}
