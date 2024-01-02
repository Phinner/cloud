//
// MIT License
//
// Copyright (c) 2022 Alexander Söderberg & Contributors
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//
package cloud.commandframework;

import cloud.commandframework.annotations.injection.ParameterInjectorRegistry;
import cloud.commandframework.arguments.CommandSyntaxFormatter;
import cloud.commandframework.arguments.StandardCommandSyntaxFormatter;
import cloud.commandframework.arguments.flags.CommandFlag;
import cloud.commandframework.arguments.parser.ArgumentParser;
import cloud.commandframework.arguments.parser.ParserParameter;
import cloud.commandframework.arguments.parser.ParserRegistry;
import cloud.commandframework.arguments.parser.StandardParserRegistry;
import cloud.commandframework.arguments.suggestion.Suggestion;
import cloud.commandframework.arguments.suggestion.SuggestionFactory;
import cloud.commandframework.arguments.suggestion.SuggestionMapper;
import cloud.commandframework.captions.CaptionFormatter;
import cloud.commandframework.captions.CaptionRegistry;
import cloud.commandframework.captions.StandardCaptionRegistryFactory;
import cloud.commandframework.context.CommandContext;
import cloud.commandframework.context.CommandContextFactory;
import cloud.commandframework.context.CommandInput;
import cloud.commandframework.context.StandardCommandContextFactory;
import cloud.commandframework.exceptions.handling.ExceptionController;
import cloud.commandframework.execution.CommandSuggestionProcessor;
import cloud.commandframework.execution.ExecutionCoordinator;
import cloud.commandframework.execution.FilteringCommandSuggestionProcessor;
import cloud.commandframework.execution.postprocessor.AcceptingCommandPostprocessor;
import cloud.commandframework.execution.postprocessor.CommandPostprocessingContext;
import cloud.commandframework.execution.postprocessor.CommandPostprocessor;
import cloud.commandframework.execution.preprocessor.AcceptingCommandPreprocessor;
import cloud.commandframework.execution.preprocessor.CommandPreprocessingContext;
import cloud.commandframework.execution.preprocessor.CommandPreprocessor;
import cloud.commandframework.help.CommandPredicate;
import cloud.commandframework.help.HelpHandler;
import cloud.commandframework.help.HelpHandlerFactory;
import cloud.commandframework.internal.CommandNode;
import cloud.commandframework.internal.CommandRegistrationHandler;
import cloud.commandframework.meta.CommandMeta;
import cloud.commandframework.permission.AndPermission;
import cloud.commandframework.permission.OrPermission;
import cloud.commandframework.permission.Permission;
import cloud.commandframework.permission.PredicatePermission;
import cloud.commandframework.services.ServicePipeline;
import cloud.commandframework.services.State;
import cloud.commandframework.setting.Configurable;
import cloud.commandframework.setting.ManagerSetting;
import cloud.commandframework.state.RegistrationState;
import cloud.commandframework.state.Stateful;
import io.leangen.geantyref.TypeToken;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.common.returnsreceiver.qual.This;

/**
 * The manager is responsible for command registration, parsing delegation, etc.
 *
 * @param <C> the command sender type used to execute commands
 */
@SuppressWarnings({"unchecked", "unused"})
@API(status = API.Status.STABLE)
public abstract class CommandManager<C> implements Stateful<RegistrationState>, CommandBuilderSource<C> {

    private final Configurable<ManagerSetting> settings = Configurable.enumConfigurable(ManagerSetting.class)
            .set(ManagerSetting.ENFORCE_INTERMEDIARY_PERMISSIONS, true);
    private final ServicePipeline servicePipeline = ServicePipeline.builder().build();
    private final ParserRegistry<C> parserRegistry = new StandardParserRegistry<>();
    private final Collection<Command<C>> commands = new LinkedList<>();
    private final ParameterInjectorRegistry<C> parameterInjectorRegistry = new ParameterInjectorRegistry<>();
    private final CommandTree<C> commandTree;
    private final SuggestionFactory<C, ? extends Suggestion> suggestionFactory;
    private final Set<CloudCapability> capabilities = new HashSet<>();
    private final ExceptionController<C> exceptionController = new ExceptionController<>();
    private final CommandExecutor<C> commandExecutor;

