import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ClientChannelEvent
import org.jline.terminal.TerminalBuilder
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.EnumSet

fun main() {
    val host = "127.0.0.1"
    val port = 22
    val user = System.getProperty("user.name")

    // 1. Chiedi la password PRIMA del raw mode
    print("Password: ")
    System.out.flush()
    val password = System.console()?.readPassword()?.concatToString()
        ?: readlnOrNull() ?: error("Nessuna password fornita")

    // 2. Ora metti il terminale in raw mode
    val terminal = TerminalBuilder.builder()
        .system(true)
        .jansi(true)
        .build()
    terminal.enterRawMode()

    val size = terminal.size

    // 3. Connessione SSH
    val client = SshClient.setUpDefaultClient()
    client.start()

    val session = client.connect(user, host, port)
        .verify(10_000)
        .session

    session.addPasswordIdentity(password)
    session.auth().verify(10_000)

    // 4. Apri canale shell con PTY
    val channel = session.createShellChannel()
    channel.ptyType = "xterm-256color"
    channel.ptyColumns = size.columns
    channel.ptyLines = size.rows

    val pipeOut = PipedOutputStream()
    val pipeIn = PipedInputStream(pipeOut)
    channel.setIn(pipeIn)
    channel.setOut(System.out)
    channel.setErr(System.err)

    channel.open().verify(10_000)

    // 5. Relay: stdin locale → canale SSH
    Thread({
        val reader = terminal.reader()
        try {
            while (true) {
                val c = reader.read()
                if (c == -1) break
                pipeOut.write(c)
                pipeOut.flush()
            }
        } catch (_: Exception) {}
    }, "input-relay").apply { isDaemon = true; start() }

    // Gestione resize
    terminal.handle(org.jline.terminal.Terminal.Signal.WINCH) {
        val newSize = terminal.size
        channel.sendWindowChange(newSize.columns, newSize.rows)
    }

    // 6. Aspetta che il canale si chiuda
    channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L)

    terminal.close()
    channel.close()
    session.close()
    client.stop()
}

fun CharArray.concatToString(): String = String(this)