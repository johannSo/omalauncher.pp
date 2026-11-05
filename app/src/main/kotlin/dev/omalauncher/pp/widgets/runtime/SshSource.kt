package dev.omalauncher.pp.widgets.runtime

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.pp.LanAddress
import dev.omalauncher.pp.LocalNetwork
import dev.omalauncher.pp.linux.JobState
import dev.omalauncher.pp.linux.Ssh
import dev.omalauncher.pp.linux.sshCommand
import dev.omalauncher.pp.widgets.lang.Lst
import dev.omalauncher.pp.widgets.lang.Null
import dev.omalauncher.pp.widgets.lang.Num
import dev.omalauncher.pp.widgets.lang.Obj
import dev.omalauncher.pp.widgets.lang.Str
import dev.omalauncher.pp.widgets.lang.Value
import dev.omalauncher.pp.widgets.lang.bool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The launcher's SSH server (settings › linux › ssh), for `source s = android.ssh` and `ssh(...)`: whether sshd is
 * on and listening, its port, the phone's addresses with the command to log in at each, and the keys that may.
 * A widget switches the same server settings does, not one of its own.
 */
class SshSource(
    private val context: Context,
    private val ssh: Ssh,
) {
    fun value(): Value = value(state(), addresses())

    /** Each change of the server's state at once; the addresses, which Android doesn't tell a launcher about, every few seconds. */
    fun updates(): Flow<Value> =
        combine(
            snapshotFlow { state() },
            flow {
                while (true) {
                    emit(addresses())
                    delay(ADDRESSES_EVERY_MS)
                }
            }.flowOn(Dispatchers.IO).distinctUntilChanged(),
            ::value,
        ).distinctUntilChanged()

    /**
     * ssh("on"), "off" or "toggle". On needs Linux up, as sshd runs in it: without it, settings › linux opens, where
     * it's installed or started, and [openLinux] is how. On installs openssh first if it isn't there.
     */
    fun act(
        word: String,
        openLinux: () -> Unit,
    ) {
        val on =
            when (word) {
                "on" -> true
                "off" -> false
                "toggle" -> !ssh.settings.enabled
                else -> return
            }
        when {
            !on -> ssh.turnOff()
            !ssh.linux -> openLinux()
            else -> ssh.turnOn()
        }
    }

    /** What the server's Compose state says, read in one go so a change shows as one value. */
    private data class State(
        val linux: Boolean,
        val enabled: Boolean,
        val listening: Boolean,
        val failed: Boolean,
        val settingUp: Boolean,
        val setupFailed: List<String>?,
        val port: Int,
        val password: Boolean,
        val fingerprint: String?,
        val keys: List<Triple<String, String, String>>,
        val log: List<String>,
    )

    private fun state(): State {
        val setup = ssh.setup
        return State(
            linux = ssh.linux,
            enabled = ssh.settings.enabled,
            listening = ssh.listening,
            failed = ssh.failed,
            settingUp = setup is JobState.Working,
            setupFailed = (setup as? JobState.Failed)?.log,
            port = ssh.settings.port,
            password = ssh.settings.password,
            fingerprint = ssh.fingerprint,
            keys = ssh.keys.map { Triple(it.type, it.comment, it.fingerprint) },
            log = ssh.log,
        )
    }

    private fun addresses(): List<LanAddress> = runCatching { LocalNetwork.lanAddresses() }.getOrDefault(emptyList())

    private fun value(
        s: State,
        addresses: List<LanAddress>,
    ): Value {
        val status =
            when {
                s.listening -> "on"
                s.failed -> "failed"
                s.settingUp -> "starting"
                !s.enabled -> "off"
                !s.linux -> "waiting"
                else -> "starting"
            }
        // Why it isn't running: sshd's own last words, or the setup's (installing openssh) when that failed.
        val error =
            when {
                s.failed -> s.log.lastOrNull { it.isNotBlank() }
                !s.enabled && s.setupFailed != null -> s.setupFailed.lastOrNull { it.isNotBlank() }
                else -> null
            }
        val list =
            addresses.map { a ->
                Obj(
                    mapOf(
                        "iface" to Str(a.iface),
                        "host" to Str(a.host),
                        "ipv6" to bool(':' in a.host),
                        "command" to Str(sshCommand(s.port, a.host)),
                    ),
                )
            }
        val first = addresses.firstOrNull()
        return Obj(
            mapOf(
                "linux" to bool(s.linux),
                "enabled" to bool(s.enabled),
                "listening" to bool(s.listening),
                "status" to Str(status),
                "error" to (error?.let(::Str) ?: Null),
                "port" to Num(s.port.toDouble()),
                "user" to Str("root"),
                "password" to bool(s.password),
                "host" to (first?.host?.let(::Str) ?: Null),
                "iface" to (first?.iface?.let(::Str) ?: Null),
                "command" to (first?.let { Str(sshCommand(s.port, it.host)) } ?: Null),
                "addresses" to Lst(list),
                "localNetwork" to bool(!LocalNetwork.missing(context)),
                "fingerprint" to (s.fingerprint?.let(::Str) ?: Null),
                "keys" to Lst(s.keys.map { (type, comment, fp) -> Obj(mapOf("type" to Str(type), "comment" to Str(comment), "fingerprint" to Str(fp))) }),
                "log" to Lst(s.log.takeLast(LOG_LINES).map(::Str)),
            ),
        )
    }

    private companion object {
        const val ADDRESSES_EVERY_MS = 5_000L
        const val LOG_LINES = 20
    }
}