    private CaptionFormatter<C, String> captionVariableReplacementHandler = CaptionFormatter.placeholderReplacing();
    private CommandSyntaxFormatter<C> commandSyntaxFormatter = new StandardCommandSyntaxFormatter<>();
    private CommandSuggestionProcessor<C> commandSuggestionProcessor =
            new FilteringCommandSuggestionProcessor<>(FilteringCommandSuggestionProcessor.Filter.startsWith(true));
    private CommandRegistrationHandler<C> commandRegistrationHandler;
    private CaptionRegistry<C> captionRegistry;
    private SuggestionMapper<? extends Suggestion> suggestionMapper = SuggestionMapper.identity();
    private HelpHandlerFactory<C> helpHandlerFactory = HelpHandlerFactory.standard(this);
    private final AtomicReference<RegistrationState> state = new AtomicReference<>(RegistrationState.BEFORE_REGISTRATION);

    /**
     * Create a new command manager instance.
     *
     * @param executionCoordinator       Execution coordinator instance. When choosing the appropriate coordinator for your
     *                                   project, be sure to consider any limitations noted by the platform documentation.
     * @param commandRegistrationHandler Command registration handler. This will get called every time a new command is
     *                                   registered to the command manager. This may be used to forward command registration
     *                                   to the platform.
     */
    protected CommandManager(
            final @NonNull ExecutionCoordinator<C> executionCoordinator,
            final @NonNull CommandRegistrationHandler<C> commandRegistrationHandler
    ) {
        final CommandContextFactory<C> commandContextFactory = new StandardCommandContextFactory<>(this);
        this.commandTree = CommandTree.newTree(this);
        this.commandRegistrationHandler = commandRegistrationHandler;
        this.suggestionFactory = SuggestionFactory.delegating(
                this,
                (suggestion) -> this.suggestionMapper.map(suggestion),
                commandContextFactory,
                executionCoordinator
        );
        this.commandExecutor = new StandardCommandExecutor<>(
                this,
                executionCoordinator,
                commandContextFactory
        );
        /* Register service types */
        this.servicePipeline.registerServiceType(new TypeToken<CommandPreprocessor<C>>() {
        }, new AcceptingCommandPreprocessor<>());
        this.servicePipeline.registerServiceType(new TypeToken<CommandPostprocessor<C>>() {
        }, new AcceptingCommandPostprocessor<>());
        /* Create the caption registry */
        this.captionRegistry = new StandardCaptionRegistryFactory<C>().create();
        /* Register default injectors */
        this.parameterInjectorRegistry().registerInjector(
                CommandContext.class,
                (context, annotationAccessor) -> context
        );
    }

    /**
     * Returns the command executor.
     *
     * <p>The executor is used to parse &amp; execute commands.</p>
     *
     * @return the command executor
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull CommandExecutor<C> commandExecutor() {
        return this.commandExecutor;
    }

    /**
     * Returns the suggestion factory.
     * <p>
     * Platform implementations of command manager may override this method to make it easier to work with platform
     * suggestion types, for example:
     * <pre>{@code
     * @Override
     * public @NonNull SuggestionFactory<C, YourType> suggestionFactory() {
     *    return super.suggestionFactory().mapped(suggestion -> yourType);
     * }
     * }</pre>
     * @return the suggestion factory
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull SuggestionFactory<C, ? extends Suggestion> suggestionFactory() {
        return this.suggestionFactory;
    }

    /**
     * Register a new command to the command manager and insert it into the underlying command tree. The command will be
     * forwarded to the {@link CommandRegistrationHandler} and will, depending on the platform, be forwarded to the platform.
     * <p>
     * Different command manager implementations have different requirements for the command registration. It is possible
     * that a command manager may only allow registration during certain stages of the application lifetime. Read the platform
     * command manager documentation to find out more about your particular platform
     *
     * @param command Command to register
     * @return The command manager instance. This is returned so that these method calls may be chained. This will always
     *         return {@code this}.
     */
    @SuppressWarnings("unchecked")
    public @This @NonNull CommandManager<C> command(final @NonNull Command<? extends C> command) {
        if (!(this.transitionIfPossible(RegistrationState.BEFORE_REGISTRATION, RegistrationState.REGISTERING)
                || this.isCommandRegistrationAllowed())) {
            throw new IllegalStateException("Unable to register commands because the manager is no longer in a registration "
                    + "state. Your platform may allow unsafe registrations by enabling the appropriate manager setting.");
        }
        this.commandTree.insertCommand((Command<C>) command);
        this.commands.add((Command<C>) command);
        return this;
    }

