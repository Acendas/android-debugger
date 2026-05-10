package com.acendas.androiddebugger.jdi

import com.sun.jdi.Bootstrap
import com.sun.jdi.VirtualMachine

/**
 * JDI socket-attach helper. We always go through `localhost:<port>` because adb
 * forwards the device-side JDWP port to localhost. Per Task 1.1.3.1.
 */
object JdiAttacher {

    fun attach(host: String, port: Int, timeoutMs: Long = 5_000): VirtualMachine {
        val mgr = Bootstrap.virtualMachineManager()
        val connector = mgr.attachingConnectors().firstOrNull { it.transport().name() == "dt_socket" }
            ?: error("No dt_socket attaching connector available in this JDK.")
        val args = connector.defaultArguments().toMutableMap().apply {
            this["hostname"]?.setValue(host)
            this["port"]?.setValue(port.toString())
            this["timeout"]?.setValue(timeoutMs.toString())
        }
        return connector.attach(args)
    }
}
