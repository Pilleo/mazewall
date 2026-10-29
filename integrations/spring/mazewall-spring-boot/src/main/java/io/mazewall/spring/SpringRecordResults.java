package io.mazewall.spring;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.springframework.asm.ClassReader;
import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.Label;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;

/** Closed materialized Java record contract; not a serializer or arbitrary DTO walker. */
final class SpringRecordResults {
    private static final Set<Class<?>> BOXES = Set.of(Boolean.class, Byte.class, Short.class, Integer.class,
            Long.class, Float.class, Double.class, Character.class);
    private static final Set<String> SERIALIZATION_HOOKS = Set.of("writeReplace", "readResolve", "writeObject", "readObject", "readObjectNoData", "readExternal", "writeExternal");
    private static final int MAX_STRING_BYTES = 65_536;
    private SpringRecordResults() {}
    static void validateShape(Class<?> type, Class<?> deployment) {
        if (!type.isRecord() || type.getTypeParameters().length != 0 || !sameDeployment(type, deployment)) throw rejected();
        for (Class<?> enclosing = type; enclosing != null; enclosing = enclosing.getEnclosingClass()) {
            if (!Modifier.isPublic(enclosing.getModifiers())) throw rejected();
        }
        if (!type.getModule().isExported(type.getPackageName(), SpringRecordResults.class.getModule())) throw rejected();
        RecordComponent[] components = type.getRecordComponents();
        if (components.length < 1 || components.length > 16) throw rejected();
        for (var component : components) {
            Class<?> value = component.getType();
            if (!value.isPrimitive() && value != String.class && !BOXES.contains(value)) throw rejected();
        }
        if (Arrays.stream(type.getDeclaredMethods()).anyMatch(method -> SERIALIZATION_HOOKS.contains(method.getName()))
                || Arrays.stream(type.getMethods()).anyMatch(method -> SERIALIZATION_HOOKS.contains(method.getName()))) throw rejected();
        String owner = Type.getInternalName(type);
        try (var input = type.getResourceAsStream("/" + owner + ".class")) {
            if (input == null) throw rejected();
            byte[] bytes = input.readNBytes(1_048_577);
            if (bytes.length > 1_048_576) throw rejected();
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                private int accessors;
                @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (descriptor.equals("Lkotlin/Metadata;")) throw rejected();
                    return null;
                }
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    for (var component : components) {
                        if (component.getName().equals(name) && descriptor.equals("()" + Type.getDescriptor(component.getType()))) {
                            if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNCHRONIZED)) != Opcodes.ACC_PUBLIC) throw rejected();
                            accessors++;
                            return new Accessor(owner, component);
                        }
                    }
                    return null;
                }
                @Override public void visitEnd() { if (accessors != components.length) throw rejected(); }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException failure) { throw new IllegalStateException("Cannot inspect reviewed record bytes", failure); }
    }
    static void validateValue(Class<?> type, Object value) {
        if (!type.isRecord() || value == null) return;
        if (value.getClass() != type) throw new InvalidValue();
        int bytes = 0;
        for (var component : type.getRecordComponents()) {
            try {
                Object item = component.getAccessor().invoke(value);
                if (item instanceof String text) {
                    if (text.length() > MAX_STRING_BYTES || !StandardCharsets.UTF_8.newEncoder().canEncode(text)) throw new InvalidValue();
                    bytes += text.getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > MAX_STRING_BYTES) throw new InvalidValue();
                }
            } catch (ReflectiveOperationException failure) { throw new InvalidValue(failure); }
        }
    }

    private static boolean sameDeployment(Class<?> type, Class<?> deployment) {
        var actual = type.getProtectionDomain().getCodeSource();
        var expected = deployment.getProtectionDomain().getCodeSource();
        if (type.getClassLoader() != deployment.getClassLoader() || actual == null || expected == null
                || actual.getLocation() == null || expected.getLocation() == null) return false;
        try { return actual.getLocation().toURI().equals(expected.getLocation().toURI()); }
        catch (java.net.URISyntaxException failure) { throw new IllegalStateException("Invalid reviewed record origin", failure); }
    }
    private static IllegalStateException rejected() { return new IllegalStateException("Result requires a public same-deployment record with1-16 scalar/String components and conventional field accessors, without serialization hooks"); }
    static final class InvalidValue extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        InvalidValue() { super("Record result exceeds the bounded materialized value contract"); }
        InvalidValue(Throwable cause) { super("Record result cannot be read safely", cause); }
    }

    /** Admit only ALOAD0, own GETFIELD, typed RETURN; every other executable instruction rejects. */
    private static final class Accessor extends MethodVisitor {
        private final String owner;
        private final RecordComponent component;
        private int instructions;
        Accessor(String owner, RecordComponent component) { super(Opcodes.ASM9); this.owner = owner; this.component = component; }
        @Override public void visitVarInsn(int opcode, int varIndex) { if (instructions++ != 0 || opcode != Opcodes.ALOAD || varIndex != 0) throw rejected(); }
        @Override public void visitFieldInsn(int opcode, String fieldOwner, String name, String descriptor) {
            if (instructions++ != 1 || opcode != Opcodes.GETFIELD || !owner.equals(fieldOwner)
                    || !component.getName().equals(name) || !Type.getDescriptor(component.getType()).equals(descriptor)) throw rejected();
        }
        @Override public void visitInsn(int opcode) { if (instructions++ != 2 || opcode != Type.getType(component.getType()).getOpcode(Opcodes.IRETURN)) throw rejected(); }
        @Override public void visitEnd() { if (instructions != 3) throw rejected(); }
        @Override public void visitIntInsn(int opcode, int operand) { throw rejected(); }
        @Override public void visitTypeInsn(int opcode, String type) { throw rejected(); }
        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) { throw rejected(); }
        @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle handle, Object... args) { throw rejected(); }
        @Override public void visitJumpInsn(int opcode, Label label) { throw rejected(); }
        @Override public void visitLdcInsn(Object value) { throw rejected(); }
        @Override public void visitIincInsn(int varIndex, int increment) { throw rejected(); }
        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) { throw rejected(); }
        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) { throw rejected(); }
        @Override public void visitMultiANewArrayInsn(String descriptor, int dimensions) { throw rejected(); }
        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) { throw rejected(); }
    }
}
