package demo.report;

import org.springframework.core.env.ConfigurableEnvironment;

public final class ReportApplication {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3 || Thread.currentThread().isVirtual()) throw new IllegalArgumentException("Usage: measure <deploymentDirectory> | serve <deploymentDirectory> <loopbackPort>");
        var root = java.nio.file.Path.of(args[1]).toRealPath();
        if (args[0].equals("measure") && args.length == 2) { ReportBootstrap.measure(root); return; }
        if (!args[0].equals("serve") || args.length != 3) throw new IllegalArgumentException("Explicit measure or serve command required");
        int port = Integer.parseInt(args[2]);
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid loopback port");
        try (var bootstrap = ReportBootstrap.open(root)) {
            var application = new org.springframework.boot.SpringApplication(ReportConfiguration.class);
            application.setRegisterShutdownHook(false);
            application.setDefaultProperties(java.util.Map.of("server.address", "127.0.0.1", "server.port", port,
                    "spring.main.banner-mode", "off", "management.endpoint.health.probes.enabled", "true",
                    "management.endpoint.health.group.readiness.include", "readinessState,protectedReports",
                    "management.endpoints.web.exposure.include", "health", "server.shutdown", "graceful"));
            application.addInitializers(context -> {
                try {
                    requireEvaluationListener(context.getEnvironment(), port);
                    bootstrap.register(context.getBeanFactory());
                } catch (Exception failure) { throw new IllegalStateException("Required listener/protected singleton setup failed before refresh", failure); }
            });
            try (var context = application.run()) {
                var web = (org.springframework.boot.web.server.context.WebServerApplicationContext) context;
                System.out.println("REPORT_READY port=" + web.getWebServer().getPort());
                var commands = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                for (String command; ; ) {
                    command = commands.readLine();
                    if ("stop".equals(command)) break;
                    if (!"drain".equals(command)) throw new IllegalArgumentException("Send drain or stop on owned stdin; EOF is not forced-termination evidence");
                    org.springframework.boot.availability.AvailabilityChangeEvent.publish(context, org.springframework.boot.availability.ReadinessState.REFUSING_TRAFFIC);
                    bootstrap.lifecycle.stop();
                    System.out.println("REPORT_DRAIN " + bootstrap.lifecycle.diagnostics().getState());
                }
            }
        }
    }

    static void requireEvaluationListener(ConfigurableEnvironment environment, int requestedPort) {
        String address = environment.getRequiredProperty("server.address");
        int port = environment.getRequiredProperty("server.port", Integer.class);
        Integer managementPort = environment.getProperty("management.server.port", Integer.class);
        String managementAddress = environment.getProperty("management.server.address");
        if (!"127.0.0.1".equals(address) || port != requestedPort
                || (managementPort != null && (managementPort == 0 || managementPort != requestedPort))
                || (managementAddress != null && !"127.0.0.1".equals(managementAddress))) {
            throw new IllegalStateException("Evaluation requires 127.0.0.1 and the CLI port, without a separate management listener");
        }
    }
}
