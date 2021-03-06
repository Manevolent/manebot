package io.manebot.command.builtin;

import io.manebot.Bot;
import io.manebot.Version;
import io.manebot.artifact.*;
import io.manebot.chat.TextStyle;
import io.manebot.command.CommandSender;
import io.manebot.command.exception.CommandArgumentException;
import io.manebot.command.exception.CommandExecutionException;
import io.manebot.command.executor.chained.AnnotatedCommandExecutor;
import io.manebot.command.executor.chained.argument.CommandArgumentLabel;
import io.manebot.command.executor.chained.argument.CommandArgumentPage;
import io.manebot.command.executor.chained.argument.CommandArgumentString;
import io.manebot.command.search.CommandArgumentSearch;
import io.manebot.database.Database;
import io.manebot.database.search.Search;
import io.manebot.database.search.SearchHandler;
import io.manebot.database.search.handler.*;
import io.manebot.platform.Platform;
import io.manebot.plugin.*;
import io.manebot.tuple.Pair;
import io.manebot.virtual.Virtual;

import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class PluginCommand extends AnnotatedCommandExecutor {
    private final Bot bot;
    private final PluginManager pluginManager;
    private final ArtifactIdentifier coreIdentifier;

    private final SearchHandler<io.manebot.database.model.Plugin> searchHandler;

    public PluginCommand(Bot bot, PluginManager pluginManager, Database database) {
        this.bot = bot;
        this.pluginManager = pluginManager;
        this.searchHandler = database
                .createSearchHandler(io.manebot.database.model.Plugin.class)
                .string(new SearchHandlerPropertyContains("artifactId"))
                .command("enabled", new SearchHandlerPropertyEquals("enabled", Boolean::parseBoolean))
                .command("disabled", new SearchHandlerPropertyEquals("enabled", Boolean::parseBoolean).not())
                .sort("name", "artifactId")
                .build();

        this.coreIdentifier = new ArtifactIdentifier(
                "io.manebot", "manebot-core",
                bot.getApiVersion().toString()
        );
    }

    @Command(description = "Searches plugins", permission = "system.plugin.search")
    public void search(CommandSender sender,
                       @CommandArgumentLabel.Argument(label = "search") String search,
                       @CommandArgumentSearch.Argument Search query)
            throws CommandExecutionException {
        try {
            sender.sendList(
                    io.manebot.database.model.Plugin.class,
                    searchHandler.search(query, 6),
                    (textBuilder, plugin) ->
                            textBuilder.append(
                                    plugin.getArtifactIdentifier().getArtifactId(),
                                    EnumSet.of(TextStyle.BOLD)
                            ).append(" (" + plugin.getArtifactIdentifier() + ")")
            );
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Command(description = "Lists plugins", permission = "system.plugin.list")
    public void list(CommandSender sender,
                     @CommandArgumentLabel.Argument(label = "list") String list,
                     @CommandArgumentPage.Argument() int page) throws CommandExecutionException {
        sender.sendList(
                Plugin.class,
                builder -> builder.direct(pluginManager.getLoadedPlugins()
                        .stream()
                        .sorted(Comparator.comparing(Plugin::getName))
                        .collect(Collectors.toList()))
                .page(page)
                .responder((textBuilder, plugin) ->
                        textBuilder.append(plugin.getName(), EnumSet.of(TextStyle.BOLD))
                                .append(" (" + plugin.getArtifact().getIdentifier() + ")"))
                .build()
        );
    }

    private void installDependencies(CommandSender sender, Collection<ArtifactDependency> dependencies)
            throws ArtifactRepositoryException, PluginLoadException, CommandExecutionException {
        for (ArtifactDependency dependency : dependencies) {
            if (pluginManager.getPlugin(dependency.getChild().getIdentifier().withoutVersion()) != null)
                continue;

            try {
                installDependencies(sender, pluginManager.getDependencies(dependency.getChild().getIdentifier()));
            } catch (Throwable e) {
                PluginLoadException exception =
                        new PluginLoadException(
                                "Failed to load dependency " +
                                dependency.getChild().getIdentifier() + " for " +
                                dependency.getParent().getIdentifier(),
                                e
                        );

                if (dependency.isRequired())
                    throw exception;
                else
                    Logger.getGlobal().log(
                            Level.WARNING,
                            "Problem loading optional plugin dependency: " + exception.getMessage()
                    );
            }

            sender.sendMessage("Installing " + dependency.getChild().getIdentifier().toString() + "...");
            sender.flush();

            PluginRegistration registration;

            try {
                registration = pluginManager.install(dependency.getChild().getIdentifier());
            } catch (PluginLoadException e) {
                Virtual.getInstance().currentProcess().getLogger().log(
                        Level.SEVERE,
                        "Failed to install " + dependency.getChild().getIdentifier().toString(),
                        e
                );

                throw new CommandExecutionException(
                        "Failed to install " + dependency.getChild().getIdentifier().toString() + ": " + e.getMessage()
                );
            }

            registration.setAutoStart(true);

            sender.sendMessage("Installed " + registration.getIdentifier().toString() + ".");
        }
    }

    private void update(CommandSender sender, Collection<PluginRegistration> registrations)
            throws CommandExecutionException {
        sender.sendMessage("Updating plugin(s)...");
        sender.flush();

        Collection<ArtifactIdentifier> updates = new Updater(registrations).check();

        if (updates.size() <= 0) {
            throw new CommandArgumentException("No updates found.");
        }

        sender.getUser().prompt(builder -> builder
                .setName("Plugin updates")
                .setDescription("[" +
                        updates.stream()
                                .map(ArtifactIdentifier::withoutVersion)
                                .map(ManifestIdentifier::toString)
                                .collect(Collectors.joining(", ")) +
                        "] have available updates; confirm to mark the plugin(s) for update on the next restart.")
                .setCallback((prompt) -> {
                    updates.forEach(identifier ->
                            pluginManager.getPlugin(identifier.withoutVersion()).setVersion(identifier.getVersion())
                    );
                })
        );
    }

    @Command(description = "Updates plugins", permission = "system.plugin.update")
    public void update(CommandSender sender,
                       @CommandArgumentLabel.Argument(label = "update") String update)
            throws CommandExecutionException {
        update(sender, pluginManager.getPlugins());
    }

    @Command(description = "Updates a plugin", permission = "system.plugin.update")
    public void update(CommandSender sender,
                        @CommandArgumentLabel.Argument(label = "update") String update,
                        @CommandArgumentString.Argument(label = "artifact") String artifact)
            throws CommandExecutionException, ArtifactRepositoryException, PluginLoadException {
        ArtifactIdentifier artifactIdentifier = pluginManager.resolveIdentifier(artifact);
        if (artifactIdentifier == null)
            throw new CommandArgumentException("Plugin not found, or no versions are available.");

        PluginRegistration registration = pluginManager.getPlugin(artifactIdentifier.withoutVersion());
        if (registration == null)
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not installed.");

        update(sender, Collections.singleton(registration));
    }

    @Command(description = "Installs a plugin", permission = "system.plugin.install")
    public void install(CommandSender sender,
                        @CommandArgumentLabel.Argument(label = "install") String install,
                        @CommandArgumentString.Argument(label = "artifact") String artifact)
            throws CommandExecutionException, ArtifactRepositoryException, PluginLoadException {
        ArtifactIdentifier artifactIdentifier = pluginManager.resolveIdentifier(artifact);
        if (artifactIdentifier == null)
            throw new CommandArgumentException("Plugin not found, or no versions are available.");

        sender.sendMessage("Resolving dependencies for " + artifactIdentifier.toString() + "...");
        sender.flush();

        // Install dependencies if required
        Collection<ArtifactDependency> dependencies;
        try {
            dependencies = pluginManager.getDependencies(artifactIdentifier);
        } catch (ArtifactRepositoryException e) {
            throw new CommandExecutionException("Failed to find dependencies for " + artifactIdentifier, e);
        }

        installDependencies(sender, dependencies);

        PluginRegistration registration = pluginManager.getPlugin(artifactIdentifier.withoutVersion());
        if (registration != null)
            throw new CommandArgumentException(
                    artifactIdentifier.withoutVersion().toString()
                    + " is already installed with version " + registration.getIdentifier().getVersion() + "."
            );

        sender.sendMessage("Installing " + artifactIdentifier.toString() + "...");
        sender.flush();

        try {
            registration = pluginManager.install(artifactIdentifier);
        } catch (PluginLoadException e) {
            Virtual.getInstance().currentProcess().getLogger().log(
                    Level.SEVERE,
                    "Failed to install " + artifactIdentifier.toString(),
                    e
            );

            throw new CommandExecutionException(
                    "Failed to install " + artifactIdentifier.toString() + ": " + e.getMessage()
            );
        }

        if (registration.isLoaded() && !registration.getInstance().isEnabled())
            sender.sendMessage("Installed and loaded plugin " + registration.getIdentifier().toString() + "; " +
                                "enable it to begin using it.");
        else
            sender.sendMessage("Installed " + registration.getIdentifier().toString() + ".");
    }

    @Command(description = "Automatically removes unneeded plugins", permission = "system.plugin.autoremove")
    public void autoremove(CommandSender sender,
                          @CommandArgumentLabel.Argument(label = "autoremove") String autoremove)
            throws CommandExecutionException {
        Collection<ManifestIdentifier> uninstalledPlugins = new LinkedList<>();

        while (true) {
            Collection<PluginRegistration> unneededPlugins = new LinkedList<>();

            for (Plugin plugin : pluginManager.getLoadedPlugins()) {
                ArtifactIdentifier artifactIdentifier = plugin.getArtifact().getIdentifier();
                ManifestIdentifier manifestIdentifier = artifactIdentifier.withoutVersion();

                if (uninstalledPlugins.contains(manifestIdentifier))
                    continue;

                // 1. Plugin is itself marked as a dependency
                // 2. Plugin does not have any dependent plugins which explicitly express requirement on this plugin
                // 3. Plugin does not have any dependers who explicitly require this plugin in their classpath
                Collection<ArtifactDependency> blockingArtifactDependencies =
                        plugin.getArtifactDependers().stream()
                                .filter(ArtifactDependency::isRequired)
                                .collect(Collectors.toList());

                Collection<Plugin> blockingPlugins =
                        plugin.getDependers().stream()
                                .filter(depender -> depender.getRequiredDependencies().contains(plugin))
                                .collect(Collectors.toList());

                // Cache installation checks (reduces JPA overhead)
                Map<ArtifactIdentifier, Boolean> installationMap = new LinkedHashMap<>();
                Function<ArtifactIdentifier, Boolean> installationQuery = pluginManager::isInstalled;

                // Challenge blocking depending artifact/classpath associations
                Iterator<ArtifactDependency> blockingArtifactIterator = blockingArtifactDependencies.iterator();
                while (blockingArtifactIterator.hasNext()) {
                    boolean installed = installationMap.computeIfAbsent(
                            blockingArtifactIterator.next().getParent().getIdentifier(),
                            installationQuery
                    );

                    if (!installed) blockingArtifactIterator.remove();
                }

                // Challenge blocking API require() associations
                Iterator<Plugin> blockingPluginsIterator = blockingPlugins.iterator();
                while (blockingPluginsIterator.hasNext()) {
                    boolean installed = installationMap.computeIfAbsent(
                            blockingPluginsIterator.next().getArtifact().getIdentifier(),
                            installationQuery
                    );

                    if (!installed) blockingPluginsIterator.remove();
                }

                // Final removal check
                boolean canRemove =
                        !plugin.getRegistration().isRequired() &&
                        plugin.getType() == PluginType.DEPENDENCY &&
                                blockingArtifactDependencies.size() <= 0 &&
                                blockingPlugins.size() <= 0;

                if (canRemove) {
                    PluginRegistration registration = pluginManager.getPlugin(manifestIdentifier);
                    if (registration != null) unneededPlugins.add(registration);
                }
            }

            for (PluginRegistration unneededPlugin : unneededPlugins) {
                sender.sendMessage("Uninstalling " + unneededPlugin.getIdentifier() + "...");
                sender.flush();

                if (unneededPlugin.isLoaded()) {
                    try {
                        unneededPlugin.getInstance().setEnabled(false);
                    } catch (PluginException e) {
                        throw new CommandExecutionException(
                                "Failed to disable " +
                                        unneededPlugin.toString() + ": " +
                                        e.getMessage()
                        );
                    }
                }

                if (pluginManager.uninstall(unneededPlugin)) uninstalledPlugins.add(unneededPlugin.getIdentifier());
            }

            if (unneededPlugins.size() <= 0) break;
        }

        if (uninstalledPlugins.size() <= 0)
            throw new CommandArgumentException("No plugins were auto-removed.");
        else
            sender.sendMessage("Un-installed " + uninstalledPlugins.size() + " plugin(s): [" +
                    String.join(",", uninstalledPlugins
                            .stream()
                            .map(ManifestIdentifier::toString)
                            .collect(Collectors.toList())
                    ) + "]");
    }

    @Command(description = "Uninstalls a plugin", permission = "system.plugin.uninstall")
    public void uninstall(CommandSender sender,
                        @CommandArgumentLabel.Argument(label = "uninstall") String uninstall,
                        @CommandArgumentString.Argument(label = "artifact") String artifact)
            throws CommandExecutionException {
        ArtifactIdentifier artifactIdentifier = pluginManager.resolveIdentifier(artifact);
        if (artifactIdentifier == null)
            throw new CommandArgumentException("Plugin not found, or no versions are available.");

        PluginRegistration registration = pluginManager.getPlugin(artifactIdentifier.withoutVersion());
        if (registration == null || !registration.isInstalled())
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not installed.");

        if (registration.isRequired())
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is required.");

        artifactIdentifier = registration.getIdentifier();

        sender.sendMessage("Un-installing " + artifactIdentifier.toString() + "...");
        sender.flush();

        Plugin instance = registration.getInstance();
        if (instance != null) {
            try {
                instance.setEnabled(false);
            } catch (PluginException e) {
                throw new CommandExecutionException(
                        "Failed to disable " +
                        artifactIdentifier.toString() + ": " +
                                e.getMessage()
                );
            }
        }

        registration.setAutoStart(false);

        try {
            pluginManager.uninstall(registration);
        } catch (UnsupportedOperationException ex) {
            throw new CommandArgumentException(artifactIdentifier.toString() + " does not support un-installation.");
        }

        sender.sendMessage("Un-installed " + registration.getIdentifier().toString() + ".");
    }

    @Command(description = "Sets a plugin's property", permission = "system.plugin.property.change")
    public void setProperty(CommandSender sender,
                            @CommandArgumentLabel.Argument(label = "property") String property,
                            @CommandArgumentLabel.Argument(label = "set") String set,
                            @CommandArgumentString.Argument(label = "artifact") String artifact,
                            @CommandArgumentString.Argument(label = "name") String name,
                            @CommandArgumentString.Argument(label = "value") String value)
            throws CommandExecutionException {
        ArtifactIdentifier artifactIdentifier = pluginManager.resolveIdentifier(artifact);
        if (artifactIdentifier == null)
            throw new CommandArgumentException("Plugin not found, or no versions are available.");

        PluginRegistration registration = pluginManager.getPlugin(artifactIdentifier.withoutVersion());
        if (registration == null)
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not installed.");

        registration.setProperty(name, value);

        sender.sendMessage("Property changed for " +
                registration.getIdentifier().toString() + ": " + name + " -> \"" + value + "\".");
    }

    @Command(description = "Unsets a plugin's property", permission = "system.plugin.property.unset")
    public void unsetProperty(CommandSender sender,
                            @CommandArgumentLabel.Argument(label = "property") String property,
                            @CommandArgumentLabel.Argument(label = "unset") String set,
                            @CommandArgumentString.Argument(label = "artifact") String artifact,
                            @CommandArgumentString.Argument(label = "name") String name)
            throws CommandExecutionException {
        ArtifactIdentifier artifactIdentifier = pluginManager.resolveIdentifier(artifact);
        if (artifactIdentifier == null)
            throw new CommandArgumentException("Plugin not found, or no versions are available.");

        PluginRegistration registration = pluginManager.getPlugin(artifactIdentifier.withoutVersion());
        if (registration == null)
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not installed.");

        if (registration.getProperty(name) == null)
            throw new CommandArgumentException("Plugin property is not set.");

        registration.setProperty(name, null);
        sender.sendMessage("Property unset for " + registration.getIdentifier().toString() + ": " + name);
    }

    @Command(description = "Gets a plugin's property", permission = "system.plugin.property.get")
    public void getProperty(CommandSender sender,
                            @CommandArgumentLabel.Argument(label = "property") String property,
                            @CommandArgumentLabel.Argument(label = "get") String get,
                            @CommandArgumentString.Argument(label = "artifact") String artifact,
                            @CommandArgumentString.Argument(label = "name") String name)
            throws CommandExecutionException {
        ArtifactIdentifier artifactIdentifier = pluginManager.resolveIdentifier(artifact);
        if (artifactIdentifier == null)
            throw new CommandArgumentException("Plugin not found, or no versions are available.");

        PluginRegistration registration = pluginManager.getPlugin(artifactIdentifier.withoutVersion());
        if (registration == null)
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not installed.");

        String value = registration.getProperty(name);
        if (value == null)
            sender.sendMessage(name + " -> (null).");
        else
            sender.sendMessage(name + " -> \"" + value + "\".");
    }

    @Command(description = "Gets a plugin's property", permission = "system.plugin.property.get")
    public void listProperty(CommandSender sender,
                            @CommandArgumentLabel.Argument(label = "property") String property,
                            @CommandArgumentLabel.Argument(label = "list") String list,
                            @CommandArgumentString.Argument(label = "artifact") String artifact,
                            @CommandArgumentPage.Argument() int page)
            throws CommandExecutionException {
        ArtifactIdentifier artifactIdentifier = pluginManager.resolveIdentifier(artifact);
        if (artifactIdentifier == null)
            throw new CommandArgumentException("Plugin not found, or no versions are available.");

        PluginRegistration registration = pluginManager.getPlugin(artifactIdentifier.withoutVersion());
        if (registration == null)
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not installed.");

        sender.sendList(
                PluginProperty.class,
                builder -> builder.direct(new ArrayList<>(registration.getProperties())).page(page)
                .responder((chatSender, pluginProperty) -> pluginProperty.getName()).build()
        );
    }

    @Command(description = "Gets current plugin information", permission = "system.plugin.info")
    public void info(CommandSender sender) throws CommandExecutionException {
        info(sender, sender.getChat().getPlatform().getPlugin().getRegistration());
    }

    @Command(description = "Gets plugin information", permission = "system.plugin.info")
    public void info(CommandSender sender,
                       @CommandArgumentLabel.Argument(label = "info") String info,
                       @CommandArgumentString.Argument(label = "identifier") String identifier)
            throws CommandExecutionException {
        ArtifactIdentifier artifactIdentifier = pluginManager.resolveIdentifier(identifier);
        if (artifactIdentifier == null)
            throw new CommandArgumentException("Plugin not found, or no versions are available.");

        PluginRegistration registration = pluginManager.getPlugin(artifactIdentifier.withoutVersion());
        if (registration == null)
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not installed.");

        info(sender, registration);
    }

    private void info(CommandSender sender, PluginRegistration registration) throws CommandExecutionException {
        sender.sendDetails(builder -> {
            builder.name("Plugin").key(registration.getIdentifier().withoutVersion().toString())
                    .item("Version", registration.getIdentifier().getVersion());

            builder.item("Auto start", registration.willAutoStart() ? "true" : "false");
            builder.item("Required", registration.isRequired() ? "true" : "false");

            if (registration.isLoaded()) {
                builder.item("Loaded", "true");

                builder.item("Dependencies", registration.getInstance().getDependencies()
                        .stream().map(Plugin::getName).collect(Collectors.toList()));

                builder.item("Dependers", registration.getInstance().getDependers()
                        .stream().map(Plugin::getName).collect(Collectors.toList()));

                builder.item("Databases", registration.getInstance().getDatabases()
                        .stream().map(Database::getName).collect(Collectors.toList()));

                if (registration.getInstance().isEnabled()) {
                    builder.item("Enabled", "true");

                    builder.item("Platforms", registration.getInstance().getPlatforms()
                            .stream().map(Platform::getId).collect(Collectors.toList()));

                    builder.item("Commands", registration.getInstance().getCommands());
                } else {
                    builder.item("Enabled", "false");
                }
            } else
                builder.item("Loaded", "false");

        });
    }

    @Command(description = "Enables a plugin", permission = "system.plugin.enable")
    public void enable(CommandSender sender,
                       @CommandArgumentLabel.Argument(label = "enable") String enable,
                       @CommandArgumentString.Argument(label = "identifier") String identifier)
            throws CommandExecutionException {
        ArtifactIdentifier artifactIdentifier = pluginManager.resolveIdentifier(identifier);
        if (artifactIdentifier == null)
            throw new CommandArgumentException("Plugin not found, or no versions are available.");

        PluginRegistration registration = pluginManager.getPlugin(artifactIdentifier.withoutVersion());
        if (registration == null)
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not installed.");

        if (!registration.isLoaded()) {
            sender.sendMessage("Loading " + artifactIdentifier.toString() + "...");
            sender.flush();

            try {
                registration.load();
            } catch (PluginLoadException e) {
                Virtual.getInstance().currentProcess().getLogger().log(
                        Level.SEVERE,
                        "Failed to load " + artifactIdentifier.toString(),
                        e
                );

                throw new CommandExecutionException(e.getMessage());
            }
        }

        if (registration.getInstance().isEnabled() && registration.willAutoStart())
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is already enabled.");

        sender.sendMessage("Enabling " + artifactIdentifier.toString() + "...");
        sender.flush();

        try {
            registration.getInstance().setEnabled(true);
        } catch (PluginException e) {
            Virtual.getInstance().currentProcess().getLogger().log(
                    Level.SEVERE,
                    "Failed to enable " + artifactIdentifier.toString(),
                    e
            );

            throw new CommandExecutionException(e.getMessage());
        }

        // Enable statically
        registration.setAutoStart(true);

        sender.sendMessage("Enabled " + registration.getIdentifier().toString() + ".");
    }

    @Command(description = "Disables a plugin", permission = "system.plugin.disable")
    public void disable(CommandSender sender,
                       @CommandArgumentLabel.Argument(label = "disable") String disable,
                       @CommandArgumentString.Argument(label = "identifier") String identifier)
            throws CommandExecutionException {
        ArtifactIdentifier artifactIdentifier = pluginManager.resolveIdentifier(identifier);
        if (artifactIdentifier == null)
            throw new CommandArgumentException("Plugin not found, or no versions are available.");

        PluginRegistration registration = pluginManager.getPlugin(artifactIdentifier.withoutVersion());
        if (registration == null)
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not installed.");

        if (!registration.isLoaded())
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not loaded.");

        if (!registration.getInstance().isEnabled() && !registration.willAutoStart())
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not enabled.");

        sender.sendMessage("Disabling " + registration.getIdentifier() + "...");
        sender.flush();

        try {
            registration.getInstance().setEnabled(false);
        } catch (PluginException e) {
            Virtual.getInstance().currentProcess().getLogger().log(
                    Level.SEVERE,
                    "Failed to disable " + artifactIdentifier.toString(),
                    e
            );

            throw new CommandExecutionException(e.getMessage());
        }

        // Disable statically
        registration.setAutoStart(false);

        sender.sendMessage("Disabled " + registration.getIdentifier().toString() + ".");
    }

    @Command(description = "Requires a plugin", permission = "system.plugin.require")
    public void require(CommandSender sender,
                          @CommandArgumentLabel.Argument(label = "require") String require,
                          @CommandArgumentString.Argument(label = "identifier") String identifier)
            throws CommandExecutionException {
        ArtifactIdentifier artifactIdentifier = pluginManager.resolveIdentifier(identifier);
        if (artifactIdentifier == null)
            throw new CommandArgumentException("Plugin not found, or no versions are available.");

        PluginRegistration registration = pluginManager.getPlugin(artifactIdentifier.withoutVersion());
        if (registration == null)
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not installed.");

        if (!registration.isLoaded())
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not loaded.");

        if (registration.isRequired())
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is already required.");

        registration.setRequired(true);

        sender.sendMessage(registration.getIdentifier().toString() + " is now required at startup.");
    }

    @Command(description = "Unrequires a plugin", permission = "system.plugin.require")
    public void unrequire(CommandSender sender,
                        @CommandArgumentLabel.Argument(label = "unrequire") String unrequire,
                        @CommandArgumentString.Argument(label = "identifier") String identifier)
            throws CommandExecutionException {
        ArtifactIdentifier artifactIdentifier = pluginManager.resolveIdentifier(identifier);
        if (artifactIdentifier == null)
            throw new CommandArgumentException("Plugin not found, or no versions are available.");

        PluginRegistration registration = pluginManager.getPlugin(artifactIdentifier.withoutVersion());
        if (registration == null)
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not installed.");

        if (!registration.isLoaded())
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not loaded.");

        if (!registration.isRequired())
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not required.");

        registration.setRequired(false);

        sender.sendMessage(registration.getIdentifier().toString() + " is no longer required at startup.");
    }

    @Command(description = "Elevates a plugin", permission = "system.plugin.elevate")
    public void elevate(CommandSender sender,
                        @CommandArgumentLabel.Argument(label = "elevate") String elevate,
                        @CommandArgumentString.Argument(label = "identifier") String identifier)
            throws CommandExecutionException {
        ArtifactIdentifier artifactIdentifier = pluginManager.resolveIdentifier(identifier);
        if (artifactIdentifier == null)
            throw new CommandArgumentException("Plugin not found, or no versions are available.");

        PluginRegistration registration = pluginManager.getPlugin(artifactIdentifier.withoutVersion());
        if (registration == null)
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not installed.");

        if (!registration.isLoaded())
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not loaded.");

        if (registration.isElevated())
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is already elevated.");

        registration.setElevated(true);

        sender.sendMessage(registration.getIdentifier().toString() + " is now elevated.");
    }

    @Command(description = "Unelevates a plugin", permission = "system.plugin.elevate")
    public void unelevate(CommandSender sender,
                          @CommandArgumentLabel.Argument(label = "unelevate") String unelevate,
                          @CommandArgumentString.Argument(label = "identifier") String identifier)
            throws CommandExecutionException {
        ArtifactIdentifier artifactIdentifier = pluginManager.resolveIdentifier(identifier);
        if (artifactIdentifier == null)
            throw new CommandArgumentException("Plugin not found, or no versions are available.");

        PluginRegistration registration = pluginManager.getPlugin(artifactIdentifier.withoutVersion());
        if (registration == null)
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not installed.");

        if (!registration.isLoaded())
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not loaded.");

        if (!registration.isElevated())
            throw new CommandArgumentException(artifactIdentifier.withoutVersion().toString() + " is not required.");

        registration.setElevated(false);

        sender.sendMessage(registration.getIdentifier().toString() + " is no longer elevated.");
    }

    @Override
    public String getDescription() {
        return "Manages plugins";
    }

    private class Updater {
        /**
         * Collection of plugins to check for updates against
         */
        private final Collection<PluginRegistration> plugins;

        private Updater(Collection<PluginRegistration> plugins) {
            this.plugins = plugins;
        }

        public Version latest(Artifact artifact, Map<ManifestIdentifier, Version> latestCapableVersions) {
            ManifestIdentifier checkedManifestIdentifier = artifact.getIdentifier().withoutVersion();

            PluginRegistration registration = pluginManager.getPlugin(checkedManifestIdentifier);
            if (registration == null) {
                return null;
            }

            Version latestVersion = latestCapableVersions.get(checkedManifestIdentifier);

            if (latestVersion == null)
                latestCapableVersions.put(
                        checkedManifestIdentifier,
                        latestVersion = artifact.getManifest().getVersions().stream()
                                .map(Version::fromString)
                                .filter(version -> version.compareTo(Version.fromString(artifact.getVersion())) > 0)
                                .sorted(Comparator.comparing((Version version) -> version).reversed())
                                .map(version -> {
                                    try {
                                        return artifact.getManifest().getArtifact(version.toString());
                                    } catch (ArtifactNotFoundException e) {
                                        return null;
                                    }
                                })
                                .filter(Objects::nonNull)
                                .filter(candidateArtifact -> {
                                    try {
                                        return candidateArtifact.getDependencies().stream()
                                                .filter(dependency ->
                                                        dependency.getType() == ArtifactDependencyLevel.PROVIDED)
                                                .allMatch(dependency -> {
                                                    if (dependency.getChild().getIdentifier()
                                                            .withoutVersion().equals(coreIdentifier.withoutVersion())) {
                                                        return Version.fromString(dependency.getChild().getVersion())
                                                                .compareTo(Version.fromString(coreIdentifier.getVersion()))
                                                                <= 0;
                                                    } else {
                                                        Version latest =
                                                                latest(dependency.getChild(), latestCapableVersions);

                                                        return latest != null && latest.compareTo(
                                                                Version.fromString(dependency.getChild().getVersion())
                                                        ) >= 0;
                                                    }
                                                });
                                    } catch (ArtifactNotFoundException e) {
                                        return false;
                                    }
                                })
                                .map(candidateArtifact -> Version.fromString(candidateArtifact.getVersion()))
                                .findFirst()
                                .orElse(Version.fromString(registration.getIdentifier().getVersion()))
                );

            return latestVersion;
        }

        /**
         * Checks for updates to the previously specified plugins.
         * @return collection of artifact identifiers that should be updated to.
         */
        public Collection<ArtifactIdentifier> check() {
            final Map<ManifestIdentifier, Version> latestCapableVersions = new LinkedHashMap<>();

            plugins.stream().map(registration -> {
                if (registration.isLoaded())
                    return registration.getInstance().getArtifact();
                else {
                    try {
                        return pluginManager.getRepostiory().getArtifact(registration.getIdentifier());
                    } catch (ArtifactRepositoryException e) {
                        throw new RuntimeException(e);
                    }
                }
            }).forEach(artifact -> latest(artifact, latestCapableVersions));

            return latestCapableVersions.entrySet().stream()
                    .filter(entry -> entry.getValue().compareTo(
                            Version.fromString(pluginManager.getPlugin(entry.getKey()).getIdentifier().getVersion())
                    ) > 0)
                    .map(entry -> new ArtifactIdentifier(
                            entry.getKey().getPackageId(),
                            entry.getKey().getArtifactId(),
                            entry.getValue().toString())
                    ).collect(Collectors.toList());
        }
    }
}
