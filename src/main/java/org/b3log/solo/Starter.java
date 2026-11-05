/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020-present, https://github.com/bolo-blog
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.b3log.solo;

import org.apache.commons.cli.*;
import org.b3log.latke.Latkes;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.util.Strings;
import org.b3log.solo.bolo.Global;
import org.b3log.solo.util.Markdowns;

/**
 * Volo with Latke 3 and Netty (Java 17 or later).
 * <ul>
 * <li>Windows: java -cp "WEB-INF/classes;WEB-INF/lib/*;." org.b3log.solo.Starter</li>
 * <li>Unix-like: java -cp "WEB-INF/classes:WEB-INF/lib/*:." org.b3log.solo.Starter</li>
 * </ul>
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 1.2.0
 */
public final class Starter extends org.b3log.latke.http.BaseServer {

    /**
     * Main.
     *
     * @param args the specified arguments
     * @throws java.lang.Exception if start failed
     */
    public static void main(final String[] args) throws Exception {
        final Logger logger = Logger.getLogger(Starter.class);

        final Options options = new Options();
        final Option listenPortOpt = Option.builder("lp").longOpt("listen_port").argName("LISTEN_PORT").
                hasArg().desc("listen port, default is 8080").build();
        options.addOption(listenPortOpt);
        options.addOption(Option.builder("ls").longOpt("listen_socket").argName("SOCKET_PATH[,MODE]")
                .hasArg().desc("Unix socket path and optional octal mode (default 0660); overrides listenSocket and disables TCP").build());

        final Option serverSchemeOpt = Option.builder("ss").longOpt("server_scheme").argName("SERVER_SCHEME").
                hasArg().desc("browser visit protocol, default is http").build();
        options.addOption(serverSchemeOpt);

        final Option serverHostOpt = Option.builder("sh").longOpt("server_host").argName("SERVER_HOST").
                hasArg().desc("browser visit domain name, default is localhost").build();
        options.addOption(serverHostOpt);

        final Option serverPortOpt = Option.builder("sp").longOpt("server_port").argName("SERVER_PORT").
                hasArg().desc("browser visit port, default is 8080").build();
        options.addOption(serverPortOpt);

        final Option staticServerSchemeOpt = Option.builder("sss").longOpt("static_server_scheme").argName("STATIC_SERVER_SCHEME").
                hasArg().desc("browser visit static resource protocol, default is http").build();
        options.addOption(staticServerSchemeOpt);

        final Option staticServerHostOpt = Option.builder("ssh").longOpt("static_server_host").argName("STATIC_SERVER_HOST").
                hasArg().desc("browser visit static resource domain name, default is localhost").build();
        options.addOption(staticServerHostOpt);

        final Option staticServerPortOpt = Option.builder("ssp").longOpt("static_server_port").argName("STATIC_SERVER_PORT").
                hasArg().desc("browser visit static resource port, default is 8080").build();
        options.addOption(staticServerPortOpt);

        final Option runtimeModeOpt = Option.builder("rm").longOpt("runtime_mode").argName("RUNTIME_MODE").
                hasArg().desc("runtime mode (DEVELOPMENT/PRODUCTION), default is DEVELOPMENT").build();
        options.addOption(runtimeModeOpt);

        final Option luteHttpOpt = Option.builder("lute").longOpt("lute_http").argName("LUTE_HTTP").
                hasArg().desc("lute http URL, default is http://localhost:8249, see https://github.com/b3log/lute-http for more details").build();
        options.addOption(luteHttpOpt);

        options.addOption("h", "help", false, "print help for the command");

        final HelpFormatter helpFormatter = new HelpFormatter();
        helpFormatter.setWidth(120);
        final CommandLineParser commandLineParser = new DefaultParser();
        CommandLine commandLine;

        final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
        final String cmdSyntax = isWindows ? "java -cp \"WEB-INF/classes;WEB-INF/lib/*;.\" org.b3log.solo.Starter"
                : "java -cp \"WEB-INF/classes:WEB-INF/lib/*:.\" org.b3log.solo.Starter";
        final String header = "\nVolo 是基于 Bolo 的博客系统，使用 Java 17 和 Netty。\n\n";
        final String footer = "\n提需求或报告缺陷请到项目网站: https://github.com/Willh92/Volo-solo\n\n";
        try {
            commandLine = commandLineParser.parse(options, args);
        } catch (final ParseException e) {
            helpFormatter.printHelp(cmdSyntax, header, options, footer, true);

            return;
        }

        if (commandLine.hasOption("h")) {
            helpFormatter.printHelp(cmdSyntax, header, options, footer, true);

            return;
        }

        final String cliSocketPath = commandLine.getOptionValue("listen_socket");
        if (cliSocketPath != null && !cliSocketPath.isBlank() && commandLine.hasOption("listen_port")) {
            throw new IllegalArgumentException("Choose either --listen_socket/listenSocket or --listen_port");
        }

        String portArg = commandLine.getOptionValue("listen_port");
        if (!Strings.isNumeric(portArg)) {
            portArg = "8080";
        }

        // Apply pre-init runtime mode & Lute overrides before Latkes.init() so bean/template initialization sees them
        final String runtimeMode = commandLine.getOptionValue("runtime_mode");
        if (null != runtimeMode) {
            Latkes.setRuntimeMode(Latkes.RuntimeMode.valueOf(runtimeMode));
        }
        final String luteHttp = commandLine.getOptionValue("lute");
        if (null != luteHttp) {
            Markdowns.LUTE_ENGINE_URL = luteHttp;
            Markdowns.LUTE_AVAILABLE = true;
        }

        try {
            Latkes.setScanPath("org.b3log.solo");
            Latkes.init();
        } catch (final Exception e) {
            logger.log(Level.ERROR, "Latke init failed, please configure latke.props or run with args, visit https://" + Global.HACPAI_DOMAIN + "/article/1492881378588 for more details");

            System.exit(-1);
        }

        applyLatkeCliOverrides(commandLine, runtimeMode);

        final String socketPath = commandLine.getOptionValue("listen_socket", Latkes.getLocalProperty("listenSocket"));
        if (socketPath != null && !socketPath.isBlank() && commandLine.hasOption("listen_port")) {
            throw new IllegalArgumentException("Choose either --listen_socket/listenSocket or --listen_port");
        }
        if (socketPath != null && !socketPath.isBlank()
                && !io.netty.channel.epoll.Epoll.isAvailable() && !io.netty.channel.kqueue.KQueue.isAvailable()) {
            throw new IllegalStateException("Latke's Unix listener requires the Linux epoll or macOS kqueue native transport");
        }
        final int port = Integer.parseInt(portArg);
        final org.b3log.solo.web.UnixSocketSupport socket = org.b3log.solo.web.UnixSocketSupport.open(socketPath);
        final SoloApplication application = new SoloApplication();
        final Starter server = new Starter();
        try (socket) {
            Latkes.setEnabledSession(true);
            application.initialize();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.shutdown();
                if (socket != null) {
                    try { socket.close(); } catch (java.io.IOException e) { logger.log(Level.ERROR, "Socket cleanup failed", e); }
                }
                application.shutdown();
            }));
            if (socket == null) server.start(port);
            else socket.listen(path -> server.start(path), server::shutdown);
        }
    }

    private static void applyLatkeCliOverrides(final CommandLine commandLine, final String runtimeMode) {
        final String serverScheme = commandLine.getOptionValue("server_scheme");
        if (null != serverScheme) {
            Latkes.setLatkeProperty("serverScheme", serverScheme);
        }
        final String serverHost = commandLine.getOptionValue("server_host");
        if (null != serverHost) {
            Latkes.setLatkeProperty("serverHost", serverHost);
        }
        final String serverPort = commandLine.getOptionValue("server_port");
        if (null != serverPort) {
            Latkes.setLatkeProperty("serverPort", serverPort);
        }
        final String staticServerScheme = commandLine.getOptionValue("static_server_scheme");
        if (null != staticServerScheme) {
            Latkes.setLatkeProperty("staticServerScheme", staticServerScheme);
        }
        final String staticServerHost = commandLine.getOptionValue("static_server_host");
        if (null != staticServerHost) {
            Latkes.setLatkeProperty("staticServerHost", staticServerHost);
        }
        final String staticServerPort = commandLine.getOptionValue("static_server_port");
        if (null != staticServerPort) {
            Latkes.setLatkeProperty("staticServerPort", staticServerPort);
        }
        if (null != runtimeMode) {
            Latkes.setLatkeProperty("runtimeMode", runtimeMode);
        }
    }

    /**
     * Private constructor.
     */
    private Starter() {
    }
}
