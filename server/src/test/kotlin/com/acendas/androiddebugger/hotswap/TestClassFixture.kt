package com.acendas.androiddebugger.hotswap

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Synthesizes minimal but valid JVM `.class` bytes via ASM for HotSwap tests.
 * Avoids on-disk fixtures (which would need a build step). Each fixture
 * function returns ready-to-use byte[].
 */
internal object TestClassFixture {

    /**
     * Build a single-method, single-field class with the given internal name
     * ("com/example/Foo"). The method `int run(int)` returns its argument
     * unchanged. The field `int counter` is package-private.
     */
    fun simpleClass(
        internalName: String,
        methodReturn: Int = 0,
        extraMethod: Boolean = false,
        extraField: Boolean = false,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            internalName,
            null,
            "java/lang/Object",
            null,
        )
        // <init>
        val ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        ctor.visitVarInsn(Opcodes.ALOAD, 0)
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        ctor.visitInsn(Opcodes.RETURN)
        ctor.visitMaxs(0, 0)
        ctor.visitEnd()

        cw.visitField(0, "counter", "I", null, null).visitEnd()
        if (extraField) {
            cw.visitField(0, "extraField", "I", null, null).visitEnd()
        }

        // int run(int x) { return methodReturn; }  (returns the constant supplied at fixture build time)
        val run = cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "(I)I", null, null)
        when (methodReturn) {
            in -1..5 -> run.visitInsn(Opcodes.ICONST_0 + methodReturn)
            else -> run.visitLdcInsn(methodReturn)
        }
        run.visitInsn(Opcodes.IRETURN)
        run.visitMaxs(0, 0)
        run.visitEnd()

        if (extraMethod) {
            val extra = cw.visitMethod(Opcodes.ACC_PUBLIC, "extraMethod", "()V", null, null)
            extra.visitInsn(Opcodes.RETURN)
            extra.visitMaxs(0, 0)
            extra.visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    /** Build a class whose super is `kotlin/coroutines/jvm/internal/SuspendLambda`. */
    fun coroutineLikeClass(internalName: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            internalName,
            null,
            "kotlin/coroutines/jvm/internal/SuspendLambda",
            null,
        )
        // Minimal <init> calling super with a default arity argument. We pass 1 for arity.
        val ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        ctor.visitVarInsn(Opcodes.ALOAD, 0)
        // Skip calling super (would need real Kotlin runtime); just RETURN. ASM
        // COMPUTE_FRAMES will accept this for shape-diff tests since we never
        // execute the bytecode — only parse it.
        ctor.visitInsn(Opcodes.RETURN)
        ctor.visitMaxs(0, 0)
        ctor.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * Synthesize an annotation-bearing method to exercise the @Composable-warning
     * code path. Adds method `void render()` with annotation
     * `Landroidx/compose/runtime/Composable;`.
     */
    fun composableMethodClass(internalName: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            internalName,
            null,
            "java/lang/Object",
            null,
        )
        val ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        ctor.visitVarInsn(Opcodes.ALOAD, 0)
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        ctor.visitInsn(Opcodes.RETURN)
        ctor.visitMaxs(0, 0)
        ctor.visitEnd()

        val render = cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "()V", null, null)
        render.visitAnnotation("Landroidx/compose/runtime/Composable;", true).visitEnd()
        render.visitInsn(Opcodes.RETURN)
        render.visitMaxs(0, 0)
        render.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }
}
