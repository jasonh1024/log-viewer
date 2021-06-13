package com.logviewer;

import com.logviewer.config.LogViewerServerConfig;
import com.logviewer.data2.LogContextHolder;
import com.logviewer.web.LogViewerServlet;
import com.logviewer.web.LogViewerWebsocket;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
import org.eclipse.jetty.security.*;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Password;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import javax.websocket.server.ServerEndpointConfig;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URL;
import java.util.Collections;
import java.util.List;

public class LogViewerMain {

    private static final Logger LOG = LoggerFactory.getLogger(LogViewerMain.class);

    public static final String PASSWORD_MD_5 = "password-md5";
    public static final String PASSWORD = "password";
    public static final String REALM_NAME = "log-viewer-realm";
    public static final String USER_ROLE = "user";

    private static final String PROP_AUTHENTICATION_ENABLED = "authentication.enabled";

    @Value("${log-viewer.server.port:8111}")
    private int port;
    @Value("${log-viewer.server.context-path:/}")
    private String contextPath;
    @Value("${log-viewer.server.interface:}")
    private String serverInterface;
    @Value("${log-viewer.server.enabled:true}")
    private boolean enabled;
    @Value("${log-viewer.use-web-socket:true}")
    private boolean useWebSocket;
    @Value("${" + PROP_AUTHENTICATION_ENABLED + ":false}")
    private boolean authenticationEnabled;

    private static Server server;

    public boolean startup() throws Exception {
        boolean closeAppContext = false;

        ApplicationContext appCtx = LogContextHolder.getInstance();
        if (appCtx == null) {
            appCtx = new AnnotationConfigApplicationContext(LvStandaloneConfig.class, LogViewerServerConfig.class);
            LogContextHolder.setInstance(appCtx);
            closeAppContext = true;
        }

        appCtx.getAutowireCapableBeanFactory().autowireBeanProperties(this, AutowireCapableBeanFactory.AUTOWIRE_NO, false);

        if (!enabled)
            return false;

        try {
            Server srv = new Server();
            ServerConnector connector=new ServerConnector(srv);
            connector.setPort(port);
            if (!serverInterface.isEmpty()) {
                InetAddress e = InetAddress.getByName(serverInterface);
                connector.setHost(e.getHostAddress());
            }
            srv.setConnectors(new Connector[]{connector});

            WebAppContext webAppCtx = new WebAppContext();

            webAppCtx.setContextPath(contextPath);

            String webXmlStr = LogViewerMain.class.getClassLoader().getResource("log-viewer-web/WEB-INF/web.xml").toString();
            URL webAppUrl = new URL(webXmlStr.substring(0, webXmlStr.length() - "WEB-INF/web.xml".length()));

            webAppCtx.setBaseResource(Resource.newResource(webAppUrl));
            webAppCtx.setAttribute(LogViewerServlet.SPRING_CONTEXT_PROPERTY, appCtx);

            if (authenticationEnabled)
                webAppCtx.setSecurityHandler(createSecurityHandler());

            srv.setHandler(webAppCtx);

            ServletHolder lvServlet = webAppCtx.addServlet(LogViewerServlet.class, "/*");
            if (useWebSocket) {
                lvServlet.setAsyncSupported(true);
            } else {
                ServerContainer websocketCtx = WebSocketServerContainerInitializer.configureContext(webAppCtx);
                websocketCtx.addEndpoint(ServerEndpointConfig.Builder.create(LogViewerWebsocket.class, "/ws").build());

                lvServlet.setInitParameter("web-socket-path", "ws");
            }

            srv.start();

            server = srv;

            LOG.info("Web interface started: http://localhost:{}{} ({}ms)", port,
                    contextPath.equals("/") ? "" : contextPath,
                    ManagementFactory.getRuntimeMXBean().getUptime());

            return true;
        }
        finally {
            if (server == null) {
                if (closeAppContext) {
                    ((ConfigurableApplicationContext)appCtx).close();
                    LogContextHolder.setInstance(null);
                }
            }
        }
    }