    /**
     * Creates a command using the given {@code commandFactory} and inserts it into the underlying command tree. The command
     * will be forwarded to the {@link CommandRegistrationHandler} and will, depending on the platform, be forwarded to the
     * platform.
     * <p>
     * Different command manager implementations have different requirements for the command registration. It is possible
     * that a command manager may only allow registration during certain stages of the application lifetime. Read the platform
     * command manager documentation to find out more about your particular platform
     *
     * @param commandFactory the command factory to register
     * @return The command manager instance. This is returned so that these method calls may be chained. This will always
     *         return {@code this}
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @This @NonNull CommandManager<C> command(final @NonNull CommandFactory<C> commandFactory) {
        commandFactory.createCommands(this).forEach(this::command);
        return this;
    }

    /**
     * Register a new command
     *
     * @param command Command to register. {@link Command.Builder#build()}} will be invoked.
     * @return The command manager instance
     */
    @SuppressWarnings("unchecked")
    public @NonNull CommandManager<C> command(final Command.@NonNull Builder<? extends C> command) {
        return this.command(((Command.Builder<C>) command).manager(this).build());
    }

    /**
     * Returns the string-producing caption formatter.
     *
     * @return the formatter
     * @since 2.0.0
     * @see #captionFormatter(CaptionFormatter)
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull CaptionFormatter<C, String> captionFormatter() {
        return this.captionVariableReplacementHandler;
    }

    /**
     * Sets the string-producing caption formatter.
     *
     * @param captionFormatter the new formatter
     * @since 2.0.0
     * @see #captionFormatter()
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    public void captionFormatter(final @NonNull CaptionFormatter<C, String> captionFormatter) {
        this.captionVariableReplacementHandler = captionFormatter;
    }

    /**
     * Returns the command syntax formatter.
     *
     * @return the syntax formatter
     * @since 1.7.0
     * @see #commandSyntaxFormatter(CommandSyntaxFormatter)
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    public @NonNull CommandSyntaxFormatter<C> commandSyntaxFormatter() {
        return this.commandSyntaxFormatter;
    }

    /**
     * Sets the command syntax formatter.
     * <p>
     * The command syntax formatter is used to format the command syntax hints that are used in help and error messages.
     *
     * @param commandSyntaxFormatter new formatter
     * @since 1.7.0
     * @see #commandSyntaxFormatter()
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    public void commandSyntaxFormatter(final @NonNull CommandSyntaxFormatter<C> commandSyntaxFormatter) {
        this.commandSyntaxFormatter = commandSyntaxFormatter;
    }

    /**
     * Returns the command registration handler.
     * <p>
     * The command registration handler is able to intercept newly created/deleted commands, in order to propagate
     * these changes to the native command handler of the platform.
     * <p>
     * In platforms without a native command concept, this is likely to return
     * {@link CommandRegistrationHandler#nullCommandRegistrationHandler()}.
     *
     * @return the command registration handler
     * @since 1.7.0
     */
    public @NonNull CommandRegistrationHandler<C> commandRegistrationHandler() {
        return this.commandRegistrationHandler;
    }

