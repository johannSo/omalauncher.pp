package dev.omalauncher.pp.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The `omalauncher-mcp` shim under bash against a stand-in for the launcher, when the machine has bash, mkfifo and
 * timeout. Not under proot, where the old shim's `wait` was seen spinning, but it holds the shim to what replaced that
 * wait: answers still come after stdin ends, SIGTERM ends it at once, and nothing of it outlives stdin.
 */
class McpShimTest {
    @Test fun answersOutliveStdin() = withShim { dir, shim ->
        val launcher = launcher(dir)
        val process = ProcessBuilder("bash", shim.path).start()
        process.outputStream.use { it.write("one\ntwo\n".toByteArray()) }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals("ONE\nTWO\n", process.inputStream.readBytes().decodeToString())
        assertEquals(0, process.exitValue())
        launcher.join(2000)
        // The launcher removed the session's folder.
        assertEquals(listOf("notify", "shim"), dir.list()!!.sorted())
    }

    @Test fun termEndsItAndNothingOutlivesStdin() = withShim { dir, shim ->
        assumeTrue("needs /proc", File("/proc/self/stat").exists())
        val launcher = launcher(dir)
        val process = ProcessBuilder("bash", shim.path).start()
        // Turned into cat on the answers: the launcher has the session, and the cat passing requests on is its child.
        val answers = waitFor { pids { it.size == 2 && it[0].endsWith("cat") && it[1].startsWith("${dir.path}/") && it[1].endsWith("/out") }.singleOrNull() }
        assertNotNull(answers)
        val requests = pids { it == listOf("cat") }.single { parent(it) == answers }
        process.destroy()
        assertTrue(process.waitFor(2, TimeUnit.SECONDS))
        // The agent goes away.
        process.outputStream.close()
        assertNotNull(waitFor { Unit.takeIf { ended(requests) } })
        launcher.join(2000)
        assertFalse(launcher.isAlive)
    }

    private fun withShim(test: (dir: File, shim: File) -> Unit) {
        assumeTrue("needs bash, mkfifo and timeout", listOf("bash", "mkfifo", "timeout").all { which(it) != null })
        val dir = Files.createTempDirectory("mcp").toFile()
        try {
            val notify = File(dir, "notify")
            assertEquals(0, ProcessBuilder("mkfifo", notify.path).start().waitFor())
            val shim = File(dir, "shim").apply { writeText(McpBridge.shim(notify.path, dir.path)) }
            test(dir, shim)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** The launcher's part of one session, as McpBridge.serve does it, answering each request upper-cased a bit later. */
    private fun launcher(dir: File) =
        thread(isDaemon = true) {
            val line = File(dir, "notify").bufferedReader().use { it.readLine() }
            val session = File(line.removePrefix(McpBridge.PREFIX))
            FileInputStream(File(session, "in")).use { input ->
                FileOutputStream(File(session, "out")).use { output ->
                    session.deleteRecursively()
                    input.bufferedReader().lineSequence().forEach {
                        Thread.sleep(200)
                        output.write("${it.uppercase()}\n".toByteArray())
                        output.flush()
                    }
                }
            }
        }

    /** [value] once it isn't null, within five seconds. */
    private fun <T : Any> waitFor(value: () -> T?): T? {
        repeat(100) {
            runCatching(value).getOrNull()?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    /** The processes whose command line [matches]. */
    private fun pids(matches: (List<String>) -> Boolean): List<Int> =
        File("/proc").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }.filter { pid ->
            runCatching { File("/proc/$pid/cmdline").readText().split('\u0000').dropLastWhile(String::isEmpty) }.getOrNull()?.let(matches) == true
        }

    /** After the command's name in /proc/[pid]/stat: its state, then its parent. */
    private fun stat(pid: Int) = runCatching { File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ') }.getOrNull()

    private fun parent(pid: Int) = stat(pid)?.getOrNull(1)?.toIntOrNull()

    private fun ended(pid: Int) = stat(pid).let { it == null || it[0] == "Z" }

    private fun which(name: String) = System.getenv("PATH").orEmpty().split(':').map { File(it, name) }.firstOrNull { it.canExecute() }?.path
}