    private static SecurityHandler createSecurityHandler() {
        ConstraintSecurityHandler res = new ConstraintSecurityHandler();

        res.setAuthenticator(new BasicAuthenticator());
        
        res.setRealmName(res.getRealmName());

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/*");
        Constraint constraint = new Constraint(Constraint.__BASIC_AUTH, USER_ROLE);
        constraint.setAuthenticate(true);
        mapping.setConstraint(constraint);
        res.addConstraintMapping(mapping);

        res.setRoles(Collections.singleton(USER_ROLE));

        res.setLoginService(createRealm(TypesafePropertySourceFactory.getHoconConfig()));

        return res;
    }

    @Nullable
    private static LoginService createRealm(@NonNull Config config) {
        if (!config.hasPath("users")) {
            throw new IllegalArgumentException("Invalid configuration: `users = [ ... ]` sections is not defined. " +
                    "List of users must be defined when `" + PROP_AUTHENTICATION_ENABLED + "=true`");
        }

        UserStore userStore = new UserStore();

        List<? extends ConfigObject> users = config.getObjectList("users");

        for (ConfigObject user : users) {
            ConfigValue name = user.get("name");
            if (name == null)
                throw new IllegalArgumentException("Invalid configuration [line=" + user.origin().lineNumber() + "] \"name\" property is not specified for the user");

            String sName = extractString(name, "name");

            if (userStore.getUserIdentity(sName) != null)
                throw new IllegalArgumentException("Invalid configuration [line=" + user.origin().lineNumber() + "] duplicated user: \"" + sName + '"');

            userStore.addUser(sName, credential(user, sName), new String[]{USER_ROLE});
        }

        HashLoginService loginService = new HashLoginService(REALM_NAME);

        loginService.setUserStore(userStore);

        return loginService;
    }

    private static String extractString(ConfigValue value, String name) {
        if (value.valueType() != ConfigValueType.STRING) {
            throw new IllegalArgumentException("Invalid configuration [line=" + value.origin().lineNumber() + "] \""
                    + name +"\" must be a string");
        }

        String res = ((String) value.unwrapped()).trim();

        if (res.isEmpty()) {
            throw new IllegalArgumentException("Invalid configuration [line=" + value.origin().lineNumber() + "] \""
                    + name + "\" must not be empty");
        }

        return res;
    }

    private static Credential credential(ConfigObject user, String sName) {
        ConfigValue password = user.get(PASSWORD);
        ConfigValue passwordMd5 = user.get(PASSWORD_MD_5);

        if (password != null) {
            String sPassword = extractString(password, PASSWORD);

            if (passwordMd5 != null) {
                throw new IllegalArgumentException("Invalid configuration [line=" + user.origin().lineNumber() + "] user \"" + sName
                        + "\": \"password\" and \"password-md5\" properties cannot be specified at the same time");
            }

            return new Password(sPassword);
        } else {
            if (passwordMd5 == null) {
                throw new IllegalArgumentException("Invalid configuration [line=" + user.origin().lineNumber() + "] user \"" + sName
                        + "\": \"password\" or \"password-md5\" must be specified");
            }

            String md5 = extractString(passwordMd5, PASSWORD_MD_5);
            if (!md5.matches("[a-fA-F0-9]{32}")) {
                throw new IllegalStateException("Invalid configuration [line=" +passwordMd5.origin().lineNumber() + "] invalid MD5 value: " + md5);
            }

            return Credential.getCredential("MD5:" + md5);
        }
    }

    public static void main(String[] args) throws Exception {
        TypesafePropertySourceFactory.getHoconConfig(); // Checks that config is exist and valid.

        LogViewerMain run = new LogViewerMain();

        if (!run.startup()) {
            synchronized (LogViewerMain.class) {
                LogViewerMain.class.wait(); // Jetty was not started. Sleep forever to avoid closing the process.
            }
        }
    }

}
