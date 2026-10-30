package io.github.dukkkkkkkk.pitch40;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import io.github.dukkkkkkkk.pitch40.command.Pitch40Command;
import io.github.dukkkkkkkk.pitch40.module.Pitch40Module;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Pitch40 elytra travel for the ZenithProxy bot",
    url = "https://github.com/dukkkkkkkk/Auto-Pitch40",
    authors = {"dukkkkkkkk"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class Pitch40Plugin implements ZenithProxyPlugin {
    public static Pitch40Config CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        // config must be registered before the module reads it in its constructor
        CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, Pitch40Config.class);
        pluginAPI.registerModule(new Pitch40Module());
        pluginAPI.registerCommand(new Pitch40Command());
    }
}