    @API(status = API.Status.STABLE, since = "1.7.0")
    protected final void commandRegistrationHandler(final @NonNull CommandRegistrationHandler<C> commandRegistrationHandler) {
        this.requireState(RegistrationState.BEFORE_REGISTRATION);
        this.commandRegistrationHandler = commandRegistrationHandler;
    }

    /**
     * Registers the given {@code capability}.
     *
     * @param capability the capability
     * @since 1.7.0
     * @see #hasCapability(CloudCapability)
     * @see #capabilities()
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    protected final void registerCapability(final @NonNull CloudCapability capability) {
        this.capabilities.add(capability);
    }

    /**
     * Checks whether the cloud implementation has the given {@code capability}.
     *
     * @param capability the capability
     * @return {@code true} if the implementation has the {@code capability}, {@code false} if not
     * @since 1.7.0
     * @see #capabilities()
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    public boolean hasCapability(final @NonNull CloudCapability capability) {
        return this.capabilities.contains(capability);
    }

    /**
     * Returns an unmodifiable snapshot of the currently registered {@link CloudCapability capabilities}.
     *
     * @return the currently registered capabilities
     * @since 1.7.0
     * @see #hasCapability(CloudCapability)
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    public @NonNull Collection<@NonNull CloudCapability> capabilities() {
        return Collections.unmodifiableSet(new HashSet<>(this.capabilities));
    }

    /**
     * Check if the command sender has the required permission. If the permission node is
     * empty, this should return {@code true}
     *
     * @param sender     Command sender
     * @param permission Permission node
     * @return {@code true} if the sender has the permission, else {@code false}
     */
    @SuppressWarnings("unchecked")
    public boolean hasPermission(
            final @NonNull C sender,
            final @NonNull Permission permission
    ) {
        if (permission instanceof PredicatePermission) {
            return ((PredicatePermission<C>) permission).hasPermission(sender);
        } else if (permission instanceof OrPermission) {
            for (final Permission innerPermission : permission.permissions()) {
                if (this.hasPermission(sender, innerPermission)) {
                    return true;
                }
            }
            return false;
        } else if (permission instanceof AndPermission) {
            for (final Permission innerPermission : permission.permissions()) {
                if (!this.hasPermission(sender, innerPermission)) {
                    return false;
                }
            }
            return true;
        }
        return permission.permissionString().isEmpty()
                || this.hasPermission(sender, permission.permissionString());
    }

    /**
     * Returns the caption registry.
     *
     * @return the caption registry
     * @since 1.7.0
     * @see #captionRegistry(CaptionRegistry)
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    public final @NonNull CaptionRegistry<C> captionRegistry() {
        return this.captionRegistry;
    }

    /**
     * Replaces the caption registry.
     * <p>
     * Some platforms may inject their own captions into the default caption registry,
     * and so you may need to insert these captions yourself, if you do decide to replace the caption registry.
     *
     * @param captionRegistry new caption registry.
     * @see #captionRegistry()
     * @since 1.7.0
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    public final void captionRegistry(final @NonNull CaptionRegistry<C> captionRegistry) {
        this.captionRegistry = captionRegistry;
    }

    /**
     * Check if the command sender has the required permission. If the permission node is
     * empty, this should return {@code true}
     *
     * @param sender     Command sender
     * @param permission Permission node
     * @return {@code true} if the sender has the permission, else {@code false}
     */
    public abstract boolean hasPermission(@NonNull C sender, @NonNull String permission);

