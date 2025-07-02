import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.*;
import java.util.jar.*;
import java.util.zip.*;

public class Rewriter {

    private static final String OLD_CLASS = "net/sf/jasperreports/engine/export/JRPdfExporter";
    private static final String NEW_CLASS = "net/sf/jasperreports/pdf/JRPdfExporter";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java Rewriter input.jar output.jar");
            return;
        }

        File inputJar = new File(args[0]);
        File outputJar = new File(args[1]);

        Path tempDir = Files.createTempDirectory("jar-unpack");

        // Extract jar
        try (JarFile jarFile = new JarFile(inputJar)) {
            jarFile.stream().forEach(entry -> {
                try {
                    File outFile = new File(tempDir.toFile(), entry.getName());
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        try (InputStream is = jarFile.getInputStream(entry);
                             OutputStream os = new FileOutputStream(outFile)) {
byte[] buffer = new byte[8192];
int length;
while ((length = is.read(buffer)) != -1) {
    os.write(buffer, 0, length);
}

                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }

        // Rewrite all class files
        Files.walk(tempDir)
            .filter(path -> path.toString().endsWith(".class"))
            .forEach(Rewriter::rewriteClassFile);

        // Repack jar
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputJar))) {
            Files.walk(tempDir).forEach(path -> {
                try {
                    if (Files.isDirectory(path)) return;
                    String entryName = tempDir.relativize(path).toString().replace("\\", "/");
                    jos.putNextEntry(new JarEntry(entryName));
                    Files.copy(path, jos);
                    jos.closeEntry();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }

        System.out.println("Modified JAR written to: " + outputJar.getAbsolutePath());
    }

    private static void rewriteClassFile(Path classPath) {
        try {
            byte[] classBytes = Files.readAllBytes(classPath);
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(0);

            ClassVisitor replacer = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    descriptor = descriptor.replace(OLD_CLASS, NEW_CLASS);
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            if (owner.equals(OLD_CLASS)) owner = NEW_CLASS;
                            desc = desc.replace(OLD_CLASS, NEW_CLASS);
                            super.visitMethodInsn(opcode, owner, name, desc, itf);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                            if (owner.equals(OLD_CLASS)) owner = NEW_CLASS;
                            desc = desc.replace(OLD_CLASS, NEW_CLASS);
                            super.visitFieldInsn(opcode, owner, name, desc);
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            if (type.equals(OLD_CLASS)) type = NEW_CLASS;
                            super.visitTypeInsn(opcode, type);
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof Type && ((Type) value).getInternalName().equals(OLD_CLASS)) {
                                value = Type.getObjectType(NEW_CLASS);
                            }
                            super.visitLdcInsn(value);
                        }
                    };
                }
            };

            reader.accept(replacer, 0);
            byte[] modified = writer.toByteArray();
            Files.write(classPath, modified);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

