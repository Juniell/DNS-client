import org.kohsuke.args4j.CmdLineException
import org.kohsuke.args4j.CmdLineParser
import org.kohsuke.args4j.Option
import java.net.InetAddress

class Launcher {
    @Option(name = "-a", usage = "inetAddress")
    private var inetAddress: InetAddress = InetAddress.getByName("10.0.0.1")

    @Option(name = "-p", usage = "port")
    private var port = 53

    fun launch(args: Array<String>) {
        val parser = CmdLineParser(this)
        try {
            parser.parseArgument(*args)
        } catch (e: CmdLineException) {
            System.err.println(e.message)
            System.err.println("java -jar DNSClient.jar [-a inetAddress] [-p port]")
            parser.printUsage(System.err)
        }
        try {
            DNSClient(inetAddress, port)
        } catch (e: Exception) {
            System.err.println(e.message)
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Launcher().launch(args)
        }
    }
}