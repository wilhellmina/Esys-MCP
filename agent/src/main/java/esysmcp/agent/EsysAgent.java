package esysmcp.agent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;

/**
 * Entry point for {@code -javaagent:esys-agent.jar}.
 *
 * <p>Phase 0 placeholder: only proves that the agent loads inside the E-Sys JRE 8 without
 * touching anything. It must never throw out of {@link #premain}: an exception here aborts
 * E-Sys startup. Everything heavy belongs in the MCP server process, not here.
 */
public final class EsysAgent {

    private EsysAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        try {
            File log = new File(System.getProperty("java.io.tmpdir"), "esys-agent.log");
            PrintWriter w = new PrintWriter(new FileWriter(log, true));
            try {
                w.println("esys-agent loaded: java=" + System.getProperty("java.version")
                        + " vm=" + System.getProperty("java.vm.name")
                        + " args=" + args);
            } finally {
                w.close();
            }
        } catch (IOException ignored) {
            // Never let the agent take E-Sys down.
        } catch (RuntimeException ignored) {
            // Same.
        }
    }
}