    /**
     * Sets the suggestion mapper.
     * <p>
     * The suggestion mapper is invoked after the suggestions have been generated by the {@link CommandComponent command
     * components} in the {@link CommandTree command tree}, but before the platform mappers configured using
     * {@link #suggestionFactory()} gets to map the suggestions.
     * This means that you can perform custom mapping to the platform-native suggestion types using your suggestion mapper.
     *
     * @param <S>              the custom type
     * @param suggestionMapper the suggestion mapper
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public <S extends Suggestion> void suggestionMapper(final @NonNull SuggestionMapper<S> suggestionMapper) {
        this.suggestionMapper = suggestionMapper;
    }

    /**
     * Deletes the given {@code rootCommand}.
     * <p>
     * This will delete all chains that originate at the root command.
     *
     * @param rootCommand The root command to delete
     * @throws CloudCapability.CloudCapabilityMissingException If {@link CloudCapability.StandardCapabilities#ROOT_COMMAND_DELETION} is missing
     * @since 1.7.0
     */
    @API(status = API.Status.EXPERIMENTAL, since = "1.7.0")
    public void deleteRootCommand(final @NonNull String rootCommand) throws CloudCapability.CloudCapabilityMissingException {
        if (!this.hasCapability(CloudCapability.StandardCapabilities.ROOT_COMMAND_DELETION)) {
            throw new CloudCapability.CloudCapabilityMissingException(CloudCapability.StandardCapabilities.ROOT_COMMAND_DELETION);
        }

        // Mark the command for deletion.
        final CommandNode<C> node = this.commandTree.getNamedNode(rootCommand);
        if (node == null || node.component() == null) {
            // If the node doesn't exist, we don't really need to delete it...
            return;
        }

        // The registration handler gets to act before we destruct the command.
        this.commandRegistrationHandler.unregisterRootCommand(node.component());

        // We then delete it from the tree.
        this.commandTree.deleteRecursively(node, true, this.commands::remove);

        // And lastly we re-build the entire tree.
        this.commandTree.verifyAndRegister();
    }

    /**
     * Returns all root command names.
     *
     * @return Root command names.
     * @since 1.7.0
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    public @NonNull Collection<@NonNull String> rootCommands() {
        return this.commandTree.rootNodes()
                .stream()
                .map(CommandNode::component)
                .filter(Objects::nonNull)
                .filter(component -> component.type() == CommandComponent.ComponentType.LITERAL)
                .map(CommandComponent::name)
                .collect(Collectors.toList());
    }

    /**
     * Invokes {@link Command.Builder#manager(CommandManager)} with {@code this} instance and returns the updated builder.
     *
     * @param builder builder to decorate
     * @return the decorated builder
     */
    @Override
    public final Command.@NonNull Builder<C> decorateBuilder(final Command.@NonNull Builder<C> builder) {
        return builder.manager(this);
    }

    /**
     * Create a new command component builder.
     * <p>
     * This will also invoke {@link CommandComponent.Builder#commandManager(CommandManager)}
     * so that the argument is associated with the calling command manager. This allows for parser inference based on
     * the type, with the help of the {@link ParserRegistry parser registry}.
     *
     * @param type Argument type
     * @param name Argument name
     * @param <T>  Generic argument name
     * @return Component builder
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public <T> CommandComponent.@NonNull Builder<C, T> componentBuilder(
            final @NonNull Class<T> type,
            final @NonNull String name
    ) {
        return CommandComponent.<C, T>ofType(type, name).commandManager(this);
    }

    /**
     * Create a new command flag builder
     *
     * @param name Flag name
     * @return Flag builder
     */
    public CommandFlag.@NonNull Builder<Void> flagBuilder(final @NonNull String name) {
        return CommandFlag.builder(name);
    }

    /**
     * Returns the internal command tree.
     * <p>
     * Be careful when accessing the command tree. Do not interact with it, unless you
     * absolutely know what you're doing.
     *
     * @return the command tree
     * @since 1.7.0
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    public @NonNull CommandTree<C> commandTree() {
        return this.commandTree;
    }

    /**
     * Constructs a default {@link CommandMeta} instance.
     *
     * @return default command meta
     */
    @Override
    public @NonNull CommandMeta createDefaultCommandMeta() {
        return CommandMeta.empty();
    }

    /**
     * Register a new command preprocessor. The order they are registered in is respected, and they
     * are called in LIFO order
     *
     * @param processor Processor to register
     * @see #preprocessContext(CommandContext, CommandInput) Preprocess a context
     */
    public void registerCommandPreProcessor(final @NonNull CommandPreprocessor<C> processor) {
        this.servicePipeline.registerServiceImplementation(
                new TypeToken<CommandPreprocessor<C>>() {
                },
                processor,
                Collections.emptyList()
        );
    }

