import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;

public final class RedefinitionAgent {
    private RedefinitionAgent() {}

    public static void premain(String ignored, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    Module module,
                    ClassLoader loader,
                    String name,
                    Class<?> type,
                    java.security.ProtectionDomain domain,
                    byte[] bytes) {
                return null;
            }
        }, true);
        Thread.ofPlatform().start(() -> {
            try {
                Class<?> target = Class.forName("RedefinitionWorkload");
                instrumentation.retransformClasses(target);
                System.out.println("redefinition-fixture: status=PASS");
            } catch (Throwable failure) {
                failure.printStackTrace(System.err);
                System.out.println("redefinition-fixture: status=FAIL");
            }
        });
    }
}
