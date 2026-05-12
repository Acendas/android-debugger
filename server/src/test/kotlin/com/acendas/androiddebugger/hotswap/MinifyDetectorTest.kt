package com.acendas.androiddebugger.hotswap

import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinifyDetectorTest {

    @Test
    fun heavily_minified_returns_true() {
        val vm = mockVm(makeClasses("com.example.app", letters = 30, normal = 5))
        assertTrue(MinifyDetector.isMinifiedBuild(vm, "com.example.app"))
    }

    @Test
    fun mostly_named_returns_false() {
        val vm = mockVm(makeClasses("com.example.app", letters = 2, normal = 40))
        assertFalse(MinifyDetector.isMinifiedBuild(vm, "com.example.app"))
    }

    @Test
    fun too_few_classes_returns_false() {
        val vm = mockVm(makeClasses("com.example.app", letters = 5, normal = 2))
        // Below the MIN_CLASSES_FOR_DECISION threshold.
        assertFalse(MinifyDetector.isMinifiedBuild(vm, "com.example.app"))
    }

    @Test
    fun synthetic_inner_classes_are_excluded() {
        // Lots of `Foo$1` style inners; outer classes named normally.
        val classes = (1..30).map { "com.example.app.Foo\$$it" } +
            (1..30).map { "com.example.app.WellNamedClass$it" }
        val vm = mockVm(classes)
        assertFalse(
            MinifyDetector.isMinifiedBuild(vm, "com.example.app"),
            "synthetic inners should not trip the minify heuristic",
        )
    }

    @Test
    fun unsupported_prefix_returns_false() {
        // App-package classes are all normal, but the heuristic prefix is wrong.
        val vm = mockVm(makeClasses("ai.startup", letters = 30, normal = 5))
        // appPackagePrefix is null → fall through to heuristic prefixes (com/io/org/net/ca).
        // 'ai' isn't in the heuristic list, so we'd see no candidates → false.
        assertFalse(MinifyDetector.isMinifiedBuild(vm, null))
    }

    private fun makeClasses(pkg: String, letters: Int, normal: Int): List<String> {
        val out = mutableListOf<String>()
        // Single-letter classes: a, b, c, ...
        for (i in 0 until letters) {
            val name = ('a' + (i % 26)).toString() + if (i >= 26) "${i / 26}" else ""
            // For names that need exactly two chars, ensure all are letters (heuristic
            // requires LetterOrDigit, NOT all-digits).
            out.add("$pkg.$name")
        }
        for (i in 0 until normal) {
            out.add("$pkg.NormalNamedClass$i")
        }
        return out
    }

    private fun mockVm(classNames: List<String>): VirtualMachine {
        val vm = Mockito.mock(VirtualMachine::class.java)
        val refs = classNames.map { name ->
            val ref = Mockito.mock(ReferenceType::class.java)
            Mockito.`when`(ref.name()).thenReturn(name)
            ref
        }
        Mockito.`when`(vm.allClasses()).thenReturn(refs)
        return vm
    }
}