    /**
     * Register a new command postprocessor. The order they are registered in is respected, and they
     * are called in LIFO order
     *
     * @param processor Processor to register
     * @see #preprocessContext(CommandContext, CommandInput) Preprocess a context
     */
    public void registerCommandPostProcessor(final @NonNull CommandPostprocessor<C> processor) {
        this.servicePipeline.registerServiceImplementation(new TypeToken<CommandPostprocessor<C>>() {
                                                           }, processor,
                Collections.emptyList()
        );
    }

    /**
     * Preprocess a command context instance
     *
     * @param context      Command context
     * @param commandInput Command input as supplied by sender
     * @return {@link State#ACCEPTED} if the command should be parsed and executed, else {@link State#REJECTED}
     * @see #registerCommandPreProcessor(CommandPreprocessor) Register a command preprocessor
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public State preprocessContext(
            final @NonNull CommandContext<C> context,
            final @NonNull CommandInput commandInput
    ) {
        this.servicePipeline.pump(CommandPreprocessingContext.of(context, commandInput))
                .through(new TypeToken<CommandPreprocessor<C>>() {
                })
                .getResult();
        return context.<String>optional(AcceptingCommandPreprocessor.PROCESSED_INDICATOR_KEY).orElse("").isEmpty()
                ? State.REJECTED
                : State.ACCEPTED;
    }

    /**
     * Postprocess a command context instance
     *
     * @param context Command context
     * @param command Command instance
     * @return {@link State#ACCEPTED} if the command should be parsed and executed, else {@link State#REJECTED}
     * @see #registerCommandPostProcessor(CommandPostprocessor) Register a command postprocessor
     */
    public State postprocessContext(
            final @NonNull CommandContext<C> context,
            final @NonNull Command<C> command
    ) {
        this.servicePipeline.pump(CommandPostprocessingContext.of(context, command))
                .through(new TypeToken<CommandPostprocessor<C>>() {
                })
                .getResult();
        return context.<String>optional(AcceptingCommandPostprocessor.PROCESSED_INDICATOR_KEY).orElse("").isEmpty()
                ? State.REJECTED
                : State.ACCEPTED;
    }

    /**
     * Returns the command suggestion processor used in this command manager.
     *
     * @return the command suggestion processor
     * @since 1.7.0
     * @see #commandSuggestionProcessor(CommandSuggestionProcessor)
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    public @NonNull CommandSuggestionProcessor<C> commandSuggestionProcessor() {
        return this.commandSuggestionProcessor;
    }

    /**
     * Sets the command suggestion processor.
     * <p>
     * This will be called every time {@link SuggestionFactory#suggest(CommandContext, String)} is called, to process the list
     * of suggestions before it's returned to the caller.
     *
     * @param commandSuggestionProcessor the new command suggestion processor
     * @since 1.7.0
     * @see #commandSuggestionProcessor()
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    public void commandSuggestionProcessor(final @NonNull CommandSuggestionProcessor<C> commandSuggestionProcessor) {
        this.commandSuggestionProcessor = commandSuggestionProcessor;
    }

    /**
     * Returns the parser registry instance.
     * <p>
     * The parser registry contains default mappings to {@link ArgumentParser argument parsers} and
     * allows for the registration of custom mappings. The parser registry also contains mappings between
     * annotations and {@link ParserParameter}, which allows for the customization of parser settings by
     * using annotations.
     * <p>
     * When creating a new parser type, it is highly recommended to register it in the parser registry.
     * In particular, default parser types (shipped with cloud implementations) should be registered in the
     * constructor of the platform {@link CommandManager}.
     *
     * @return the parser registry instance
     * @since 1.7.0
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    public @NonNull ParserRegistry<C> parserRegistry() {
        return this.parserRegistry;
    }

    /**
     * Get the parameter injector registry instance
     *
     * @return Parameter injector registry
     * @since 1.3.0
     */
    public final @NonNull ParameterInjectorRegistry<C> parameterInjectorRegistry() {
        return this.parameterInjectorRegistry;
    }

