package sos.create.plugintester;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mod(PluginTesterMod.MOD_ID)
@Mod.EventBusSubscriber(modid = PluginTesterMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PluginTesterMod {
    public static final String MOD_ID = "plugin_tester";
    private static final String PAPER_VERSION = "1.21.4";
    private static final Path ROOT = FMLPaths.GAMEDIR.get().resolve("mods").resolve("PluginTester");
    private static final Path PLUGINS = ROOT.resolve("plugins");
    private static final Path READY = ROOT.resolve("ready");
    private static final Path REPORTS = ROOT.resolve("reports");
    private static final Path SERVER = ROOT.resolve("server");
    private static Process serverProcess;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (serverProcess != null && serverProcess.isAlive()) {
                try {
                    serverProcess.getOutputStream().write("stop\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    serverProcess.getOutputStream().flush();
                } catch (IOException ignored) { }
            }
        }, "PluginTester-shutdown"));
    }

    public PluginTesterMod() {
        try {
            Files.createDirectories(PLUGINS);
            Files.createDirectories(READY);
            Files.createDirectories(REPORTS);
            Files.createDirectories(SERVER);
            migrateLegacyPlugins();
            createInstructions();
        } catch (IOException e) {
            System.err.println("[PluginTester] Cannot create folders: " + e.getMessage());
        }

    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("plugintester")
            .then(Commands.literal("scan").executes(ctx -> scan(ctx.getSource()::sendSystemMessage)))
            .then(Commands.literal("start").executes(ctx -> start(ctx.getSource()::sendSystemMessage)))
            .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource()::sendSystemMessage)))
            .then(Commands.literal("status").executes(ctx -> status(ctx.getSource()::sendSystemMessage)))
            .then(Commands.literal("path").executes(ctx -> path(ctx.getSource()::sendSystemMessage)))
            .then(Commands.literal("promote")
                .then(Commands.argument("file", StringArgumentType.greedyString())
                    .executes(ctx -> promote(StringArgumentType.getString(ctx, "file"), ctx.getSource()::sendSystemMessage))))
        );
    }

    @FunctionalInterface
    private interface Feedback { void send(Component message); }

    private static int scan(Feedback feedback) {
        try {
            Files.createDirectories(PLUGINS);
            List<Path> jars;
            try (var stream = Files.list(PLUGINS)) {
                jars = stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted().toList();
            }
            if (jars.isEmpty()) {
                feedback.send(Component.literal("[PluginTester] Папка plugins пуста: " + PLUGINS));
                return 0;
            }

            Set<String> pluginNames = new HashSet<>();
            List<ScanResult> results = new ArrayList<>();
            for (Path jar : jars) {
                ScanResult result = inspect(jar);
                results.add(result);
                if (result.pluginName != null) pluginNames.add(result.pluginName.toLowerCase(Locale.ROOT));
            }

            for (ScanResult result : results) {
                for (String dependency : result.requiredDependencies) {
                    if (!pluginNames.contains(dependency.toLowerCase(Locale.ROOT))) {
                        result.errors.add("Не найдена обязательная зависимость: " + dependency);
                    }
                }
                for (String dependency : result.optionalDependencies) {
                    if (!pluginNames.contains(dependency.toLowerCase(Locale.ROOT))) {
                        result.warnings.add("Не найдена необязательная зависимость: " + dependency);
                    }
                }
            }

            StringBuilder report = new StringBuilder("Plugin Tester report\n")
                .append("Дата: ").append(new Date()).append("\n")
                .append("Проверено JAR: ").append(results.size()).append("\n\n");
            int passed = 0;
            for (ScanResult result : results) {
                if (result.errors.isEmpty()) passed++;
                report.append(result.file.getFileName()).append(" — ")
                    .append(result.errors.isEmpty() ? "СТРУКТУРА OK" : "ОШИБКИ").append("\n");
                if (result.pluginName != null) report.append("  name: ").append(result.pluginName).append("\n");
                if (result.mainClass != null) report.append("  main: ").append(result.mainClass).append("\n");
                result.errors.forEach(s -> report.append("  ERROR: ").append(s).append("\n"));
                result.warnings.forEach(s -> report.append("  WARNING: ").append(s).append("\n"));
                report.append("\n");
            }
            Path reportFile = REPORTS.resolve("scan-" + System.currentTimeMillis() + ".txt");
            Files.writeString(reportFile, report);
            feedback.send(Component.literal("[PluginTester] Проверено: " + results.size()
                + ", без структурных ошибок: " + passed + ". Отчёт: " + reportFile.getFileName()));
            feedback.send(Component.literal("[PluginTester] Это статическая проверка, не гарантия совместимости. Для запуска: /plugintester start"));
            return results.size();
        } catch (Exception e) {
            feedback.send(Component.literal("[PluginTester] Ошибка проверки: " + e.getMessage()));
            return 0;
        }
    }

    private static ScanResult inspect(Path jarPath) {
        ScanResult result = new ScanResult(jarPath);
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var descriptor = jar.getJarEntry("plugin.yml");
            if (descriptor == null) descriptor = jar.getJarEntry("paper-plugin.yml");
            if (descriptor == null) {
                result.errors.add("Нет plugin.yml или paper-plugin.yml — это может быть не Bukkit/Paper-плагин.");
                return result;
            }
            String yaml;
            try (var in = jar.getInputStream(descriptor)) {
                yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            result.pluginName = scalar(yaml, "name");
            result.mainClass = scalar(yaml, "main");
            if (result.pluginName == null) result.errors.add("В дескрипторе отсутствует name.");
            if (result.mainClass == null) result.errors.add("В дескрипторе отсутствует main.");
            else {
                String classPath = result.mainClass.replace('.', '/') + ".class";
                if (jar.getJarEntry(classPath) == null) result.errors.add("Главный класс не найден в JAR: " + result.mainClass);
            }
            result.requiredDependencies.addAll(readYamlList(yaml, "depend"));
            result.optionalDependencies.addAll(readYamlList(yaml, "softdepend"));
            if (result.pluginName == null || result.mainClass == null) result.warnings.add("Дескриптор заполнен не полностью.");
        } catch (Exception e) {
            result.errors.add("Не удалось прочитать JAR: " + e.getMessage());
        }
        return result;
    }

    private static String scalar(String yaml, String key) {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + "\\s*:\\s*['\"]?([^\\r\\n#'\"]+)").matcher(yaml);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static List<String> readYamlList(String yaml, String key) {
        List<String> found = new ArrayList<>();
        boolean inSection = false;
        Pattern section = Pattern.compile("^" + Pattern.quote(key) + "\\s*:\\s*(.*)$");
        for (String rawLine : yaml.split("\\R")) {
            String line = rawLine.replaceFirst("\\s+#.*$", "").trim();
            Matcher header = section.matcher(line);
            if (header.matches()) {
                inSection = true;
                String value = header.group(1).trim();
                if (value.startsWith("[") && value.endsWith("]")) {
                    String inside = value.substring(1, value.length() - 1);
                    for (String item : inside.split(",")) addYamlValue(found, item);
                    inSection = false;
                } else if (!value.isEmpty()) {
                    addYamlValue(found, value);
                    inSection = false;
                }
                continue;
            }
            if (inSection) {
                if (!rawLine.isEmpty() && !Character.isWhitespace(rawLine.charAt(0))) {
                    inSection = false;
                    continue;
                }
                Matcher item = Pattern.compile("^\\s+-\\s*(.+?)\\s*$").matcher(rawLine);
                if (item.matches()) addYamlValue(found, item.group(1));
            }
        }
        return found;
    }

    private static void addYamlValue(List<String> target, String raw) {
        String value = raw.trim().replaceAll("^['\\\"]|['\\\"]$", "");
        if (value.matches("[A-Za-z0-9_.-]+") && !target.contains(value)) target.add(value);
    }

    private static int start(Feedback feedback) {
        try {
            if (serverProcess != null && serverProcess.isAlive()) {
                feedback.send(Component.literal("[PluginTester] Тестовый сервер уже запущен."));
                return 1;
            }
            Path paper = SERVER.resolve("paper.jar");
            if (Files.notExists(paper)) {
                feedback.send(Component.literal("[PluginTester] Сначала помести Paper " + PAPER_VERSION + " в mods/PluginTester/server/paper.jar"));
                return 0;
            }
            Path properties = SERVER.resolve("server.properties");
            if (Files.notExists(properties)) {
                Files.writeString(properties, "view-distance=4\nsimulation-distance=3\nmax-players=1\n");
            }
            Path eula = SERVER.resolve("eula.txt");
            if (Files.notExists(eula) || !Files.readString(eula).contains("eula=true")) {
                if (Files.notExists(eula)) Files.writeString(eula, "eula=false\n");
                feedback.send(Component.literal("[PluginTester] Для запуска прочитай EULA Minecraft и, если согласен, измени server/eula.txt на eula=true."));
                return 0;
            }
            Path serverPlugins = SERVER.resolve("plugins");
            Files.createDirectories(serverPlugins);
            try (var stream = Files.list(PLUGINS)) {
                for (Path file : stream.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                    Files.copy(file, serverPlugins.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Path log = REPORTS.resolve("paper-console.log");
            Files.deleteIfExists(log);
            Files.createFile(log);
            ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-Xms512M", "-Xmx1024M",
                "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", "-XX:ActiveProcessorCount=2",
                "-XX:ParallelGCThreads=2", "-XX:ConcGCThreads=1", "-jar", "paper.jar", "--nogui")
                .directory(SERVER.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            serverProcess = builder.start();
            feedback.send(Component.literal("[PluginTester] Paper запускается. RAM: 512 МБ–1 ГБ. Лог: reports/paper-console.log"));
            feedback.send(Component.literal("[PluginTester] Подключение в Minecraft: localhost (если сервер успешно загрузился)."));
            return 1;
        } catch (Exception e) {
            feedback.send(Component.literal("[PluginTester] Не удалось запустить Paper: " + e.getMessage()));
            return 0;
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static int stop(Feedback feedback) {
        try {
            if (serverProcess == null || !serverProcess.isAlive()) {
                feedback.send(Component.literal("[PluginTester] Сервер не запущен."));
                return 0;
            }
            serverProcess.getOutputStream().write("stop\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            serverProcess.getOutputStream().flush();
            feedback.send(Component.literal("[PluginTester] Команда остановки отправлена Paper."));
            return 1;
        } catch (IOException e) {
            feedback.send(Component.literal("[PluginTester] Не удалось отправить stop: " + e.getMessage()));
            return 0;
        }
    }

    private static int status(Feedback feedback) {
        boolean running = serverProcess != null && serverProcess.isAlive();
        feedback.send(Component.literal("[PluginTester] Paper: " + (running ? "работает" : "остановлен")));
        return running ? 1 : 0;
    }

    private static int path(Feedback feedback) {
        try {
            Files.createDirectories(PLUGINS);
            Files.createDirectories(READY);
            Files.createDirectories(REPORTS);
            Files.createDirectories(SERVER);
            feedback.send(Component.literal("[PluginTester] Папка плагинов: " + PLUGINS.toAbsolutePath()));
            feedback.send(Component.literal("[PluginTester] Папка Paper: " + SERVER.toAbsolutePath()));
            return 1;
        } catch (IOException e) {
            feedback.send(Component.literal("[PluginTester] Не удалось создать папки: " + e.getMessage()));
            return 0;
        }
    }

    private static int promote(String fileName, Feedback feedback) {
        try {
            Path name = Path.of(fileName);
            if (name.isAbsolute() || name.getNameCount() != 1 || !name.getFileName().toString().endsWith(".jar")) {
                feedback.send(Component.literal("[PluginTester] Укажи только имя JAR-файла, без путей."));
                return 0;
            }
            Path source = PLUGINS.resolve(name);
            if (Files.notExists(source)) {
                feedback.send(Component.literal("[PluginTester] Файл не найден в папке plugins."));
                return 0;
            }
            ScanResult result = inspect(source);
            if (!result.errors.isEmpty()) {
                feedback.send(Component.literal("[PluginTester] Нельзя перенести: статическая проверка нашла ошибки."));
                return 0;
            }
            Path log = REPORTS.resolve("paper-console.log");
            if (result.pluginName == null || Files.notExists(log)) {
                feedback.send(Component.literal("[PluginTester] Сначала запусти Paper и проверь плагин на сервере."));
                return 0;
            }
            if (serverProcess == null || !serverProcess.isAlive()) {
                feedback.send(Component.literal("[PluginTester] Тестовый Paper-сервер сейчас не работает. Запусти его и дождись полной загрузки."));
                return 0;
            }
            String console = Files.readString(log).toLowerCase(Locale.ROOT);
            String plugin = result.pluginName.toLowerCase(Locale.ROOT);
            boolean enabled = console.contains("enabling " + plugin)
                || console.contains("loading server plugin " + plugin)
                || console.contains("enabled " + plugin);
            boolean completed = console.contains("done (");
            boolean failed = (console.contains("could not load") && console.contains(name.toString().toLowerCase(Locale.ROOT)))
                || console.contains("error occurred while enabling " + plugin)
                || console.contains("failed to load plugin " + plugin);
            if (!enabled || !completed || failed) {
                feedback.send(Component.literal("[PluginTester] Плагин не подтверждён логом Paper. Проверь reports/paper-console.log и исправь ошибки."));
                return 0;
            }
            Files.copy(source, READY.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            feedback.send(Component.literal("[PluginTester] Плагин прошёл базовую проверку структуры и найден в логе успешной загрузки Paper. Скопирован в ready."));
            return 1;
        } catch (Exception e) {
            feedback.send(Component.literal("[PluginTester] Ошибка переноса: " + e.getMessage()));
            return 0;
        }
    }

    private static void migrateLegacyPlugins() throws IOException {
        Path legacyPlugins = FMLPaths.GAMEDIR.get().resolve("PluginTester").resolve("plugins");
        if (Files.isDirectory(legacyPlugins)) {
            try (var stream = Files.list(legacyPlugins)) {
                for (Path file : stream
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .toList()) {
                    Path destination = PLUGINS.resolve(file.getFileName());
                    if (Files.notExists(destination)) {
                        Files.copy(file, destination);
                    }
                }
            }
        }
    }

    private static void createInstructions() throws IOException {
        Path instructions = ROOT.resolve("ИНСТРУКЦИЯ.txt");
        if (Files.notExists(instructions)) {
            Files.writeString(instructions,
                "PLUGIN TESTER — Forge / Minecraft 1.21.11\nТестовый сервер: Paper 1.21.4\n\n" +
                "1. Помести Bukkit/Paper .jar в папку plugins.\n" +
                "2. В игре выполни /plugintester scan — проверка структуры и plugin.yml.\n" +
                "3. Для реального теста скачай Paper " + PAPER_VERSION + " и положи файл как server/paper.jar.\n" +
                "4. Прочитай EULA. Если согласен, поменяй eula=false на eula=true в server/eula.txt.\n" +
                "5. Выполни /plugintester start. Подключись к localhost.\n" +
                "6. Логи Paper: reports/paper-console.log.\n" +
                "7. После успешной загрузки сервера /plugintester promote имя.jar проверяет лог Paper и копирует JAR в ready.\n\n" +
                "Команды: /plugintester scan | start | stop | status | path | promote <имя.jar>\n" +
                "Мод работает на Forge 1.21.11, а плагины запускаются на отдельном Paper " + PAPER_VERSION + " сервере. Paper-плагины не работают внутри одиночного мира Forge. Запускается отдельный локальный Paper-сервер.\n" +
                "Сервер использует Java 21, -Xms512M и -Xmx1024M. Для остановки используй /plugintester stop.\n");
        }
    }

    private static final class ScanResult {
        final Path file;
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> requiredDependencies = new ArrayList<>();
        final List<String> optionalDependencies = new ArrayList<>();
        String pluginName;
        String mainClass;
        ScanResult(Path file) { this.file = file; }
    }
}