    /**
     * Returns the exception controller.
     * <p>
     * The exception controller is responsible for exception handler registration.
     *
     * @return the exception controller
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public final @NonNull ExceptionController<C> exceptionController() {
        return this.exceptionController;
    }

    /**
     * Returns an unmodifiable view of all registered commands.
     *
     * @return unmodifiable view of all registered commands
     * @since 1.7.0
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    public final @NonNull Collection<@NonNull Command<C>> commands() {
        return Collections.unmodifiableCollection(this.commands);
    }

    /**
     * Creates a new command help handler instance.
     * <p>
     * The command helper handler can be used to assist in the production of command help menus, etc.
     * <p>
     * This command help handler instance will display all commands registered in this command manager.
     *
     * @return a new command helper handler instance
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public final @NonNull HelpHandler<C> createHelpHandler() {
        return this.helpHandlerFactory.createHelpHandler(cmd -> true);
    }

    /**
     * Creates a new command help handler instance.
     * <p>
     * The command helper handler can be used to assist in the production of commad help menus, etc.
     * <p>
     * A filter can be specified to filter what commands
     * registered in this command manager are visible in the help menu.
     *
     * @param filter predicate that filters what commands are displayed in the help menu.
     * @return a new command helper handler instance
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public final @NonNull HelpHandler<C> createHelpHandler(
            final @NonNull CommandPredicate<C> filter
    ) {
        return this.helpHandlerFactory.createHelpHandler(filter);
    }

    /**
     * Returns the help handler factory.
     *
     * @return the help handler factory
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public final @NonNull HelpHandlerFactory<C> helpHandlerFactory() {
        return this.helpHandlerFactory;
    }

    /**
     * Sets the new help handler factory.
     * <p>
     * The help handler factory is used to create {@link cloud.commandframework.help.HelpHandler} instances.
     *
     * @param helpHandlerFactory the new factory instance
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public final void helpHandlerFactory(final @NonNull HelpHandlerFactory<C> helpHandlerFactory) {
        this.helpHandlerFactory = helpHandlerFactory;
    }

    /**
     * Returns a {@link Configurable} instance that can be used to modify the settings for this command manager instance.
     *
     * @return settings instance
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull Configurable<ManagerSetting> settings() {
        return this.settings;
    }

    @Override
    public final @NonNull RegistrationState state() {
        return this.state.get();
    }

    @Override
    public final boolean transitionIfPossible(final @NonNull RegistrationState in, final @NonNull RegistrationState out) {
        return this.state.compareAndSet(in, out) || this.state.get() == out;
    }

    /**
     * Transition the command manager from either {@link RegistrationState#BEFORE_REGISTRATION} or
     * {@link RegistrationState#REGISTERING} to {@link RegistrationState#AFTER_REGISTRATION}.
     *
     * @throws IllegalStateException if the manager is not in the expected state
     * @since 1.4.0
     */
    @API(status = API.Status.STABLE, since = "1.4.0")
    protected final void lockRegistration() {
        if (this.state() == RegistrationState.BEFORE_REGISTRATION) {
            this.transitionOrThrow(RegistrationState.BEFORE_REGISTRATION, RegistrationState.AFTER_REGISTRATION);
            return;
        }
        this.transitionOrThrow(RegistrationState.REGISTERING, RegistrationState.AFTER_REGISTRATION);
    }

    /**
     * Check if command registration is allowed.
     * <p>
     * On platforms where unsafe registration is possible, this can be overridden by enabling the
     * {@link ManagerSetting#ALLOW_UNSAFE_REGISTRATION} setting.
     *
     * @return {@code true} if the registration is allowed, else {@code false}
     * @since 1.2.0
     */
    @API(status = API.Status.STABLE, since = "1.2.0")
    public boolean isCommandRegistrationAllowed() {
        return this.settings().get(ManagerSetting.ALLOW_UNSAFE_REGISTRATION)
                || this.state.get() != RegistrationState.AFTER_REGISTRATION;
    }
}
